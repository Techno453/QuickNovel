package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Pure (Android-free) configuration helpers for the OpenAI-compatible chat completions backend.
 */
object LlmConfig {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    const val MODEL_GPT4O = "gpt-4o"
    const val MODEL_GPT4O_MINI = "gpt-4o-mini"

    /** Default for new installs (see [ModelPresets.DEFAULT]). */
    const val DEFAULT_MODEL = "gpt-6-luna"
    const val DEFAULT_PRESET_ID = "gpt-6-luna"

    /** Model used by installs from before presets existed that never picked a model. */
    const val LEGACY_DEFAULT_MODEL = MODEL_GPT4O_MINI

    /** Preset id stored for a free-text model / base URL / extra body. */
    const val CUSTOM_PRESET_ID = "custom"

    /** Prompt profile of a custom model until the user picks one (see docs/translation-models.md). */
    const val CUSTOM_PROMPT_PROFILE = PromptProfiles.DEFAULT_ID

    /** Structured model presets shown in settings. A "Custom…" entry is added by the UI. */
    val PRESETS: List<ModelPreset> get() = ModelPresets.ALL

    fun preset(id: String?): ModelPreset? = ModelPresets.byId(id)

    /** Outcome of moving pre-preset settings to presets: what to select and where the old key goes. */
    data class MigratedSelection(
        val presetId: String,
        /** Model id to store for a custom selection (null = leave as is). */
        val customModel: String?,
        /** Provider whose key slot receives the old single API key. */
        val keyProviderId: String,
    )

    /**
     * Pre-preset installs stored one model id, base URL and API key:
     * - a stored model and/or base URL becomes the matching preset, or "custom" if none matches
     *   (a missing model meant the old default, gpt-4o-mini);
     * - an install with a key that never picked a model keeps the old default (gpt-4o-mini);
     * - a fresh install gets [DEFAULT_PRESET_ID] (gpt-6-luna).
     */
    fun migrateLegacySelection(storedModel: String?, storedBaseUrl: String?, hasLegacyKey: Boolean): MigratedSelection {
        val model = storedModel?.trim()?.takeIf { it.isNotEmpty() }
        val url = storedBaseUrl?.takeIf { it.isNotBlank() }
        if (model != null || url != null) {
            val baseUrl = normalizeBaseUrl(url)
            val effectiveModel = model ?: LEGACY_DEFAULT_MODEL
            ModelPresets.match(baseUrl, effectiveModel)?.let { return MigratedSelection(it.id, null, it.provider.id) }
            return MigratedSelection(CUSTOM_PRESET_ID, effectiveModel, LlmProviders.forBaseUrl(baseUrl).id)
        }
        if (hasLegacyKey) return MigratedSelection(ModelPresets.GPT_4O_MINI.id, null, LlmProviders.OPENAI.id)
        return MigratedSelection(DEFAULT_PRESET_ID, null, ModelPresets.DEFAULT.provider.id)
    }

    /**
     * Normalizes a user-entered base URL: trims, adds https:// if no scheme is given, removes
     * trailing slashes and a pasted "/chat/completions" suffix. Blank input yields the default.
     */
    fun normalizeBaseUrl(raw: String?): String {
        var url = raw?.trim().orEmpty()
        if (url.isEmpty()) return DEFAULT_BASE_URL
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        url = url.trimEnd('/')
        if (url.endsWith("/chat/completions", ignoreCase = true)) {
            url = url.dropLast("/chat/completions".length).trimEnd('/')
        }
        return url
    }

    fun chatCompletionsUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + "/chat/completions"

    fun isDefaultBaseUrl(baseUrl: String?): Boolean = normalizeBaseUrl(baseUrl) == DEFAULT_BASE_URL

    /**
     * Parses a JSON object of extra request-body fields for [TranslationEngine] (e.g.
     * `{"reasoning_effort":"none"}` or `{"reasoning":{"enabled":false},"temperature":null}`).
     * Blank or invalid input yields an empty map.
     */
    fun parseExtraBody(json: String?): Map<String, Any?> = parseExtraBodyOrNull(json).orEmpty()

    /** Like [parseExtraBody], but null when non-blank input is not a JSON object (for validation). */
    fun parseExtraBodyOrNull(json: String?): Map<String, Any?>? {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            jacksonObjectMapper().readValue(json, Map::class.java) as? Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }
}
