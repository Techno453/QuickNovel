package com.lagradost.quicknovel.ui.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.translator.SpendLedger
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.batch.BatchChapterStatus
import com.lagradost.quicknovel.translator.batch.BatchStatus
import com.lagradost.quicknovel.translator.db.BatchJob
import com.lagradost.quicknovel.translator.db.TranslatorDatabase
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Polls the OpenAI batches, stores finished results and notifies; stops itself when none are left. */
class BatchPollWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // Workers can run before anything else in a fresh process.
        SpendLedger.install(ctx)
        return try {
            val report = BatchManager.pollAll(ctx)
            report.finished.forEach { BatchNotifications.finished(ctx, it) }
            if (report.stillActive == 0) BatchScheduler.stopPeriodic(ctx)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BatchPollWorker", "Poll failed", e)
            Result.retry()
        }
    }
}

/** WorkManager scheduling of [BatchPollWorker]. */
object BatchScheduler {
    private const val PERIODIC = "translator_batch_poll"
    private const val ONCE = "translator_batch_poll_now"
    private const val INTERVAL_MINUTES = 15L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Periodic polling (every ~15 min, network required) while a batch is unfinished. */
    fun ensurePolling(ctx: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<BatchPollWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(network)
                .setInitialDelay(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w("BatchScheduler", "Could not schedule batch polling", e)
        }
    }

    fun stopPeriodic(ctx: Context) {
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        } catch (e: Exception) {
            Log.w("BatchScheduler", "Could not cancel batch polling", e)
        }
    }

    /**
     * One poll as soon as there is a network (app start, opening the translate dialog or the spend
     * screen). Cheap when there is no batch: the worker reads the DB and stops.
     */
    fun pollNow(ctx: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<BatchPollWorker>().setConstraints(network).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w("BatchScheduler", "Could not poll batches", e)
        }
    }
}

object BatchNotifications {
    private const val CHANNEL_ID = "translation_progress"
    private const val BASE_ID = 9100

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Translation Progress", NotificationManager.IMPORTANCE_LOW))
        }
        return nm
    }

    private fun contentIntent(ctx: Context): PendingIntent {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(ctx, MainActivity::class.java)
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(ctx, 0, launch, flags)
    }

    private fun post(ctx: Context, id: Int, title: String, text: String) {
        try {
            channel(ctx).notify(
                id,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.netflix_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(contentIntent(ctx))
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w("BatchNotifications", "Could not notify", e)
        }
    }

    fun submitted(ctx: Context, job: BatchJob) = post(
        ctx, BASE_ID + (job.id % 1000).toInt(),
        "Overnight translation queued: ${job.novelName}",
        "Chapters ${job.fromChapter + 1}–${job.toChapter + 1}, ${job.requestCount} requests, about " +
            "${SpendPricing.format(job.estimatedUsd)} at batch price. Ready within 24 hours.",
    )

    suspend fun finished(ctx: Context, job: BatchJob) {
        val counts = BatchManager.chapterCounts(ctx, job.id)
        val stored = counts[BatchChapterStatus.STORED] ?: 0
        val needsLive = counts[BatchChapterStatus.NEEDS_LIVE] ?: 0
        val status = BatchStatus.fromName(job.status)
        val title = when (status) {
            BatchStatus.DONE -> "Overnight translation ready: ${job.novelName}"
            BatchStatus.FAILED -> "Overnight translation failed: ${job.novelName}"
            else -> "Overnight translation ${status.label.lowercase()}: ${job.novelName}"
        }
        val text = buildString {
            if (status == BatchStatus.FAILED) {
                append(job.error ?: "OpenAI rejected the batch.")
            } else {
                append("$stored chapter(s) translated")
                if (needsLive > 0) append(", $needsLive left for normal translation")
                job.actualUsd?.let { append(". Cost ${SpendPricing.format(it)}") }
                append('.')
            }
        }
        post(ctx, BASE_ID + (job.id % 1000).toInt(), title, text)
    }

    fun failedToSubmit(ctx: Context, novelName: String, message: String) =
        post(ctx, BASE_ID + 999, "Overnight translation not started: $novelName", message)
}

/** Unfinished batch count, for screens that want to poll on open. */
suspend fun hasUnfinishedBatches(ctx: Context): Boolean =
    TranslatorDatabase.getInstance(ctx).batchDao().jobsWithStatus(BatchStatus.ACTIVE_NAMES).isNotEmpty()
