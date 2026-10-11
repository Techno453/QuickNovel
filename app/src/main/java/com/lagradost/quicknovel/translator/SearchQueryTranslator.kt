package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.util.GoogleTranslate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates a search query into a provider's language so that, e.g., an English query
 * finds novels on Chinese/Japanese/Korean sites.
 *
 * Google Translate is used by default; if "Use AI for search queries" is enabled and an API key
 * is set, the LLM is tried first (it knows original titles of popular novels better).
 * Results are cached in memory; concurrent lookups for the same (lang, query) share one request.
 */
object SearchQueryTranslator {
    private const val TAG = "SearchQueryTranslator"
    private const val MAX_CACHE_ENTRIES = 200

    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Self-contained entry point for any search screen (legacy SearchViewModel, or a Compose
     * view model): returns the query to send to a provider whose language is [providerLang].
     * That is the translated query when the provider is zh/ja/ko and "Translate search queries"
     * is on, otherwise [query] unchanged. Never throws (except cancellation); falls back to
     * the original query. Settings are read from [SecurePrefs] via the application context.
     */
    suspend fun translateQuery(query: String, providerLang: String?): String {
        val lang = TranslationLanguages.normalize(providerLang) ?: return query
        val ctx = BaseApplication.context
        if (!SecurePrefs.isTranslateSearchEnabled(ctx)) return query
        return translate(query, lang, ctx)
    }

    /** Translates [query] into [targetLang] (zh/ja/ko). Returns [query] on failure or no-op. */
    suspend fun translate(query: String, targetLang: String, context: Context? = null): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || isAlreadyInScript(trimmed, targetLang)) return query

        val key = "$targetLang:$trimmed"
        cache[key]?.let { return it }

        val mutex = locks.getOrPut(key) { Mutex() }
        return try {
            mutex.withLock {
                cache[key]?.let { return@withLock it }
                val translated = try {
                    translateUncached(trimmed, targetLang, context ?: BaseApplication.context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Query translation failed", e)
                    null
                }
                val result = translated?.trim()?.takeIf { it.isNotEmpty() && !it.equals(trimmed, ignoreCase = true) }
                if (result != null) cache[key] = result
                result ?: query
            }
        } finally {
            if (!mutex.isLocked) locks.remove(key, mutex)
        }
    }

    private suspend fun translateUncached(query: String, lang: String, ctx: Context?): String? {
        if (SecurePrefs.isAiSearchEnabled(ctx)) {
            val apiKey = SecurePrefs.getApiKey(ctx)
            if (apiKey != null) {
                try {
                    val engine = TranslationEngine(
                        apiKey = apiKey,
                        model = SecurePrefs.getModel(ctx),
                        baseUrl = SecurePrefs.getBaseUrl(ctx),
                        sourceLang = lang
                    )
                    engine.translateShort(query, PromptTemplates.searchQuerySystemPrompt(lang))
                        .takeIf { it.isNotBlank() }
                        ?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "LLM query translation failed, falling back to Google", e)
                }
            }
        }
        val translated = GoogleTranslate.translateTo(query, from = "auto", to = TranslationLanguages.googleCode(lang))
        return translated.takeIf { it != query }
    }

    /**
     * True if [query] is already written in [lang]'s script, so translating it is pointless
     * (Han for Chinese, kana/Han for Japanese, Hangul for Korean).
     */
    internal fun isAlreadyInScript(query: String, lang: String): Boolean {
        val code = TranslationLanguages.normalize(lang) ?: return true
        return query.any { c ->
            val block = Character.UnicodeBlock.of(c)
            when (code) {
                TranslationLanguages.CHINESE -> isHan(block)
                TranslationLanguages.JAPANESE -> isHan(block) ||
                        block == Character.UnicodeBlock.HIRAGANA ||
                        block == Character.UnicodeBlock.KATAKANA ||
                        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
                TranslationLanguages.KOREAN -> block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                        block == Character.UnicodeBlock.HANGUL_JAMO ||
                        block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
                else -> false
            }
        }
    }

    private fun isHan(block: Character.UnicodeBlock?): Boolean =
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS

    fun clearCache() = cache.clear()
}
