package com.lagradost.quicknovel.translator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lagradost.quicknovel.BaseApplication

/**
 * Translator settings (API key, backend, model and feature toggles).
 *
 * The backing [SharedPreferences] is created once (thread-safe, application context) because
 * building a [MasterKey] + [EncryptedSharedPreferences] is expensive. If the encrypted store
 * cannot be created (broken keystore on some devices), we fall back to plain SharedPreferences
 * so the app never crashes.
 *
 * Boolean toggles are mirrored in memory so RecyclerView binds never touch SharedPreferences.
 */
object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val PREFS_FILE = "translator_secure_prefs"
    private const val FALLBACK_PREFS_FILE = "translator_prefs_fallback"

    private const val KEY_API_KEY = "openai_api_key"
    private const val KEY_MODEL = "openai_model"
    private const val KEY_BASE_URL = "openai_base_url"
    private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    private const val KEY_GTRANSLATE_TITLES = "gtranslate_titles"
    private const val KEY_TRANSLATE_SEARCH = "translate_search_queries"
    private const val KEY_AI_SEARCH = "ai_search_queries"
    private const val KEY_TRANSLATE_ON_DEMAND = "translate_on_demand"

    const val MODEL_GPT4O = LlmConfig.MODEL_GPT4O
    const val MODEL_GPT4O_MINI = LlmConfig.MODEL_GPT4O_MINI

    @Volatile
    private var prefs: SharedPreferences? = null

    // In-memory mirrors of the boolean toggles (defaults apply until prefs are loaded).
    @Volatile private var translationEnabled = true
    @Volatile private var gtranslateTitles = false
    @Volatile private var translateSearch = true
    @Volatile private var aiSearch = false
    @Volatile private var translateOnDemand = false

    /** True if the encrypted store failed and plain SharedPreferences are in use. */
    @Volatile
    var usingFallback = false
        private set

    private fun getPrefs(context: Context?): SharedPreferences? {
        prefs?.let { return it }
        val ctx = (context ?: BaseApplication.context)?.applicationContext ?: return null
        return synchronized(this) {
            prefs ?: createPrefs(ctx).also { created ->
                translationEnabled = created.getBoolean(KEY_TRANSLATION_ENABLED, true)
                gtranslateTitles = created.getBoolean(KEY_GTRANSLATE_TITLES, false)
                translateSearch = created.getBoolean(KEY_TRANSLATE_SEARCH, true)
                aiSearch = created.getBoolean(KEY_AI_SEARCH, false)
                translateOnDemand = created.getBoolean(KEY_TRANSLATE_ON_DEMAND, false)
                prefs = created
            }
        }
    }

    private fun createPrefs(appContext: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs", t)
            usingFallback = true
            appContext.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /** Loads the store off the main thread so later reads are instant. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        getPrefs(context)
    }

    private fun ensureLoaded(context: Context?) {
        if (prefs == null) getPrefs(context)
    }

    private fun edit(context: Context?, block: SharedPreferences.Editor.() -> Unit) {
        getPrefs(context)?.edit()?.apply { block() }?.apply()
    }

    // ---- API key / backend ----

    fun getApiKey(context: Context?): String? =
        getPrefs(context)?.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(context: Context, key: String?) = edit(context) { putString(KEY_API_KEY, key) }

    fun hasApiKey(context: Context?): Boolean = getApiKey(context) != null

    fun getModel(context: Context?): String =
        getPrefs(context)?.getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: LlmConfig.DEFAULT_MODEL

    /** Stores any model id (preset or custom free text); blank resets to the default model. */
    fun setModel(context: Context, model: String) = edit(context) {
        if (model.isBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, model.trim())
    }

    /** Model presets for settings UIs: (model id, label). Anything else is a custom model. */
    val modelPresets: List<Pair<String, String>> get() = LlmConfig.MODEL_PRESETS

    /** True if the configured model is not one of [modelPresets] (free-text custom model). */
    fun isCustomModel(context: Context?): Boolean =
        LlmConfig.MODEL_PRESETS.none { it.first == getModel(context) }

    /** OpenAI-compatible base URL, e.g. https://api.openai.com/v1 (normalized). */
    fun getBaseUrl(context: Context?): String =
        LlmConfig.normalizeBaseUrl(getPrefs(context)?.getString(KEY_BASE_URL, null))

    /** Stores the base URL; blank resets to the default. */
    fun setBaseUrl(context: Context, url: String?) = edit(context) {
        if (url.isNullOrBlank()) remove(KEY_BASE_URL)
        else putString(KEY_BASE_URL, LlmConfig.normalizeBaseUrl(url))
    }

    // ---- Toggles (memory-cached) ----

    fun isTranslationEnabled(context: Context?): Boolean {
        ensureLoaded(context); return translationEnabled
    }

    fun setTranslationEnabled(context: Context, enabled: Boolean) {
        translationEnabled = enabled
        edit(context) { putBoolean(KEY_TRANSLATION_ENABLED, enabled) }
    }

    /** Whether to auto-translate titles, chapter names and synopses via Google Translate. */
    fun isGoogleTranslateTitlesEnabled(context: Context?): Boolean {
        ensureLoaded(context); return gtranslateTitles
    }

    fun setGoogleTranslateTitlesEnabled(context: Context, enabled: Boolean) {
        gtranslateTitles = enabled
        edit(context) { putBoolean(KEY_GTRANSLATE_TITLES, enabled) }
    }

    /** Whether search queries are translated into each provider's language (default on). */
    fun isTranslateSearchEnabled(context: Context?): Boolean {
        ensureLoaded(context); return translateSearch
    }

    fun setTranslateSearchEnabled(context: Context, enabled: Boolean) {
        translateSearch = enabled
        edit(context) { putBoolean(KEY_TRANSLATE_SEARCH, enabled) }
    }

    /** Whether the LLM (instead of Google Translate) translates search queries. */
    fun isAiSearchEnabled(context: Context?): Boolean {
        ensureLoaded(context); return aiSearch
    }

    fun setAiSearchEnabled(context: Context, enabled: Boolean) {
        aiSearch = enabled
        edit(context) { putBoolean(KEY_AI_SEARCH, enabled) }
    }

    /** Whether the reader translates untranslated chapters on demand with the LLM. */
    fun isTranslateOnDemandEnabled(context: Context?): Boolean {
        ensureLoaded(context); return translateOnDemand
    }

    fun setTranslateOnDemandEnabled(context: Context, enabled: Boolean) {
        translateOnDemand = enabled
        edit(context) { putBoolean(KEY_TRANSLATE_ON_DEMAND, enabled) }
    }
}
