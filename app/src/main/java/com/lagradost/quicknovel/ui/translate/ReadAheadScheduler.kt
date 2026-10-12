package com.lagradost.quicknovel.ui.translate

import android.content.Context
import android.util.Log
import com.lagradost.quicknovel.translator.ReadAheadQueue
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.translator.TranslationLanguages
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The process-wide read-ahead queue, worked through by [TranslationForegroundService]. */
object ReadAheadState {
    val queue = ReadAheadQueue()
}

/**
 * "Auto-translate ahead": when the reader opens chapter i of a novel that is being read in
 * translation, queue chapters i+1 … i+N (N = [SecurePrefs.getReadAheadChapters]) that are not
 * translated yet and start the service's low-priority read-ahead worker.
 *
 * "Being read in translation" = a translatable (zh/ja/ko) source, an API key, and either
 * "Translate chapters on demand" is on or chapter i already has a stored translation.
 */
object ReadAheadScheduler {
    private const val TAG = "ReadAhead"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingUpdate: Job? = null

    /** Call when the reader's current chapter changes. Cheap; the work happens off the main thread. */
    fun onChapterOpened(ctx: Context, novelId: String, current: Int) {
        val appCtx = ctx.applicationContext
        pendingUpdate?.cancel() // only the latest position matters
        pendingUpdate = scope.launch {
            try {
                update(appCtx, novelId, current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule read-ahead for $novelId", e)
            }
        }
    }

    private suspend fun update(ctx: Context, novelId: String, current: Int) {
        val ahead = SecurePrefs.getReadAheadChapters(ctx)
        val queue = ReadAheadState.queue
        if (ahead <= 0) {
            if (queue.hasPending) TranslationForegroundService.stopReadAhead(ctx)
            return
        }
        val apiName = NovelChapterSource.apiNameOf(novelId) ?: return
        if (TranslationLanguages.langOfApi(apiName) == null) return
        if (!SecurePrefs.hasApiKey(ctx)) return

        val translated = TranslatorDatabase.getInstance(ctx).translationDao().getTranslatedChapterNums(novelId).toHashSet()
        if (!SecurePrefs.isTranslateOnDemandEnabled(ctx) && current !in translated) return

        val entry = NovelChapterSource.resolve(novelId) ?: return
        // A claim without a running service is stale (e.g. the service was killed): take it over.
        if (queue.isWorkerClaimed && !TranslationForegroundService.isAlive) queue.releaseWorker()
        if (queue.update(novelId, current, ahead, entry.chapters.size, translated)) {
            Log.i(TAG, "Read-ahead for $novelId after chapter $current: ${queue.pendingChapters}")
            TranslationForegroundService.startReadAhead(ctx)
        }
    }
}
