package com.lagradost.quicknovel.util

import android.util.Log
import com.lagradost.quicknovel.MainActivity.Companion.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Collections

/**
 * Lightweight wrapper around the free Google Translate endpoint.
 * No API key required. Used for UI-level translation of titles, chapter names
 * and synopses, and for translating search queries.
 */
object GoogleTranslate {

    private const val TAG = "GoogleTranslate"
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"

    /** Max characters per request; longer text is split on paragraph boundaries. */
    internal const val MAX_REQUEST_CHARS = 4500

    /** Above this encoded length the text is sent as a POST form body instead of a GET query. */
    private const val MAX_GET_ENCODED_LENGTH = 6000

    private const val MAX_CACHE_ENTRIES = 500

    /** Bounded, access-ordered LRU cache. All access goes through the synchronized wrapper. */
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    /**
     * Translate [text] to [targetLang] (default "en") with auto-detected source.
     * Returns the translated string, or the original on failure.
     */
    suspend fun translate(text: String, targetLang: String = "en"): String =
        translateTo(text, from = "auto", to = targetLang)

    /**
     * Translate [text] from [from] (or "auto") into [to]. Long text is split into requests
     * of at most [MAX_REQUEST_CHARS] characters on paragraph boundaries.
     * Returns the original text for any piece that fails.
     */
    suspend fun translateTo(text: String, from: String = "auto", to: String): String {
        if (text.isBlank()) return text
        val key = "$from>$to:$text"
        cache[key]?.let { return it }

        val pieces = splitForRequest(text, MAX_REQUEST_CHARS)
        var allOk = true
        val translated = withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            for (piece in pieces) {
                if (piece.isBlank()) {
                    sb.append(piece)
                } else {
                    sb.append(requestTranslation(piece, from, to) ?: piece.also { allOk = false })
                }
            }
            sb.toString()
        }
        if (allOk && translated.isNotBlank()) cache[key] = translated
        return translated
    }

    /**
     * Splits [text] into pieces of at most [maxChars] characters, cutting after newlines where
     * possible, then after sentence punctuation / whitespace. Concatenating the pieces yields the
     * original text, so paragraph structure survives translation.
     */
    internal fun splitForRequest(text: String, maxChars: Int = MAX_REQUEST_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (text.length - start > maxChars) {
            val limit = start + maxChars
            var cut = text.lastIndexOf('\n', limit - 1).let { if (it >= start) it + 1 else -1 }
            if (cut <= start + maxChars / 4) {
                var i = limit - 1
                cut = -1
                while (i > start + maxChars / 4) {
                    val c = text[i]
                    if (c in "。！？.!?；;…" || c.isWhitespace()) {
                        cut = i + 1; break
                    }
                    i--
                }
                if (cut == -1) cut = limit
            }
            if (cut < text.length && Character.isLowSurrogate(text[cut])) cut--
            out.add(text.substring(start, cut))
            start = cut
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    private suspend fun requestTranslation(piece: String, from: String, to: String): String? {
        // Keep surrounding whitespace/newlines: Google trims them.
        val leading = piece.takeWhile { it.isWhitespace() }
        val trailing = piece.takeLastWhile { it.isWhitespace() }
        val core = piece.trim()
        if (core.isEmpty()) return piece
        return try {
            val encoded = URLEncoder.encode(core, "UTF-8")
            val baseUrl = "$ENDPOINT?client=gtx&sl=$from&tl=$to&dt=t"
            val headers = mapOf("User-Agent" to "Mozilla/5.0", "Accept" to "application/json")
            val response = if (encoded.length <= MAX_GET_ENCODED_LENGTH) {
                app.get("$baseUrl&q=$encoded", headers = headers)
            } else {
                app.post(baseUrl, headers = headers, data = mapOf("q" to core))
            }
            // Response format: [[["translated","original",null,null,1],...],...]
            parseGoogleTranslateResponse(response.text).takeIf { it.isNotBlank() }
                ?.let { leading + it + trailing }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed for: ${core.take(40)}", e)
            null
        }
    }

    /**
     * Translate a list of strings. Returns a list of the same size with translated
     * strings (original on failure).
     */
    suspend fun translateBatch(texts: List<String>, targetLang: String = "en"): List<String> {
        if (texts.isEmpty()) return texts
        // Translate each individually (Google's free endpoint has small limits)
        return texts.map { translate(it, targetLang) }
    }

    /** Parse the nested JSON array returned by Google Translate. */
    private fun parseGoogleTranslateResponse(json: String): String {
        return try {
            val root = JSONArray(json)
            val sentences = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.optJSONArray(i) ?: continue
                if (sentence.isNull(0)) continue
                sb.append(sentence.optString(0))
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Google Translate response", e)
            ""
        }
    }

    /** Clear the in-memory cache (e.g. when target language changes). */
    fun clearCache() {
        cache.clear()
    }
}
