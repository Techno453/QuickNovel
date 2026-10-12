package com.lagradost.quicknovel.translator

import java.net.URI
import java.util.Locale

/** Input tokens per source character for one tokenizer, by source language. */
data class TokensPerChar(val zh: Double, val ja: Double, val ko: Double) {
    fun forLang(lang: String?): Double = when (TranslationLanguages.normalize(lang)) {
        TranslationLanguages.JAPANESE -> ja
        TranslationLanguages.KOREAN -> ko
        else -> zh
    }
}

/** An OpenAI-compatible API provider. API keys are stored per provider id. */
data class LlmProvider(
    val id: String,
    val label: String,
    /** Default base URL ("" for custom endpoints). */
    val baseUrl: String,
    val keyPlaceholder: String = "sk-...",
    /** Extra HTTP headers sent with every request (e.g. OpenRouter app attribution). */
    val headers: Map<String, String> = emptyMap(),
)

object LlmProviders {
    val OPENAI = LlmProvider("openai", "OpenAI", "https://api.openai.com/v1")
    val GOOGLE = LlmProvider(
        "google", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "AIza..."
    )
    val DEEPSEEK = LlmProvider("deepseek", "DeepSeek", "https://api.deepseek.com")
    val QWEN = LlmProvider("qwen", "Alibaba Qwen", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1")
    val OPENROUTER = LlmProvider(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "sk-or-...",
        headers = mapOf("HTTP-Referer" to "https://github.com/LagradOst/QuickNovel", "X-Title" to "QuickNovel"),
    )
    val ANTHROPIC = LlmProvider("anthropic", "Anthropic", "https://api.anthropic.com/v1", "sk-ant-...")
    val CUSTOM = LlmProvider("custom", "Custom endpoint", "", "API key")

    val ALL = listOf(OPENAI, GOOGLE, DEEPSEEK, QWEN, OPENROUTER, ANTHROPIC, CUSTOM)

    fun byId(id: String?): LlmProvider = ALL.firstOrNull { it.id == id } ?: CUSTOM

    /** Provider whose API host matches [baseUrl]; [CUSTOM] for anything else (local servers, other vendors). */
    fun forBaseUrl(baseUrl: String?): LlmProvider {
        val host = try {
            URI(LlmConfig.normalizeBaseUrl(baseUrl)).host?.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        } ?: return CUSTOM
        return ALL.firstOrNull { p ->
            p.baseUrl.isNotEmpty() && URI(p.baseUrl).host.equals(host, ignoreCase = true)
        } ?: CUSTOM
    }
}

/** Grouping in the model picker, roughly by price/quality role. */
enum class PresetTier(val label: String) {
    RECOMMENDED("Recommended"),
    QUALITY("Best quality"),
    BALANCED("Balanced"),
    BUDGET("Cheapest"),
    LEGACY("Legacy"),
    PREMIUM("Premium (over the \$3–5 / 100 chapters budget)"),
}

/**
 * A ready-made model configuration: endpoint, model id, extra request fields and whether to send
 * `temperature`, plus list prices and tokenizer ratios for cost estimates.
 */
data class ModelPreset(
    val id: String,
    val label: String,
    val provider: LlmProvider,
    val baseUrl: String,
    val model: String,
    /** Merged into every request body; a null value removes that field. */
    val extraBody: Map<String, Any?> = emptyMap(),
    /** False for models that reject or misbehave with a custom temperature (Gemini 3, Claude). */
    val sendTemperature: Boolean = true,
    /** USD per 1M input tokens. */
    val priceIn: Double,
    /** USD per 1M output tokens. */
    val priceOut: Double,
    val tokensPerChar: TokensPerChar,
    val notes: String = "",
    val tier: PresetTier,
    /** [PromptProfile] id for this model, chosen from the translation benchmark (default = standard prompt). */
    val promptProfile: String = PromptProfiles.DEFAULT_ID,
) {
    fun toEngineConfig(apiKey: String = ""): EngineConfig = EngineConfig(
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        extraBody = extraBody,
        sendTemperature = sendTemperature,
        headers = provider.headers,
        presetId = id,
        providerId = provider.id,
        label = label,
        promptProfile = promptProfile,
    )
}

/** Everything [TranslationEngine] needs to talk to one model. */
data class EngineConfig(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val extraBody: Map<String, Any?> = emptyMap(),
    val sendTemperature: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
    /** Preset this config came from; null for a custom model. */
    val presetId: String? = null,
    val providerId: String = LlmProviders.CUSTOM.id,
    /** Display name (preset label or the model id). */
    val label: String = model,
    /** [PromptProfile] id (extra system-prompt rules for this model). */
    val promptProfile: String = PromptProfiles.DEFAULT_ID,
) {
    val isCustom: Boolean get() = presetId == null

    /** True if both configs would send requests for the same model to the same endpoint. */
    fun sameModelAs(other: EngineConfig): Boolean =
        LlmConfig.normalizeBaseUrl(baseUrl) == LlmConfig.normalizeBaseUrl(other.baseUrl) &&
            model.equals(other.model, ignoreCase = true)

    override fun toString(): String =
        "EngineConfig(baseUrl=$baseUrl, model=$model, presetId=$presetId, apiKey=${if (apiKey.isBlank()) "none" else "****"})"
}

/**
 * Shipped presets. Prices are official list prices (USD per 1M tokens, 2026-10-11); see
 * docs/translation-models.md for the benchmark behind the choices.
 *
 * Claude Haiku 5.5 is deliberately absent: in the 2026-10-11 benchmark it refused two of three
 * chapters (citing copyright) and abridged its output.
 */
object ModelPresets {
    private val OPENAI_TPC = TokensPerChar(0.97, 0.89, 0.86)
    private val GEMINI_TPC = TokensPerChar(0.79, 0.68, 0.82)
    private val DEEPSEEK_TPC = TokensPerChar(0.63, 0.80, 0.99)
    private val QWEN_TPC = TokensPerChar(0.69, 0.77, 1.00)
    private val CLAUDE_TPC = TokensPerChar(1.2, 1.0, 1.07)

    private val OPENAI_NO_REASONING = mapOf("reasoning_effort" to "none")
    private val OPENROUTER_NO_REASONING = mapOf("reasoning" to mapOf("enabled" to false))

    private const val GEMINI_MATURE =
        "May block mature content (Gemini's PROHIBITED_CONTENT filter cannot be turned off); set a fallback for 18+ novels."
    private const val VIA_OPENROUTER = "Via OpenRouter: one key for every provider, no per-token markup (5.5% fee on credits)."

    val GPT_6_LUNA = ModelPreset(
        id = "gpt-6-luna", label = "GPT-6 Luna", provider = LlmProviders.OPENAI,
        baseUrl = LlmProviders.OPENAI.baseUrl, model = "gpt-6-luna",
        extraBody = OPENAI_NO_REASONING, priceIn = 0.10, priceOut = 0.50, tokensPerChar = OPENAI_TPC,
        notes = "Default. Best value in the 2026-10-11 benchmark: judge 8.1, 100% glossary adherence, " +
            "about \$0.19 per 100 chapters measured.",
        tier = PresetTier.RECOMMENDED,
    )

    val GPT_6_SOL = ModelPreset(
        id = "gpt-6-sol", label = "GPT-6 Sol", provider = LlmProviders.OPENAI,
        baseUrl = LlmProviders.OPENAI.baseUrl, model = "gpt-6-sol",
        extraBody = OPENAI_NO_REASONING, priceIn = 2.00, priceOut = 10.00, tokensPerChar = OPENAI_TPC,
        notes = "Highest judge score (8.4) in the benchmark; about \$3.7 per 100 chapters measured.",
        tier = PresetTier.QUALITY,
    )

    val GPT_5_4_MINI = ModelPreset(
        id = "gpt-5.4-mini", label = "GPT-5.4 mini", provider = LlmProviders.OPENAI,
        baseUrl = LlmProviders.OPENAI.baseUrl, model = "gpt-5.4-mini",
        extraBody = OPENAI_NO_REASONING, priceIn = 0.75, priceOut = 4.50, tokensPerChar = OPENAI_TPC,
        notes = "Judge 7.9, fast (12 s per chapter); costs more than Luna for no measured gain.",
        tier = PresetTier.BALANCED,
    )

    val GEMINI_3_1_FLASH_LITE = ModelPreset(
        id = "gemini-3.1-flash-lite", label = "Gemini 3.1 Flash-Lite", provider = LlmProviders.GOOGLE,
        baseUrl = LlmProviders.GOOGLE.baseUrl, model = "gemini-3.1-flash-lite",
        // Thinking cannot be fully disabled on Gemini 3; "minimal" is the lowest level.
        extraBody = mapOf("reasoning_effort" to "minimal"), sendTemperature = false,
        priceIn = 0.25, priceOut = 1.50, tokensPerChar = GEMINI_TPC,
        notes = "Fastest in the benchmark (8 s per chapter), judge 8.0. $GEMINI_MATURE",
        tier = PresetTier.BUDGET,
    )

    val DEEPSEEK_FLASH = ModelPreset(
        id = "deepseek-flash", label = "DeepSeek Flash", provider = LlmProviders.DEEPSEEK,
        baseUrl = LlmProviders.DEEPSEEK.baseUrl, model = "deepseek-flash",
        extraBody = mapOf("thinking" to mapOf("type" to "disabled")),
        priceIn = 0.30, priceOut = 1.20, tokensPerChar = DEEPSEEK_TPC,
        notes = "Chinese-trained, judge 7.6. Peak price shown; half price (0.15/0.60) off-peak. " +
            "Rejects politically sensitive text (\"Content Exists Risk\"), so set a fallback.",
        tier = PresetTier.BUDGET,
    )

    val QWEN_3_8_FLASH = ModelPreset(
        id = "qwen3.8-flash", label = "Qwen 3.8 Flash", provider = LlmProviders.QWEN,
        baseUrl = LlmProviders.QWEN.baseUrl, model = "qwen3.8-flash",
        extraBody = mapOf("enable_thinking" to false),
        priceIn = 0.15, priceOut = 0.47, tokensPerChar = QWEN_TPC,
        notes = "Cheapest, judge 7.8, but slow (39 s per chapter). International endpoint; keys are " +
            "region-locked. May reject content (data_inspection_failed).",
        tier = PresetTier.BUDGET,
    )

    val OR_GPT_6_LUNA = ModelPreset(
        id = "or-gpt-6-luna", label = "GPT-6 Luna (OpenRouter)", provider = LlmProviders.OPENROUTER,
        baseUrl = LlmProviders.OPENROUTER.baseUrl, model = "openai/gpt-6-luna",
        extraBody = OPENROUTER_NO_REASONING, priceIn = 0.10, priceOut = 0.50, tokensPerChar = OPENAI_TPC,
        notes = VIA_OPENROUTER, tier = PresetTier.RECOMMENDED,
    )

    val OR_GPT_6_SOL = ModelPreset(
        id = "or-gpt-6-sol", label = "GPT-6 Sol (OpenRouter)", provider = LlmProviders.OPENROUTER,
        baseUrl = LlmProviders.OPENROUTER.baseUrl, model = "openai/gpt-6-sol",
        extraBody = OPENROUTER_NO_REASONING, priceIn = 2.00, priceOut = 10.00, tokensPerChar = OPENAI_TPC,
        notes = VIA_OPENROUTER, tier = PresetTier.QUALITY,
    )

    val OR_GEMINI_3_1_FLASH_LITE = ModelPreset(
        id = "or-gemini-3.1-flash-lite", label = "Gemini 3.1 Flash-Lite (OpenRouter)", provider = LlmProviders.OPENROUTER,
        baseUrl = LlmProviders.OPENROUTER.baseUrl, model = "google/gemini-3.1-flash-lite",
        extraBody = OPENROUTER_NO_REASONING, sendTemperature = false,
        priceIn = 0.25, priceOut = 1.50, tokensPerChar = GEMINI_TPC,
        notes = "$VIA_OPENROUTER $GEMINI_MATURE", tier = PresetTier.BUDGET,
    )

    val OR_DEEPSEEK_FLASH = ModelPreset(
        id = "or-deepseek-flash", label = "DeepSeek Flash (OpenRouter)", provider = LlmProviders.OPENROUTER,
        baseUrl = LlmProviders.OPENROUTER.baseUrl, model = "deepseek/deepseek-v4.1-flash",
        extraBody = OPENROUTER_NO_REASONING, priceIn = 0.30, priceOut = 1.20, tokensPerChar = DEEPSEEK_TPC,
        notes = "$VIA_OPENROUTER No off-peak discount on OpenRouter.", tier = PresetTier.BUDGET,
    )

    val OR_QWEN_3_8_FLASH = ModelPreset(
        id = "or-qwen3.8-flash", label = "Qwen 3.8 Flash (OpenRouter)", provider = LlmProviders.OPENROUTER,
        baseUrl = LlmProviders.OPENROUTER.baseUrl, model = "qwen/qwen3.8-flash",
        extraBody = OPENROUTER_NO_REASONING, priceIn = 0.15, priceOut = 0.47, tokensPerChar = QWEN_TPC,
        notes = VIA_OPENROUTER, tier = PresetTier.BUDGET,
    )

    val GPT_4O_MINI = ModelPreset(
        id = "gpt-4o-mini", label = "GPT-4o mini", provider = LlmProviders.OPENAI,
        baseUrl = LlmProviders.OPENAI.baseUrl, model = "gpt-4o-mini",
        priceIn = 0.15, priceOut = 0.60, tokensPerChar = OPENAI_TPC,
        notes = "Previous default. Judge 7.1; GPT-6 Luna is better and cheaper.",
        tier = PresetTier.LEGACY,
    )

    val GPT_4O = ModelPreset(
        id = "gpt-4o", label = "GPT-4o", provider = LlmProviders.OPENAI,
        baseUrl = LlmProviders.OPENAI.baseUrl, model = "gpt-4o",
        priceIn = 2.50, priceOut = 10.00, tokensPerChar = OPENAI_TPC,
        notes = "Judge 8.1 at 20× the price of GPT-6 Luna.",
        tier = PresetTier.LEGACY,
    )

    val CLAUDE_SONNET_5_5 = ModelPreset(
        id = "claude-sonnet-5-5", label = "Claude Sonnet 5.5", provider = LlmProviders.ANTHROPIC,
        baseUrl = LlmProviders.ANTHROPIC.baseUrl, model = "claude-sonnet-5-5",
        // Anthropic rejects temperature < 1 through the compatibility layer; max_tokens keeps output bounded.
        extraBody = mapOf("max_tokens" to 8192), sendTemperature = false,
        priceIn = 2.00, priceOut = 10.00, tokensPerChar = CLAUDE_TPC,
        notes = "Over budget (about \$5.8 per 100 chapters measured) and judged 7.0. Claude Haiku 5.5 is " +
            "not offered: it refused 2 of 3 benchmark chapters citing copyright and abridged its output.",
        tier = PresetTier.PREMIUM,
    )

    /** All presets, in picker order. */
    val ALL: List<ModelPreset> = listOf(
        GPT_6_LUNA, OR_GPT_6_LUNA,
        GPT_6_SOL, OR_GPT_6_SOL,
        GPT_5_4_MINI,
        GEMINI_3_1_FLASH_LITE, OR_GEMINI_3_1_FLASH_LITE,
        DEEPSEEK_FLASH, OR_DEEPSEEK_FLASH,
        QWEN_3_8_FLASH, OR_QWEN_3_8_FLASH,
        GPT_4O_MINI, GPT_4O,
        CLAUDE_SONNET_5_5,
    )

    val DEFAULT: ModelPreset = GPT_6_LUNA

    /** Presets suggested as a fallback (in the settings summary). */
    val SUGGESTED_FALLBACKS: List<ModelPreset> = listOf(GEMINI_3_1_FLASH_LITE, GPT_6_SOL)

    fun byId(id: String?): ModelPreset? = ALL.firstOrNull { it.id == id }

    /** Preset with this endpoint and model id (extra body ignored), or null. */
    fun match(baseUrl: String?, model: String?): ModelPreset? {
        if (model.isNullOrBlank()) return null
        val url = LlmConfig.normalizeBaseUrl(baseUrl)
        return ALL.firstOrNull { LlmConfig.normalizeBaseUrl(it.baseUrl) == url && it.model.equals(model.trim(), ignoreCase = true) }
    }
}
