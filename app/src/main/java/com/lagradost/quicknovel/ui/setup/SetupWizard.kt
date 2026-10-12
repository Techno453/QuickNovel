package com.lagradost.quicknovel.ui.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.translator.SecurePrefs
import com.lagradost.quicknovel.util.Apis.Companion.getApiProviderLangSettings

/**
 * First-run translation setup: when to show it and the settings it writes outside [SecurePrefs].
 *
 * First run = [SecurePrefs.KEY_SETUP_COMPLETED] unset and no API key stored for any provider, so
 * existing installs that already have a key never see it after an update.
 */
object SetupWizard {
    /** MainActivity opens the Settings tab for this action (the wizard's "All settings" link). */
    const val ACTION_OPEN_SETTINGS = "com.lagradost.quicknovel.action.OPEN_SETTINGS"

    /** Languages picked on the welcome page (default preferences, string set of zh / ja / ko). */
    const val KEY_READING_LANGUAGES = "setup_reading_languages"

    @Volatile
    private var launchedThisProcess = false

    /**
     * Opens the wizard over [activity] on first run. Call off the main thread: it loads the
     * encrypted preferences.
     */
    fun launchIfNeeded(activity: Activity) {
        if (launchedThisProcess) return
        try {
            val show = SetupChoices.shouldShowOnLaunch(
                setupCompleted = SecurePrefs.isSetupCompleted(activity),
                hasAnyApiKey = SecurePrefs.hasAnyApiKey(activity),
            )
            if (!show) return
            launchedThisProcess = true
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.startActivity(SetupActivity.intent(activity, firstRun = true))
                }
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun getReadingLanguages(context: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(KEY_READING_LANGUAGES, null)?.toSet()
            ?: SetupChoices.READING_LANGUAGES.toSet()

    /** Stores the languages and shows only those CJK sources in search and on the home page. */
    fun setReadingLanguages(context: Context, languages: Set<String>) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val current = context.getApiProviderLangSettings()
            val updated = SetupChoices.providerLanguages(current, languages)
            prefs.edit {
                putStringSet(KEY_READING_LANGUAGES, languages.toMutableSet())
                putStringSet(context.getString(R.string.provider_lang_key), updated.toMutableSet())
            }
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /** Opens MainActivity on the Settings tab (closing the wizard above it). */
    fun openSettings(activity: Activity) {
        activity.startActivity(
            Intent(activity, com.lagradost.quicknovel.MainActivity::class.java)
                .setAction(ACTION_OPEN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
