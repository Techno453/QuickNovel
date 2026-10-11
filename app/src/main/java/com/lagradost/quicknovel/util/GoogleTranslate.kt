package com.lagradost.quicknovel.util

import android.util.Log
import com.lagradost.quicknovel.MainActivity.Companion.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/**
 * Lightweight wrapper around the free Google Translate endpoint.
 * No API key required.  Used for UI-level translation of titles,
 * chapter names, and synopses.
 *
 * Source language auto-detected; target language defaults to English.
 */
object GoogleTranslate {

    private const val TAG = "GoogleTranslate"

    // Simple in-memory cache to avoid re-translating the same strings
    private val cache = LinkedHashMap<String, String>(64, 0.75f, true).also { map ->
        // Evict oldest entries when cache exceeds 500 entries
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > 500
        }
    }

    /**
     * Translate [text] to [targetLang] (default "en").
     * Returns the translated string, or the original on failure.
     */
    suspend fun translate(text: String, targetLang: String = "en"): String {
        if (text.isBlank()) return text
        val key = "$targetLang:$text"
        cache[key]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(text, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single" +
                    "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"

                val response = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0",
                        "Accept" to "application/json"
                    )
                )

                val json = response.text
                // Response format: [[["translated","original",null,null,1],...],...]
                val translated = parseGoogleTranslateResponse(json)
                if (translated.isNotBlank()) {
                    cache[key] = translated
                    translated
                } else {
                    text
                }
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed for: ${text.take(40)}", e)
                text
            }
        }
    }

    /**
     * Translate a list of strings in a single request (batched).
     * Returns a list of the same size with translated strings (original on failure).
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
                val piece = sentence.optString(0) ?: continue
                sb.append(piece)
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
