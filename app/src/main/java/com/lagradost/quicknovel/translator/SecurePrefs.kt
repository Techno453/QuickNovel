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

    /** Pre-preset single API key; migrated to [KEY_PROVIDER_KEY_PREFIX] + provider id. */
    private const val KEY_LEGACY_API_KEY = "openai_api_key"
    /** Per-provider API keys: api_key_openai, api_key_google, ... */
    private const val KEY_PROVIDER_KEY_PREFIX = "api_key_"
    /** Custom model id (also the model of installs from before presets). */
    private const val KEY_MODEL = "openai_model"
    /** Custom base URL. */
    private const val KEY_BASE_URL = "openai_base_url"
    /** Custom extra request-body JSON. */
    private const val KEY_EXTRA_BODY = "custom_extra_body"
    /** Custom model's prompt profile id ([PromptProfiles]). */
    private const val KEY_CUSTOM_PROMPT_PROFILE = "custom_prompt_profile"
    /** Selected preset id, or [LlmConfig.CUSTOM_PRESET_ID]. */
    const val KEY_PRESET = "model_preset"
    /** Fallback preset id; absent = off. */
    const val KEY_FALLBACK_PRESET = "fallback_preset"
    private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    private const val KEY_GTRANSLATE_TITLES = "gtranslate_titles"
    private const val KEY_TRANSLATE_SEARCH = "translate_search_queries"
    private const val KEY_AI_SEARCH = "ai_search_queries"
    private const val KEY_TRANSLATE_ON_DEMAND = "translate_on_demand"

    // Story Bible / consistency settings (UI to be wired into the settings screen).
    const val KEY_AUTO_ADD_TERMS = "auto_add_discovered_terms"
    const val KEY_STRICT_MODE = "strict_consistency_mode"
    const val KEY_CARRY_STORY_CONTEXT = "carry_story_context"
    const val KEY_ANALYZE_CHAPTER_COUNT = "analyze_chapter_count"
    /** Suffixed with the source language: default_name_style_zh / _ja / _ko. */
    const val KEY_DEFAULT_NAME_STYLE_PREFIX = "default_name_style_"
    /** Suffixed with the source language: default_honorific_style_zh / _ja / _ko. */
    const val KEY_DEFAULT_HONORIFIC_STYLE_PREFIX = "default_honorific_style_"

    const val DEFAULT_ANALYZE_CHAPTERS = 5
    const val MAX_ANALYZE_CHAPTERS = 20

    const val MODEL_GPT4O = LlmConfig.MODEL_GPT4O
    const val MODEL_GPT4O_MINI = LlmConfig.MODEL_GPT4O_MINI
    const val CUSTOM_PRESET_ID = LlmConfig.CUSTOM_PRESET_ID

    @Volatile
    private var prefs: SharedPreferences? = null

    // In-memory mirrors of the boolean toggles (defaults apply until prefs are loaded).
    @Volatile private var translationEnabled = true
    @Volatile private var gtranslateTitles = false
    @Volatile private var translateSearch = true
    @Volatile private var aiSearch = false
    @Volatile private var translateOnDemand = false
    @Volatile private var autoAddTerms = true
    @Volatile private var strictMode = true
    @Volatile private var carryStoryContext = true

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
                autoAddTerms = created.getBoolean(KEY_AUTO_ADD_TERMS, true)
                strictMode = created.getBoolean(KEY_STRICT_MODE, true)
                carryStoryContext = created.getBoolean(KEY_CARRY_STORY_CONTEXT, true)
                migrateToPresets(created)
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

    /**
     * One-time move from the single model / base URL / key settings to presets and per-provider
     * keys (see [LlmConfig.migrateLegacySelection]); the old key moves to the slot of the provider
     * it was used with.
     */
    private fun migrateToPresets(p: SharedPreferences) {
        if (p.contains(KEY_PRESET)) return
        val storedModel = p.getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() }
        val storedUrl = p.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        val legacyKey = p.getString(KEY_LEGACY_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
        val selection = LlmConfig.migrateLegacySelection(storedModel, storedUrl, legacyKey != null)
        val editor = p.edit()
        editor.putString(KEY_PRESET, selection.presetId)
        selection.customModel?.let { editor.putString(KEY_MODEL, it) }
        if (legacyKey != null) {
            val slot = KEY_PROVIDER_KEY_PREFIX + selection.keyProviderId
            if (!p.contains(slot)) editor.putString(slot, legacyKey)
            editor.remove(KEY_LEGACY_API_KEY)
        }
        editor.apply()
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

    // ---- Model presets / backend ----

    /** Model presets for settings UIs. Anything else is a custom model. */
    val modelPresets: List<ModelPreset> get() = LlmConfig.PRESETS

    /** Selected preset id, or [CUSTOM_PRESET_ID]. */
    fun getPresetId(context: Context?): String {
        val id = getPrefs(context)?.getString(KEY_PRESET, null)
        return if (id == CUSTOM_PRESET_ID || ModelPresets.byId(id) != null) id!! else LlmConfig.DEFAULT_PRESET_ID
    }

    /** The selected preset; null when a custom model is configured. */
    fun getPreset(context: Context?): ModelPreset? = ModelPresets.byId(getPresetId(context))

    /** Selects a preset (base URL, model, extra body and temperature behaviour come from it). */
    fun selectPreset(context: Context, id: String) = edit(context) {
        putString(KEY_PRESET, if (ModelPresets.byId(id) != null) id else CUSTOM_PRESET_ID)
    }

    fun isCustomModel(context: Context?): Boolean = getPresetId(context) == CUSTOM_PRESET_ID

    /** Custom model: free-text base URL, model id and extra request-body JSON (validated by the UI). */
    fun setCustomModel(context: Context, baseUrl: String?, model: String, extraBodyJson: String?) = edit(context) {
        putString(KEY_PRESET, CUSTOM_PRESET_ID)
        if (baseUrl.isNullOrBlank()) remove(KEY_BASE_URL) else putString(KEY_BASE_URL, LlmConfig.normalizeBaseUrl(baseUrl))
        if (model.isBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, model.trim())
        if (extraBodyJson.isNullOrBlank()) remove(KEY_EXTRA_BODY) else putString(KEY_EXTRA_BODY, extraBodyJson.trim())
    }

    fun getCustomModel(context: Context?): String =
        getPrefs(context)?.getString(KEY_MODEL, null)?.trim()?.takeIf { it.isNotEmpty() } ?: LlmConfig.DEFAULT_MODEL

    fun getCustomBaseUrl(context: Context?): String =
        LlmConfig.normalizeBaseUrl(getPrefs(context)?.getString(KEY_BASE_URL, null))

    fun getCustomExtraBodyJson(context: Context?): String =
        getPrefs(context)?.getString(KEY_EXTRA_BODY, null).orEmpty()

    /** Prompt profile of the custom model ([LlmConfig.CUSTOM_PROMPT_PROFILE] until the user picks one). */
    fun getCustomPromptProfile(context: Context?): String =
        getPrefs(context)?.getString(KEY_CUSTOM_PROMPT_PROFILE, null)?.takeIf { PromptProfiles.exists(it) }
            ?: LlmConfig.CUSTOM_PROMPT_PROFILE

    fun setCustomPromptProfile(context: Context, id: String) = edit(context) {
        if (PromptProfiles.exists(id)) putString(KEY_CUSTOM_PROMPT_PROFILE, id) else remove(KEY_CUSTOM_PROMPT_PROFILE)
    }

    /** Active model config (without the key). */
    private fun activeConfig(context: Context?): EngineConfig {
        getPreset(context)?.let { return it.toEngineConfig() }
        val baseUrl = getCustomBaseUrl(context)
        val model = getCustomModel(context)
        return EngineConfig(
            baseUrl = baseUrl,
            model = model,
            extraBody = LlmConfig.parseExtraBody(getCustomExtraBodyJson(context)),
            sendTemperature = true, // dropped automatically if the model rejects it, or via "temperature": null
            providerId = LlmProviders.forBaseUrl(baseUrl).id,
            promptProfile = getCustomPromptProfile(context),
        )
    }

    /** Everything the engine needs for the selected model, including its provider's API key. */
    fun engineConfig(context: Context?): EngineConfig {
        val config = activeConfig(context)
        return config.copy(apiKey = getApiKey(context, config.providerId).orEmpty())
    }

    /** Provider of the selected model (API keys are stored per provider). */
    fun getActiveProvider(context: Context?): LlmProvider = LlmProviders.byId(activeConfig(context).providerId)

    /** Model id of the selected model. */
    fun getModel(context: Context?): String = activeConfig(context).model

    /** Base URL of the selected model (normalized). */
    fun getBaseUrl(context: Context?): String = LlmConfig.normalizeBaseUrl(activeConfig(context).baseUrl)

    // ---- Fallback model ----

    /** Fallback preset id, or null when the fallback is off (default). */
    fun getFallbackPresetId(context: Context?): String? =
        getPrefs(context)?.getString(KEY_FALLBACK_PRESET, null)?.takeIf { ModelPresets.byId(it) != null }

    fun setFallbackPresetId(context: Context, id: String?) = edit(context) {
        if (id == null || ModelPresets.byId(id) == null) remove(KEY_FALLBACK_PRESET) else putString(KEY_FALLBACK_PRESET, id)
    }

    /**
     * Engine config for the fallback model, or null when it is off, is the same model as the
     * primary, or its provider has no API key.
     */
    fun fallbackEngineConfig(context: Context?): EngineConfig? {
        val preset = ModelPresets.byId(getFallbackPresetId(context)) ?: return null
        val key = getApiKey(context, preset.provider.id) ?: return null
        return preset.toEngineConfig(key).takeUnless { it.sameModelAs(activeConfig(context)) }
    }

    // ---- API keys (per provider) ----

    fun getApiKey(context: Context?, providerId: String): String? =
        getPrefs(context)?.getString(KEY_PROVIDER_KEY_PREFIX + providerId, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** API key for the selected model's provider. */
    fun getApiKey(context: Context?): String? = getApiKey(context, activeConfig(context).providerId)

    fun setApiKey(context: Context, providerId: String, key: String?) = edit(context) {
        if (key.isNullOrBlank()) remove(KEY_PROVIDER_KEY_PREFIX + providerId)
        else putString(KEY_PROVIDER_KEY_PREFIX + providerId, key.trim())
    }

    /** Sets the API key for the selected model's provider. */
    fun setApiKey(context: Context, key: String?) = setApiKey(context, activeConfig(context).providerId, key)

    fun hasApiKey(context: Context?): Boolean = getApiKey(context) != null

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

    // ---- Story Bible / consistency ----

    /** Insert confidently discovered names into the bible immediately (default on). */
    fun isAutoAddTermsEnabled(context: Context?): Boolean {
        ensureLoaded(context); return autoAddTerms
    }

    fun setAutoAddTermsEnabled(context: Context, enabled: Boolean) {
        autoAddTerms = enabled
        edit(context) { putBoolean(KEY_AUTO_ADD_TERMS, enabled) }
    }

    /** Re-request a chunk once when glossary / untranslated-text / paragraph checks fail (default on). */
    fun isStrictModeEnabled(context: Context?): Boolean {
        ensureLoaded(context); return strictMode
    }

    fun setStrictModeEnabled(context: Context, enabled: Boolean) {
        strictMode = enabled
        edit(context) { putBoolean(KEY_STRICT_MODE, enabled) }
    }

    /** Carry story context (premise, previous chapter summary, preceding English) between chapters (default on). */
    fun isCarryStoryContextEnabled(context: Context?): Boolean {
        ensureLoaded(context); return carryStoryContext
    }

    fun setCarryStoryContextEnabled(context: Context, enabled: Boolean) {
        carryStoryContext = enabled
        edit(context) { putBoolean(KEY_CARRY_STORY_CONTEXT, enabled) }
    }

    /** Engine options from the current settings. */
    fun translationOptions(context: Context?): TranslationOptions = TranslationOptions(
        strictMode = isStrictModeEnabled(context),
        autoAddTerms = isAutoAddTermsEnabled(context),
        carryStoryContext = isCarryStoryContextEnabled(context),
    )

    /** Default chapter count for "Analyze novel" (1–20, default 5). */
    fun getAnalyzeChapterCount(context: Context?): Int =
        (getPrefs(context)?.getInt(KEY_ANALYZE_CHAPTER_COUNT, DEFAULT_ANALYZE_CHAPTERS) ?: DEFAULT_ANALYZE_CHAPTERS)
            .coerceIn(1, MAX_ANALYZE_CHAPTERS)

    fun setAnalyzeChapterCount(context: Context, count: Int) =
        edit(context) { putInt(KEY_ANALYZE_CHAPTER_COUNT, count.coerceIn(1, MAX_ANALYZE_CHAPTERS)) }

    /** Global default name style for [lang] (see [TranslationStyle.nameStyleOptions]). */
    fun getDefaultNameStyle(context: Context?, lang: String?): String {
        val code = TranslationLanguages.normalize(lang) ?: TranslationLanguages.DEFAULT
        val stored = getPrefs(context)?.getString(KEY_DEFAULT_NAME_STYLE_PREFIX + code, null)
        return stored?.takeIf { it in TranslationStyle.nameStyleOptions(code) } ?: TranslationStyle.defaultNameStyle(code)
    }

    fun setDefaultNameStyle(context: Context, lang: String, style: String) {
        val code = TranslationLanguages.normalize(lang) ?: return
        edit(context) { putString(KEY_DEFAULT_NAME_STYLE_PREFIX + code, style) }
    }

    /** Global default honorific style ("keep" / "drop" / "adapt") for [lang]. */
    fun getDefaultHonorificStyle(context: Context?, lang: String?): String {
        val code = TranslationLanguages.normalize(lang) ?: TranslationLanguages.DEFAULT
        val stored = getPrefs(context)?.getString(KEY_DEFAULT_HONORIFIC_STYLE_PREFIX + code, null)
        return stored?.takeIf { it in TranslationStyle.HONORIFIC_OPTIONS } ?: TranslationStyle.defaultHonorificStyle(code)
    }

    fun setDefaultHonorificStyle(context: Context, lang: String, style: String) {
        val code = TranslationLanguages.normalize(lang) ?: return
        edit(context) { putString(KEY_DEFAULT_HONORIFIC_STYLE_PREFIX + code, style) }
    }
}
