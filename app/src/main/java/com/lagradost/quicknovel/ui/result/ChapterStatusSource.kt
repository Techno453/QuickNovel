package com.lagradost.quicknovel.ui.result

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.translate.BatchManager
import com.lagradost.quicknovel.ui.translate.ReadAheadState
import kotlinx.coroutines.CancellationException

/** Translation status of one chapter, shown as a small chip in the result page's chapter list. */
enum class ChapterTranslationStatus {
    NONE,

    /** A stored translation exists. */
    TRANSLATED,

    /** A stored translation exists but the source chapter changed since. */
    STALE,

    /** Waiting to be translated (read-ahead or overnight batch) or being translated now. */
    QUEUED,
}

/** Where the chapter list gets the statuses from. */
interface ChapterStatusSource {
    /** Chapter indices with a stored translation. */
    suspend fun translated(novelId: String): Set<Int>

    /** Chapter indices whose stored translation is out of date ("source changed"). */
    suspend fun stale(novelId: String): Set<Int>

    /** Chapter indices queued (read-ahead queue, unfinished overnight batch) or in progress. */
    suspend fun queued(novelId: String): Set<Int>

    companion object {
        /** Status of each chapter; queued wins over stale, stale over translated. */
        suspend fun statuses(source: ChapterStatusSource, novelId: String): Map<Int, ChapterTranslationStatus> =
            merge(source.translated(novelId), source.stale(novelId), source.queued(novelId))

        fun merge(translated: Set<Int>, stale: Set<Int>, queued: Set<Int>): Map<Int, ChapterTranslationStatus> {
            val map = HashMap<Int, ChapterTranslationStatus>()
            for (i in translated) map[i] = ChapterTranslationStatus.TRANSLATED
            for (i in stale) map[i] = ChapterTranslationStatus.STALE
            for (i in queued) map[i] = ChapterTranslationStatus.QUEUED
            return map
        }
    }
}

/** The raw lookups behind [TranslatorChapterStatusSource] (a seam for tests; no Android types). */
interface ChapterStatusQueries {
    /** `translated_chapters` rows of the novel. */
    suspend fun translatedChapterNums(novelId: String): List<Int>

    /** `translated_chapters` rows flagged stale (source hash changed). */
    suspend fun staleChapterNums(novelId: String): List<Int>

    /** Chapters of the novel in overnight batches that have not finished. */
    suspend fun chaptersInUnfinishedBatches(novelId: String): Set<Int>

    /** The read-ahead queue's novel and its pending chapters (one novel at a time). */
    fun readAheadPending(): Pair<String?, List<Int>>
}

/**
 * Reads the translator database, the overnight batch jobs and the read-ahead queue. A failing
 * lookup only drops its own chips (logged), never the others.
 */
class TranslatorChapterStatusSource(private val queries: ChapterStatusQueries) : ChapterStatusSource {
    companion object {
        private const val TAG = "ChapterStatus"

        /** The app's source: translator DB, [BatchManager] and [ReadAheadState]. */
        fun create(ctx: Context): TranslatorChapterStatusSource {
            val appCtx = ctx.applicationContext
            return TranslatorChapterStatusSource(object : ChapterStatusQueries {
                private val dao get() = TranslatorDatabase.getInstance(appCtx).translationDao()
                override suspend fun translatedChapterNums(novelId: String) = dao.getTranslatedChapterNums(novelId)
                override suspend fun staleChapterNums(novelId: String) = dao.getStaleChapterNums(novelId)
                override suspend fun chaptersInUnfinishedBatches(novelId: String) =
                    BatchManager.chaptersInUnfinishedBatches(appCtx, novelId)

                override fun readAheadPending(): Pair<String?, List<Int>> {
                    val queue = ReadAheadState.queue
                    return queue.currentNovelId to queue.pendingChapters
                }
            })
        }
    }

    private suspend fun <T> safely(what: String, block: suspend () -> Collection<T>): Set<T> = try {
        block().toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        try {
            Log.w(TAG, "Could not read $what", e)
        } catch (_: RuntimeException) {
            // android.util.Log is not available in plain JVM tests
        }
        emptySet()
    }

    override suspend fun translated(novelId: String): Set<Int> =
        safely("translated chapters") { queries.translatedChapterNums(novelId) }

    override suspend fun stale(novelId: String): Set<Int> =
        safely("stale chapters") { queries.staleChapterNums(novelId) }

    override suspend fun queued(novelId: String): Set<Int> {
        val (queueNovel, pending) = queries.readAheadPending()
        val readAhead = if (queueNovel == novelId) pending.toSet() else emptySet()
        val batch = safely("batch-queued chapters") { queries.chaptersInUnfinishedBatches(novelId) }
        return readAhead + batch
    }
}
