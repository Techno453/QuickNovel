package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
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

    /** Stored translation HTML for a chapter, or null. */
    suspend fun getCached(ctx: Context, novelId: String, chapterIndex: Int): String? =
        try {
            TranslatorDatabase.getInstance(ctx).translationDao()
                .getTranslation(novelId, chapterIndex)?.englishText
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
    ): String? = withChapterLock(novelId, chapterIndex) {
        // Another caller may have finished translating while we waited.
        getCached(ctx, novelId, chapterIndex)?.let { return@withChapterLock it }

        val source = fetchSource()?.takeIf { it.isNotBlank() } ?: return@withChapterLock null
        beforeTranslate(source)
        val engine = TranslationEngine(config, lang, fallback = SecurePrefs.fallbackEngineConfig(ctx))
        // Loaded after beforeTranslate so a fresh analysis is used.
        val inputs = StoryBibleRepository.loadChapterInputs(ctx, novelId, chapterIndex, lang)
        val result = engine.translateChapter(
            sourceText = source,
            novelId = novelId,
            chapterNum = chapterIndex,
            glossaryTerms = inputs.bible,
            context = inputs.context,
            options = inputs.options,
            onProgress = onProgress,
        )
        if (result.englishText.isBlank()) return@withChapterLock null

        StoryBibleRepository.persistChapter(ctx, novelId, chapterIndex, result, config.model, lang)
        result.englishText
    }

    /**
     * @param sourceLang provider language (zh/ja/ko); untranslatable languages only get the cache.
     * @param fetchSource loads the original chapter text/HTML; only called if a translation is needed.
     * @param chapterCount size of the novel's chapter list; with [fetchChapter], enables the
     *   automatic analysis before the novel's first translation
     * @param fetchChapter loads any chapter's source (for that analysis)
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
        fetchSource: suspend () -> String?,
    ): String? {
        getCached(ctx, novelId, chapterIndex)?.let { return it }

        val lang = TranslationLanguages.normalize(sourceLang) ?: return null
        if (!SecurePrefs.isTranslateOnDemandEnabled(ctx)) return null
        val config = SecurePrefs.engineConfig(ctx).takeIf { it.apiKey.isNotBlank() } ?: return null

        return try {
            translateIfMissing(
                ctx, novelId, chapterIndex, lang, config,
                fetchSource = fetchSource,
                beforeTranslate = { source ->
                    if (fetchChapter != null) {
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
