package com.lagradost.quicknovel.ui.result

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import com.lagradost.quicknovel.ui.translate.ReadAheadState
import kotlinx.coroutines.CancellationException

/** Translation status of one chapter, shown as a small chip in the result page's chapter list. */
enum class ChapterTranslationStatus {
    NONE,

    /** A stored translation exists. */
    TRANSLATED,

    /** A stored translation exists but the source chapter changed since. */
    STALE,

    /** Waiting to be translated (read-ahead or batch) or being translated now. */
    QUEUED,
}

/**
 * Where the chapter list gets the statuses from. Kept small so the translator's accessors for
 * stale and batch-queued chapters can be plugged in without touching the adapter.
 */
interface ChapterStatusSource {
    /** Chapter indices with a stored translation. */
    suspend fun translated(ctx: Context, novelId: String): Set<Int>

    /** Chapter indices whose stored translation is out of date ("source changed"). */
    suspend fun stale(ctx: Context, novelId: String): Set<Int>

    /** Chapter indices queued or in progress. */
    fun queued(novelId: String): Set<Int>

    companion object {
        /** Status of each chapter; queued wins over stale, stale over translated. */
        suspend fun statuses(source: ChapterStatusSource, ctx: Context, novelId: String): Map<Int, ChapterTranslationStatus> =
            merge(source.translated(ctx, novelId), source.stale(ctx, novelId), source.queued(novelId))

        fun merge(translated: Set<Int>, stale: Set<Int>, queued: Set<Int>): Map<Int, ChapterTranslationStatus> {
            val map = HashMap<Int, ChapterTranslationStatus>()
            for (i in translated) map[i] = ChapterTranslationStatus.TRANSLATED
            for (i in stale) map[i] = ChapterTranslationStatus.STALE
            for (i in queued) map[i] = ChapterTranslationStatus.QUEUED
            return map
        }
    }
}

/** Reads the translator database and the read-ahead queue. */
object TranslatorChapterStatusSource : ChapterStatusSource {
    private const val TAG = "ChapterStatus"

    override suspend fun translated(ctx: Context, novelId: String): Set<Int> = try {
        TranslatorDatabase.getInstance(ctx).translationDao().getTranslatedChapterNums(novelId).toSet()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read translated chapters", e)
        emptySet()
    }

    // TODO(translator): use the stale-chapter accessor of ChapterTranslationRepository once it
    //  exists (stored translation whose source hash no longer matches). Until then nothing is
    //  reported as stale.
    override suspend fun stale(ctx: Context, novelId: String): Set<Int> = emptySet()

    // TODO(translator): add the batch job's queued / in-progress chapters once the repository
    //  exposes them. For now only the read-ahead queue's pending chapters are known.
    override fun queued(novelId: String): Set<Int> {
        val queue = ReadAheadState.queue
        return if (queue.currentNovelId == novelId) queue.pendingChapters.toSet() else emptySet()
    }
}
