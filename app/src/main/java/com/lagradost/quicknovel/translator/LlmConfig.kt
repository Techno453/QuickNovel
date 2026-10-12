package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Pure (Android-free) configuration helpers for the OpenAI-compatible chat completions backend.
 */
object LlmConfig {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    const val MODEL_GPT4O = "gpt-4o"
    const val MODEL_GPT4O_MINI = "gpt-4o-mini"
    const val DEFAULT_MODEL = MODEL_GPT4O_MINI

    /** Model presets shown in settings, (model id, label). A "Custom…" entry is added by the UI. */
    val MODEL_PRESETS: List<Pair<String, String>> = listOf(
        MODEL_GPT4O_MINI to "gpt-4o-mini (\$ — fast & cheap)",
        MODEL_GPT4O to "gpt-4o (\$\$\$ — best quality)",
    )

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
    fun parseExtraBody(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            (jacksonObjectMapper().readValue(json, Map::class.java) as? Map<String, Any?>).orEmpty()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
