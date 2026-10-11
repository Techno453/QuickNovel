package com.lagradost.quicknovel.translator

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatedChapter
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Single entry point for the reader to obtain a chapter translation: returns the stored
 * translation if there is one, otherwise (when "Translate on demand while reading" is enabled
 * and an API key is set) translates the chapter with the LLM, stores it and returns it.
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

    /**
     * @param sourceLang provider language (zh/ja/ko); untranslatable languages only get the cache.
     * @param fetchSource loads the original chapter text/HTML; only called if a translation is needed.
     * @return translated chapter HTML, or null to fall back to the original chapter.
     */
    suspend fun getOrTranslate(
        ctx: Context,
        novelId: String,
        chapterIndex: Int,
        sourceLang: String?,
        fetchSource: suspend () -> String?
    ): String? {
        getCached(ctx, novelId, chapterIndex)?.let { return it }

        val lang = TranslationLanguages.normalize(sourceLang) ?: return null
        if (!SecurePrefs.isTranslateOnDemandEnabled(ctx)) return null
        val apiKey = SecurePrefs.getApiKey(ctx) ?: return null

        val lockKey = "$novelId#$chapterIndex"
        val mutex = locks.getOrPut(lockKey) { Mutex() }
        return try {
            mutex.withLock {
                // Another caller may have finished translating while we waited.
                getCached(ctx, novelId, chapterIndex)?.let { return@withLock it }

                val source = fetchSource()?.takeIf { it.isNotBlank() } ?: return@withLock null
                val db = TranslatorDatabase.getInstance(ctx)
                val model = SecurePrefs.getModel(ctx)
                val engine = TranslationEngine(
                    apiKey = apiKey,
                    model = model,
                    baseUrl = SecurePrefs.getBaseUrl(ctx),
                    sourceLang = lang
                )
                val result = engine.translateChapter(
                    sourceText = source,
                    novelId = novelId,
                    chapterNum = chapterIndex,
                    glossaryTerms = db.glossaryDao().getAllTermsSync(novelId)
                )
                if (result.englishText.isBlank()) return@withLock null

                db.translationDao().insert(
                    TranslatedChapter(
                        novelId = novelId,
                        chapterNum = chapterIndex,
                        englishText = result.englishText,
                        model = model,
                        sourceLang = lang
                    )
                )
                if (result.pendingTerms.isNotEmpty()) {
                    db.pendingTermDao().insertAll(result.pendingTerms)
                }
                result.englishText
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "On-demand translation failed for $novelId #$chapterIndex", e)
            null
        } finally {
            if (!mutex.isLocked) locks.remove(lockKey, mutex)
        }
    }
}
