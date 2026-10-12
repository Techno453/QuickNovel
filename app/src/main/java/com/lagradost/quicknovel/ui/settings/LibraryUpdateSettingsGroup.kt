package com.lagradost.quicknovel.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.updates.LibraryCategory
import com.lagradost.quicknovel.updates.LibraryUpdateChecker
import com.lagradost.quicknovel.updates.LibraryUpdateScheduler
import com.lagradost.quicknovel.updates.LibraryUpdateSettings
import com.lagradost.quicknovel.updates.UpdateFrequency
import com.mihon.common.preference.AndroidPreferenceStore
import com.mihon.presentation.settings.Preference
import kotlinx.collections.immutable.persistentListOf

/** The "Library updates" settings group: periodic new-chapter checks. */
object LibraryUpdateSettingsGroup {
    @Composable
    fun preferenceGroup(): Preference.PreferenceGroup {
        val context = LocalContext.current.applicationContext
        val store = remember(context) { AndroidPreferenceStore(context) }
        val frequency = remember(store) {
            store.getString(LibraryUpdateSettings.KEY_FREQUENCY, UpdateFrequency.DEFAULT.key)
        }
        val unmetered = remember(store) {
            store.getBoolean(
                LibraryUpdateSettings.KEY_UNMETERED_ONLY,
                LibraryUpdateSettings.DEFAULT_UNMETERED_ONLY
            )
        }
        val categories = remember(store) {
            store.getStringSet(LibraryUpdateSettings.KEY_CATEGORIES, LibraryCategory.DEFAULT_KEYS)
        }

        // Android 13+: ask for the notification permission when the checks get turned on
        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    showToast(R.string.library_update_permission_denied, Toast.LENGTH_LONG)
                }
            }
        val requestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (t: Throwable) {
                    logError(t)
                }
            }
        }

        val categoryEntries = linkedMapOf(
            LibraryCategory.Reading.key to stringResource(R.string.type_reading),
            LibraryCategory.PlanToRead.key to stringResource(R.string.type_plan_to_read),
            LibraryCategory.OnHold.key to stringResource(R.string.type_on_hold),
            LibraryCategory.Completed.key to stringResource(R.string.type_completed),
            LibraryCategory.Dropped.key to stringResource(R.string.type_dropped),
            LibraryCategory.Downloads.key to stringResource(R.string.library_update_downloads),
        )
        val noneSelected = stringResource(R.string.library_update_categories_none)

        return Preference.PreferenceGroup(
            title = stringResource(R.string.library_updates_settings),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    icon = painterResource(R.drawable.ic_baseline_autorenew_24),
                    title = stringResource(R.string.library_update_frequency),
                    preference = frequency,
                    entries = linkedMapOf(
                        UpdateFrequency.Off.key to stringResource(R.string.library_update_frequency_off),
                        UpdateFrequency.Every6Hours.key to stringResource(R.string.library_update_frequency_6h),
                        UpdateFrequency.Every12Hours.key to stringResource(R.string.library_update_frequency_12h),
                        UpdateFrequency.Daily.key to stringResource(R.string.library_update_frequency_daily),
                    ),
                    onValueChanged = { value ->
                        val newFrequency = UpdateFrequency.fromKey(value)
                        LibraryUpdateScheduler.schedule(context, newFrequency, unmetered.get())
                        if (newFrequency != UpdateFrequency.Off) requestNotifications()
                        true
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    icon = painterResource(R.drawable.ic_baseline_cloud_24),
                    title = stringResource(R.string.library_update_unmetered),
                    subtitle = stringResource(R.string.library_update_unmetered_desc),
                    preference = unmetered,
                    onValueChanged = { value ->
                        LibraryUpdateScheduler.schedule(
                            context, UpdateFrequency.fromKey(frequency.get()), value
                        )
                        true
                    },
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    icon = painterResource(R.drawable.ic_baseline_collections_bookmark_24),
                    title = stringResource(R.string.library_update_categories),
                    preference = categories,
                    entries = categoryEntries,
                    subtitleProvider = { selected, entries ->
                        entries.filterKeys { it in selected }.values.joinToString()
                            .ifEmpty { noneSelected }
                    },
                ),
                Preference.PreferenceItem.TextPreference(
                    icon = painterResource(R.drawable.ic_baseline_system_update_24),
                    title = stringResource(R.string.library_update_check_now),
                    subtitle = stringResource(R.string.library_update_check_now_desc),
                    onClick = {
                        if (LibraryUpdateChecker.isRunning) {
                            showToast(R.string.library_update_already_running, Toast.LENGTH_SHORT)
                        } else {
                            requestNotifications()
                            showToast(R.string.library_update_started, Toast.LENGTH_SHORT)
                            LibraryUpdateScheduler.runNow(context)
                        }
                    },
                ),
            )
        )
    }
}
