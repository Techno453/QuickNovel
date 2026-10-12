package com.lagradost.quicknovel.updates

import android.content.Context
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.CancellationException

/** Runs [LibraryUpdateChecker]; scheduled by [LibraryUpdateScheduler]. */
class LibraryUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val KEY_MANUAL = "manual"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && LibraryUpdateSettings.frequency(context) == UpdateFrequency.Off) {
            return Result.success()
        }
        try {
            val result = LibraryUpdateChecker.run(context, manual)
            if (manual) {
                when {
                    result == null -> showToast(R.string.library_update_already_running, Toast.LENGTH_SHORT)
                    result.found.isEmpty() -> showToast(R.string.library_update_none_found, Toast.LENGTH_SHORT)
                    !LibraryUpdateNotifier.canNotify(context) ->
                        showToast(R.string.library_update_permission_denied, Toast.LENGTH_LONG)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Per-novel failures never get here; this is a bug, do not retry in a loop
            logError(t)
        }
        return Result.success()
    }
}
