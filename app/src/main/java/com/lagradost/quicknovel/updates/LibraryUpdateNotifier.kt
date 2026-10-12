package com.lagradost.quicknovel.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError

/**
 * "New chapters" notifications: one notification per novel (tap opens its result page), grouped
 * under a summary "New chapters in N novels" that lists "name +N" lines (tap opens the novel when
 * there is only one, the library otherwise). Own low-importance channel, so no sound by default.
 */
object LibraryUpdateNotifier {
    const val CHANNEL_ID = "quicknovel.library_updates"
    private const val GROUP_KEY = "quicknovel.library_updates.group"
    private const val TAG_NOVEL = "library_update_novel"
    private const val TAG_SUMMARY = "library_update_summary"
    private const val SUMMARY_ID = 0

    const val ACTION_OPEN_RESULT = "com.lagradost.quicknovel.action.OPEN_LIBRARY_RESULT"
    const val ACTION_OPEN_LIBRARY = "com.lagradost.quicknovel.action.OPEN_LIBRARY"
    const val EXTRA_URL = "com.lagradost.quicknovel.extra.RESULT_URL"
    const val EXTRA_API_NAME = "com.lagradost.quicknovel.extra.RESULT_API_NAME"
    const val EXTRA_NOVEL_ID = "com.lagradost.quicknovel.extra.NOVEL_ID"

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.library_update_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.library_update_channel_desc)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private val immutableFlag =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun mainIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    private fun openResultIntent(context: Context, novel: FoundNovel): PendingIntent {
        val intent = mainIntent(context).apply {
            action = ACTION_OPEN_RESULT
            putExtra(EXTRA_URL, novel.url)
            putExtra(EXTRA_API_NAME, novel.apiName)
            putExtra(EXTRA_NOVEL_ID, novel.id)
        }
        return PendingIntent.getActivity(
            context, novel.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )
    }

    private fun openLibraryIntent(context: Context): PendingIntent {
        val intent = mainIntent(context).apply { action = ACTION_OPEN_LIBRARY }
        return PendingIntent.getActivity(
            context, 0x4C1B, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag
        )
    }

    fun notifyNewChapters(context: Context, found: List<FoundNovel>) {
        val plan = UpdateNotificationPlan.from(found)
        if (plan.novels.isEmpty() || !canNotify(context)) return
        try {
            ensureChannel(context)
            val manager = NotificationManagerCompat.from(context)
            val resources = context.resources

            for (novel in plan.novels) {
                val count = UpdateNotificationPlan.count(novel)
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_menu_book_24)
                    .setContentTitle(novel.name)
                    .setContentText(
                        resources.getQuantityString(R.plurals.library_update_novel_text, count, count)
                    )
                    .setSubText(novel.apiName)
                    .setContentIntent(openResultIntent(context, novel))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setGroup(GROUP_KEY)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                    .build()
                @Suppress("MissingPermission") // checked in canNotify
                manager.notify(TAG_NOVEL, novel.id, notification)
            }

            val title = resources.getQuantityString(
                R.plurals.library_update_title, plan.total, plan.total
            )
            val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
            plan.lines.forEach { style.addLine(it) }
            if (plan.moreCount > 0) {
                style.setSummaryText(
                    resources.getQuantityString(
                        R.plurals.library_update_more, plan.moreCount, plan.moreCount
                    )
                )
            }
            val single = plan.novels.singleOrNull()
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_menu_book_24)
                .setContentTitle(title)
                .setContentText(plan.lines.joinToString(", "))
                .setStyle(style)
                .setContentIntent(
                    if (single != null) openResultIntent(context, single) else openLibraryIntent(context)
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setGroup(GROUP_KEY)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .build()
            @Suppress("MissingPermission")
            manager.notify(TAG_SUMMARY, SUMMARY_ID, summary)
        } catch (t: Throwable) {
            // SecurityException if the permission was revoked in between
            logError(t)
        }
    }

    /** Removes a novel's notification once it has been opened. */
    fun cancelNovel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(TAG_NOVEL, id)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
