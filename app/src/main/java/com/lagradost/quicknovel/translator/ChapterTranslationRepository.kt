package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for the reader to obtain a chapter translation: returns the stored
 * translation if there is one, otherwise (when "Translate on demand while reading" is enabled
 * and an API key is set) translates the chapter with the LLM, stores it and returns it.
 *
 * Every path that translates one chapter (reader on demand, read-ahead, batch job) goes through
 * [withChapterLock], so the same chapter is never translated twice concurrently.
 */
object ChapterTranslationRepository {
    private const val TAG = "ChapterTranslationRepo"

    /** Prevents translating the same chapter twice when the reader preloads concurrently. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The stored row a caller may serve: any row inside a [preview]; outside one, only a normal
     * translation (a preview row counts as untranslated and is overwritten by the normal path).
     */
    fun servable(entry: TranslatedChapter?, preview: Boolean = false): TranslatedChapter? =
        entry?.takeIf { preview || !it.preview }

    /** Stored translation HTML for a chapter, or null (preview rows only when [preview]). */
    suspend fun getCached(ctx: Context, novelId: String, chapterIndex: Int, preview: Boolean = false): String? =
        try {
            servable(
                TranslatorDatabase.getInstance(ctx).translationDao().getTranslation(novelId, chapterIndex),
                preview,
            )?.englishText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read translation", e)
            null
        }

    /** Runs [block] while holding the per-chapter lock shared by all translation paths. */
    suspend fun <T> withChapterLock(novelId: String, chapterIndex: Int, block: suspend () -> T): T {
        val lockKey = "$novelId#$chapterIndex"
        val mutex = locks.getOrPut(lockKey) { Mutex() }
        return try {
            mutex.withLock { block() }
        } finally {
            if (!mutex.isLocked) locks.remove(lockKey, mutex)
        }
    }

    /**
     * Translates chapter [chapterIndex] unless it is already stored, under the chapter lock, with
     * the same Story Bible, style and continuity context as the batch translator.
     *
     * @param beforeTranslate runs with the fetched source right before the translation (used for
     *   the automatic analysis, so the source fetched here is reused)
     * @return the stored or new translation HTML, or null if the source could not be loaded
     * @throws TranslationApiException and other errors from the engine
     */
    suspend fun translateIfMissing(
        ctx: Context,
        novelId: String,
        chapterIndex: Int,
        lang: String,
        config: EngineConfig,
        fetchSource: suspend () -> String?,
        beforeTranslate: suspend (source: String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
        /** What the paid calls are recorded as in the spend ledger (the job's meter is kept). */
        spendKind: SpendKind = SpendKind.TRANSLATE,
        /**
         * Re-translate a stored translation marked stale. The source is fetched and compared
         * first: if it matches the stored hash again, the stored translation is kept (no paid call).
         */
        retranslateStale: Boolean = false,
        /**
         * Preview: translate without the glossary ([StoryBibleRepository.ChapterInputs.preview])
         * and store only the chapter row (see [StoryBibleRepository.persistChapter]).
         */
        preview: Boolean = false,
    ): String? = withChapterLock(novelId, chapterIndex) {
        translateIfMissingCore(
            dbStore(ctx), novelId, chapterIndex, retranslateStale, fetchSource, beforeTranslate, preview,
        ) { source, hash ->
            val engine = TranslationEngine(config, lang, fallback = SecurePrefs.fallbackEngineConfig(ctx))
            // Loaded after beforeTranslate so a fresh analysis is used.
            val inputs = if (preview) {
                StoryBibleRepository.loadPreviewInputs(ctx, novelId, lang)
            } else {
                StoryBibleRepository.loadChapterInputs(ctx, novelId, chapterIndex, lang)
            }
            val outer = coroutineContext[SpendTag]
            val tag = SpendTag(spendKind, novelId, chapterIndex, outer?.batchId, outer?.meter)
            val result = withContext(tag) {
                engine.translateChapter(
                    sourceText = source,
                    novelId = novelId,
                    chapterNum = chapterIndex,
                    glossaryTerms = inputs.bible,
                    context = inputs.context,
                    options = inputs.options,
                    onProgress = onProgress,
                )
            }
            if (result.englishText.isBlank()) return@translateIfMissingCore null

            StoryBibleRepository.persistChapter(ctx, novelId, chapterIndex, result, config.model, lang, hash, preview)
            result.englishText
        }
    }

    /** The stored-translation lookups the decisions below need (the translator DB; fakes in tests). */
    internal interface ChapterStore {
        suspend fun entry(novelId: String, chapterIndex: Int): TranslatedChapter?
        suspend fun markSourceState(novelId: String, chapterIndex: Int, hash: String, stale: Boolean)
    }

    private fun dbStore(ctx: Context) = object : ChapterStore {
        override suspend fun entry(novelId: String, chapterIndex: Int) = getCachedEntry(ctx, novelId, chapterIndex)
        override suspend fun markSourceState(novelId: String, chapterIndex: Int, hash: String, stale: Boolean) =
            this@ChapterTranslationRepository.markSourceState(ctx, novelId, chapterIndex, hash, stale)
    }

    /**
     * What [translateIfMissing] decides, for every kind of novel (online provider or imported
     * book; the chapter source only differs in [fetchSource]): serve the stored translation;
     * otherwise fetch the source, keep a stale translation whose source is back to the stored
     * hash, else run [beforeTranslate] and [translate] (which stores the result with the hash).
     * Outside a [preview] a stored preview row is ignored ([servable]): the chapter is translated
     * normally and the row replaced; inside one it is served like any stored translation.
     */
    internal suspend fun translateIfMissingCore(
        store: ChapterStore,
        novelId: String,
        chapterIndex: Int,
        retranslateStale: Boolean,
        fetchSource: suspend () -> String?,
        beforeTranslate: suspend (source: String) -> Unit,
        preview: Boolean = false,
        translate: suspend (source: String, hash: String) -> String?,
    ): String? {
        // Another caller may have finished translating while we waited.
        val cached = servable(store.entry(novelId, chapterIndex), preview)
        if (cached != null && !(retranslateStale && cached.stale)) return cached.englishText

        val source = fetchSource()?.takeIf { it.isNotBlank() } ?: return null
        val hash = SourceHash.of(source)
        if (cached != null && SourceHash.compare(cached.sourceHash, hash) == SourceHash.Check.MATCH) {
            // The source is back to what was translated: reuse it, skip the paid call.
            store.markSourceState(novelId, chapterIndex, hash, stale = false)
            return cached.englishText
        }
        beforeTranslate(source)
        return translate(source, hash)
    }

    // ---- Source-hash cache invalidation ----

    /** The stored translation row (text, model, hash, stale flag), or null. */
    suspend fun getCachedEntry(ctx: Context, novelId: String, chapterIndex: Int): TranslatedChapter? =
        try {
            TranslatorDatabase.getInstance(ctx).translationDao().getTranslation(novelId, chapterIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read translation", e)
            null
        }

    private suspend fun markSourceState(ctx: Context, novelId: String, chapterIndex: Int, hash: String, stale: Boolean) {
        try {
            TranslatorDatabase.getInstance(ctx).translationDao().setSourceState(novelId, chapterIndex, hash, stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not update the source state of $novelId #$chapterIndex", e)
        }
    }

    /**
     * Call whenever a chapter's source was fetched anyway (reader reload, analysis, a job): compares
     * it with the hash stored with the translation and
     * - CHANGED: marks the translation stale (still readable; never re-translated automatically);
     * - MATCH: clears a stale flag;
     * - UNKNOWN (translation from before DB v6): treated as valid and the hash is filled in, so later
     *   changes are detected.
     * Cheap (one hash + one DB read). Never fetch a source only to call this.
     */
    suspend fun checkSourceHash(ctx: Context, novelId: String, chapterIndex: Int, source: String): SourceHash.Check =
        checkSourceHashCore(dbStore(ctx), novelId, chapterIndex, source)

    internal suspend fun checkSourceHashCore(store: ChapterStore, novelId: String, chapterIndex: Int, source: String): SourceHash.Check {
        if (source.isBlank()) return SourceHash.Check.NO_TRANSLATION
        // Preview rows are throwaways: never checked or marked stale.
        val stored = servable(store.entry(novelId, chapterIndex)) ?: return SourceHash.Check.NO_TRANSLATION
        val hash = SourceHash.of(source)
        val check = SourceHash.compare(stored.sourceHash, hash)
        when (check) {
            SourceHash.Check.CHANGED -> if (!stored.stale) {
                try {
                    Log.i(TAG, "Source of $novelId #$chapterIndex changed since it was translated: marked stale")
                } catch (_: RuntimeException) {
                    // android.util.Log is not available in plain JVM tests
                }
                store.markSourceState(novelId, chapterIndex, stored.sourceHash, stale = true)
            }
            SourceHash.Check.MATCH -> if (stored.stale) store.markSourceState(novelId, chapterIndex, hash, stale = false)
            SourceHash.Check.UNKNOWN -> store.markSourceState(novelId, chapterIndex, hash, stale = false)
            SourceHash.Check.NO_TRANSLATION -> Unit
        }
        return check
    }

    /** True if the stored translation of the chapter is marked stale (its source changed). */
    suspend fun isStale(ctx: Context, novelId: String, chapterIndex: Int): Boolean =
        servable(getCachedEntry(ctx, novelId, chapterIndex))?.stale == true

    /** Chapter indices of [novelId] whose translation is stale. */
    suspend fun staleChapters(ctx: Context, novelId: String): List<Int> = try {
        TranslatorDatabase.getInstance(ctx).translationDao().getStaleChapterNums(novelId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read stale chapters", e)
        emptyList()
    }

    /** Live list of stale chapter indices of [novelId] (for the reader / chapter list). */
    fun observeStaleChapters(ctx: Context, novelId: String): Flow<List<Int>> =
        TranslatorDatabase.getInstance(ctx).translationDao().observeStaleChapterNums(novelId)

    /**
     * True when an on-demand translation runs the automatic analysis first: never in a preview,
     * otherwise when the caller can fetch other chapters (the analysis reads the first ones).
     */
    internal fun analyzesFirst(preview: Boolean, canFetchChapters: Boolean): Boolean = !preview && canFetchChapters

    /** True if a stored row was made by a preview (`translated_chapters.preview`, DB v9). */
    fun isPreviewTranslation(entry: TranslatedChapter?): Boolean = entry?.preview == true

    /**
     * @param sourceLang provider language (zh/ja/ko); untranslatable languages only get the cache.
     * @param fetchSource loads the original chapter text/HTML; only called if a translation is needed.
     * @param chapterCount size of the novel's chapter list; with [fetchChapter], enables the
     *   automatic analysis before the novel's first translation
     * @param fetchChapter loads any chapter's source (for that analysis)
     * @param preview the result page's Preview: a stored translation is still served; a missing one
     *   is translated alone, without the analysis, the glossary or story context
     *   ([TranslationOptions.preview]), and stored as a preview row so it is never paid twice;
     *   outside a preview such a row counts as untranslated
     * @return translated chapter HTML, or null to fall back to the original chapter.
     */
    suspend fun getOrTranslate(
        ctx: Context,
        novelId: String,
        chapterIndex: Int,
        sourceLang: String?,
        chapterCount: Int = 0,
        fetchChapter: (suspend (Int) -> String?)? = null,
        analysisListener: NovelAnalyzer.Listener? = null,
        preview: Boolean = false,
        fetchSource: suspend () -> String?,
    ): String? {
        getCached(ctx, novelId, chapterIndex, preview)?.let { return it }

        val lang = TranslationLanguages.normalize(sourceLang) ?: return null
        if (!SecurePrefs.isTranslateOnDemandEnabled(ctx)) return null
        val config = SecurePrefs.engineConfig(ctx).takeIf { it.apiKey.isNotBlank() } ?: return null

        return try {
            translateIfMissing(
                ctx, novelId, chapterIndex, lang, config,
                fetchSource = fetchSource,
                preview = preview,
                beforeTranslate = { source ->
                    if (analyzesFirst(preview, canFetchChapters = fetchChapter != null) && fetchChapter != null) {
                        // Single-flight per novel: other chapters (and the service) wait for this one.
                        NovelAnalyzer.ensureAnalyzed(
                            ctx, novelId, lang, chapterCount,
                            fetch = { i -> if (i == chapterIndex) source else fetchChapter(i) },
                            listener = analysisListener,
                        )
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "On-demand translation failed for $novelId #$chapterIndex", e)
            null
        }
    }
}
