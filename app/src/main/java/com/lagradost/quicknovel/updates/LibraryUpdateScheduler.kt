package com.lagradost.quicknovel.updates

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import com.lagradost.quicknovel.DownloadFileWorkManager
import com.lagradost.quicknovel.mvvm.logError
import java.util.concurrent.TimeUnit

/** The user settings of the update checker, stored in the default shared preferences. */
object LibraryUpdateSettings {
    /** "0" (off), "6", "12" or "24" hours, see [UpdateFrequency]. Default "24". */
    const val KEY_FREQUENCY = "library_update_frequency"

    /** Only run the periodic check on an unmetered network. Default true. */
    const val KEY_UNMETERED_ONLY = "library_update_unmetered_only"

    /** Which library parts are checked, see [LibraryCategory]. Default reading, plan_to_read, downloads. */
    const val KEY_CATEGORIES = "library_update_categories"

    const val DEFAULT_UNMETERED_ONLY = true

    private fun prefs(context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun frequency(context: Context): UpdateFrequency =
        UpdateFrequency.fromKey(prefs(context).getString(KEY_FREQUENCY, UpdateFrequency.DEFAULT.key))

    fun unmeteredOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNMETERED_ONLY, DEFAULT_UNMETERED_ONLY)

    fun categories(context: Context): Set<LibraryCategory> =
        LibraryCategory.fromKeys(prefs(context).getStringSet(KEY_CATEGORIES, LibraryCategory.DEFAULT_KEYS))
}

object LibraryUpdateScheduler {
    const val PERIODIC_WORK = "library_update_periodic"
    const val MANUAL_WORK = "library_update_manual"

    /** Flex window: the periodic run may start anywhere in the last hour of its period. */
    private const val FLEX_MINUTES = 60L

    /**
     * (Re)applies the periodic schedule from the settings. Safe to call on every app start:
     * an unchanged schedule keeps its timing ([ExistingPeriodicWorkPolicy.UPDATE]).
     */
    fun schedule(
        context: Context,
        frequency: UpdateFrequency = LibraryUpdateSettings.frequency(context),
        unmeteredOnly: Boolean = LibraryUpdateSettings.unmeteredOnly(context),
    ) {
        try {
            val workManager = DownloadFileWorkManager.getWorkerManager(context)
            if (frequency == UpdateFrequency.Off) {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(
                frequency.hours, TimeUnit.HOURS,
                FLEX_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(Data.Builder().putBoolean(LibraryUpdateWorker.KEY_MANUAL, false).build())
                .build()
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** "Check for new chapters now": any network, does not wait for the periodic window. */
    fun runNow(context: Context) {
        try {
            val request = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInputData(Data.Builder().putBoolean(LibraryUpdateWorker.KEY_MANUAL, true).build())
                .build()
            DownloadFileWorkManager.getWorkerManager(context)
                .enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
