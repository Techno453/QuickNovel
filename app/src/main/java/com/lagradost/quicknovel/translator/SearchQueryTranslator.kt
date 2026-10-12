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
 * finds novels on Chinese/Japanese/Korean sites (docs/search.md).
 *
 * The result is a short list of candidate queries ([QueryCandidates]), not one string: an
 * English title like "Above the sky" has a published original (高天之上) that a literal
 * translation (天空之上) never matches. Sources try the candidates in order:
 * 1. the known original title, when "Use AI for search queries" is on and an API key is set
 *    (first line of the model's reply);
 * 2. the original titles from the key-free title index ([NovelUpdatesIndex], "Associated Names"),
 *    for a Latin-script query, up to [MAX_INDEX_NAMES] in the target language's script;
 * 3. the literal translation from the free Google endpoint;
 * 4. the model's other lines (literal translation, common alternative wording).
 * A query already written in the target script is sent unchanged.
 *
 * Results are cached in memory per (lang, query); concurrent lookups share one request.
 */
object SearchQueryTranslator {
    private const val TAG = "SearchQueryTranslator"
    private const val MAX_CACHE_ENTRIES = 200

    /** Most candidates the translator itself produces per target language. */
    const val MAX_CANDIDATES = 3

    /** Most title-index names used per target language. */
    const val MAX_INDEX_NAMES = 3

    /**
     * Candidate queries for one target language. [known] is the AI's known original title (null
     * without AI or when the model gave nothing); [indexed] the title index's original names;
     * [others] the remaining candidates in order (Google literal, then the model's other lines),
     * or the untranslated query.
     */
    data class QueryCandidates(
        val known: String?,
        val indexed: List<String> = emptyList(),
        val others: List<String> = emptyList(),
    ) {
        /** The candidates in the order sources try them, de-duplicated. */
        val all: List<String> get() = distinctQueries(listOfNotNull(known) + indexed + others)

        companion object {
            fun untranslated(query: String) = QueryCandidates(null, emptyList(), listOf(query))
        }
    }

    /** Where the translator gets its raw material; replaced in tests. */
    interface Sources {
        /** Raw model reply for [query] → [lang], or null when AI search is off / has no key. */
        suspend fun ai(query: String, lang: String): String?

        /** Google translation of [query] into [lang], or null on failure. */
        suspend fun google(query: String, lang: String): String?

        /** Original CJK titles of the novel named [query] (any script), best first; empty if unknown. */
        suspend fun titleIndex(query: String): List<String>
    }

    private val cache: MutableMap<String, QueryCandidates> = Collections.synchronizedMap(
        object : LinkedHashMap<String, QueryCandidates>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryCandidates>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Self-contained entry point for any search screen: the candidates for a provider whose
     * language is [providerLang]. Untranslated when the provider is not zh/ja/ko or "Translate
     * search queries" is off. Never throws (except cancellation).
     */
    suspend fun candidatesFor(query: String, providerLang: String?): QueryCandidates {
        val lang = TranslationLanguages.normalize(providerLang)
            ?: return QueryCandidates.untranslated(query)
        val ctx = BaseApplication.context
        if (!SecurePrefs.isTranslateSearchEnabled(ctx)) return QueryCandidates.untranslated(query)
        return candidates(query, lang, ctx)
    }

    /** The first candidate for [providerLang] (legacy single-query callers). */
    suspend fun translateQuery(query: String, providerLang: String?): String =
        candidatesFor(query, providerLang).all.firstOrNull() ?: query

    /** First candidate of [query] in [targetLang] (zh/ja/ko). Returns [query] on failure or no-op. */
    suspend fun translate(query: String, targetLang: String, context: Context? = null): String =
        candidates(query, targetLang, context).all.firstOrNull() ?: query

    /** Cached candidates of [query] in [targetLang]. */
    suspend fun candidates(
        query: String,
        targetLang: String,
        context: Context? = null,
        sources: Sources = AppSources(context),
    ): QueryCandidates {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || isAlreadyInScript(trimmed, targetLang)) {
            return QueryCandidates.untranslated(query)
        }
        val key = "$targetLang:$trimmed"
        cache[key]?.let { return it }

        val mutex = locks.getOrPut(key) { Mutex() }
        return try {
            mutex.withLock {
                cache[key]?.let { return@withLock it }
                val result = computeCandidates(trimmed, targetLang, sources)
                if (result != QueryCandidates.untranslated(trimmed)) cache[key] = result
                result
            }
        } finally {
            if (!mutex.isLocked) locks.remove(key, mutex)
        }
    }

    /** Uncached: asks [sources] and merges. Never throws (except cancellation). */
    internal suspend fun computeCandidates(query: String, lang: String, sources: Sources): QueryCandidates {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || isAlreadyInScript(trimmed, lang)) return QueryCandidates.untranslated(query)
        val aiLines = try {
            sources.ai(trimmed, lang)?.let { parseModelCandidates(it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "LLM query translation failed, falling back to Google", e)
            emptyList()
        }
        val indexNames = try {
            sources.titleIndex(trimmed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Title index lookup failed", e)
            emptyList()
        }
        val google = try {
            sources.google(trimmed, lang)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Query translation failed", e)
            null
        }
        return mergeCandidates(trimmed, lang, aiLines, indexNames, google)
    }

    /**
     * Merge order: the model's first line (the known original title), the title index's names in
     * [lang]'s script (at most [MAX_INDEX_NAMES]), the Google literal, the model's other lines.
     * The translation part (model lines and Google) keeps at most [MAX_CANDIDATES]. Drops blanks,
     * duplicates and echoes of the query; the untranslated query when nothing is left.
     */
    internal fun mergeCandidates(
        query: String,
        lang: String,
        aiLines: List<String>,
        indexNames: List<String>,
        google: String?,
    ): QueryCandidates {
        val trimmed = query.trim()
        fun usable(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals(trimmed, ignoreCase = true) }
        val known = usable(aiLines.firstOrNull())
        val translated = distinctQueries(
            listOfNotNull(known, usable(google)) + aiLines.drop(1).mapNotNull { usable(it) }
        ).take(MAX_CANDIDATES)
        val indexed = NovelUpdatesParser.namesForLanguage(indexNames, lang, MAX_INDEX_NAMES)
            .filterNot { known != null && it.equals(known, ignoreCase = true) }
        val others = if (known != null) translated.drop(1) else translated
        val indexedSet = indexed.map { it.lowercase() }.toSet()
        val result = QueryCandidates(known, indexed, others.filterNot { it.lowercase() in indexedSet })
        return if (result.all.isEmpty()) QueryCandidates.untranslated(trimmed) else result
    }

    private val NUMBERING = Regex("""^\s*(?:\d{1,2}\s*[.)、:：]|[-*•·])\s*""")
    private val LABEL = Regex(
        """^(?:known\s+)?(?:original(?:\s+title)?|literal(?:\s+translation)?|alternative(?:\s+wording)?|translation|title)\s*[:：]\s*""",
        RegexOption.IGNORE_CASE
    )
    private const val QUOTES = "\"'“”‘’「」『』《》"

    /**
     * Candidate lines of a model reply: one per non-empty line, numbering ("1.", "-") and labels
     * ("Original title:") and quotes removed, de-duplicated, at most [MAX_CANDIDATES]. A one-line
     * reply (the old contract) gives one candidate.
     */
    internal fun parseModelCandidates(raw: String): List<String> {
        val cleaned = TranslationEngine.cleanModelOutput(raw)
        return distinctQueries(
            cleaned.lineSequence().map { line ->
                line.trim().replaceFirst(NUMBERING, "").replaceFirst(LABEL, "").trim().trim { it in QUOTES }.trim()
            }.filter { it.isNotEmpty() }.toList()
        ).take(MAX_CANDIDATES)
    }

    /** Keeps the first of each query (case-insensitive, whitespace-collapsed). */
    internal fun distinctQueries(queries: List<String>): List<String> {
        val seen = HashSet<String>()
        return queries.map { it.trim() }.filter { q ->
            q.isNotEmpty() && seen.add(q.lowercase().replace(Regex("""\s+"""), " "))
        }
    }

    /** The app's sources: the translation model (AI search on, key set) and Google. */
    private class AppSources(private val context: Context?) : Sources {
        override suspend fun ai(query: String, lang: String): String? {
            val ctx = context ?: BaseApplication.context
            if (!SecurePrefs.isAiSearchEnabled(ctx)) return null
            // The title lookup model (default GPT-6 Sol), not the chapter translation model.
            val config = SecurePrefs.titleLookupEngineConfig(ctx)
            if (config.apiKey.isBlank()) return null
            val engine = TranslationEngine(config, lang)
            return kotlinx.coroutines.withContext(SpendTag(SpendKind.SEARCH)) {
                engine.completeShort(query, PromptTemplates.searchQuerySystemPrompt(lang))
            }
        }

        override suspend fun titleIndex(query: String): List<String> =
            NovelUpdatesIndex.associatedNames(query)

        override suspend fun google(query: String, lang: String): String? {
            val translated = GoogleTranslate.translateTo(query, from = "auto", to = TranslationLanguages.googleCode(lang))
            return translated.takeIf { it != query }
        }
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
                TranslationLanguages.JAPANESE -> isHan(block) || isKana(block)
                TranslationLanguages.KOREAN -> isHangul(block)
                else -> false
            }
        }
    }

    internal fun isHan(block: Character.UnicodeBlock?): Boolean =
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS

    internal fun isKana(block: Character.UnicodeBlock?): Boolean =
        block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS

    internal fun isHangul(block: Character.UnicodeBlock?): Boolean =
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO

    fun clearCache() = cache.clear()
}
