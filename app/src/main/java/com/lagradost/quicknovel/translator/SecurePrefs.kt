package com.lagradost.quicknovel.translator

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val PREFS_FILE = "translator_secure_prefs"
    private const val KEY_API_KEY = "openai_api_key"
    private const val KEY_MODEL = "openai_model"
    private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    private const val KEY_GTRANSLATE_TITLES = "gtranslate_titles"

    const val MODEL_GPT4O = "gpt-4o"
    const val MODEL_GPT4O_MINI = "gpt-4o-mini"

    private fun getPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(context: Context): String? {
        return getPrefs(context).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, key: String?) {
        getPrefs(context).edit().putString(KEY_API_KEY, key).apply()
    }

    fun getModel(context: Context): String {
        return getPrefs(context).getString(KEY_MODEL, MODEL_GPT4O_MINI) ?: MODEL_GPT4O_MINI
    }

    fun setModel(context: Context, model: String) {
        getPrefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun isTranslationEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TRANSLATION_ENABLED, true)
    }

    fun setTranslationEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TRANSLATION_ENABLED, enabled).apply()
    }

    /** Whether to auto-translate titles, chapter names and synopses via Google Translate. */
    fun isGoogleTranslateTitlesEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GTRANSLATE_TITLES, false)
    }

    fun setGoogleTranslateTitlesEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GTRANSLATE_TITLES, enabled).apply()
    }
}
