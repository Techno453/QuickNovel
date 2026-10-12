package com.lagradost.quicknovel.translator

import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Token / cost estimates for translating source-language chapters into English.
 * Pure Kotlin so it can be unit tested.
 *
 * Per chapter of `chars` source characters in language `lang`:
 * ```
 * input  = chars × tokensPerChar(model's tokenizer, lang) + 1,000 + min(120 × bibleTerms, 2,000)
 * output = 0.9 × chars × tokensPerChar(o200k, lang)          (≈ 4,000 for 4,500 zh chars)
 * strict mode: input and output × 1.15 (one re-request for some passages)
 * usd    = input × $in / 1M + output × $out / 1M
 * ```
 */
object CostEstimator {
    /** Tokenizer families with measured input tokens per source character (zh / ja / ko). */
    enum class TokenizerFamily(val tokensPerChar: TokensPerChar) {
        OPENAI(TokensPerChar(0.97, 0.89, 0.86)),
        CLAUDE(TokensPerChar(1.2, 1.0, 1.07)),
        GEMINI(TokensPerChar(0.79, 0.68, 0.82)),
        DEEPSEEK(TokensPerChar(0.63, 0.80, 0.99)),
        QWEN(TokensPerChar(0.69, 0.77, 1.00)),
        GLM(TokensPerChar(0.68, 0.83, 1.16)),
        KIMI(TokensPerChar(0.60, 0.91, 1.20)),
    }

    /** USD per 1M tokens. */
    data class ModelPricing(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
        val family: TokenizerFamily = TokenizerFamily.OPENAI,
        val note: String = "",
    )

    data class Estimate(
        val inputTokens: Int,
        val outputTokens: Int,
        /** Null when the model's pricing is unknown (custom / self-hosted models). */
        val usd: Double?,
    ) {
        val totalTokens: Int get() = inputTokens + outputTokens

        operator fun times(n: Int) = Estimate(inputTokens * n, outputTokens * n, usd?.times(n))
    }

    const val DEFAULT_CHAPTER_CHARS = 4500
    /** System prompt + story-bible framing per chapter. */
    const val PROMPT_OVERHEAD_TOKENS = 1000
    const val TOKENS_PER_BIBLE_TERM = 120
    const val MAX_BIBLE_TOKENS = 2000
    /** English output tokens per source character, relative to o200k input tokens. */
    const val OUTPUT_RATIO = 0.9
    /** Strict consistency mode re-requests some passages once. */
    const val STRICT_RETRY_FACTOR = 1.15

    private val OUTPUT_TOKENIZER = TokenizerFamily.OPENAI

    /** Official list prices, 2026-10-11. Keys are matched exactly or as a prefix followed by '-' (dated snapshots). */
    private val pricing: Map<String, ModelPricing> = linkedMapOf(
        // OpenAI
        "gpt-6-luna" to ModelPricing(0.10, 0.50),
        "gpt-6-sol" to ModelPricing(2.00, 10.00),
        "gpt-6.1-sol" to ModelPricing(2.00, 10.00),
        "gpt-6-astra" to ModelPricing(10.00, 50.00),
        "gpt-5.4-mini" to ModelPricing(0.75, 4.50),
        "gpt-5-mini" to ModelPricing(0.25, 2.00),
        "gpt-5.6-terra" to ModelPricing(2.00, 12.00),
        LlmConfig.MODEL_GPT4O to ModelPricing(2.50, 10.00),
        LlmConfig.MODEL_GPT4O_MINI to ModelPricing(0.15, 0.60),
        // Anthropic
        "claude-haiku-5-5" to ModelPricing(0.10, 0.50, TokenizerFamily.CLAUDE),
        "claude-sonnet-5-5" to ModelPricing(2.00, 10.00, TokenizerFamily.CLAUDE),
        "claude-opus-5-5" to ModelPricing(4.00, 20.00, TokenizerFamily.CLAUDE),
        // Google
        "gemini-3.1-flash-lite" to ModelPricing(0.25, 1.50, TokenizerFamily.GEMINI),
        "gemini-3.5-flash-lite" to ModelPricing(0.30, 2.50, TokenizerFamily.GEMINI),
        "gemini-3.8-flash" to ModelPricing(0.75, 3.75, TokenizerFamily.GEMINI),
        "gemini-3.1-pro-preview" to ModelPricing(2.00, 12.00, TokenizerFamily.GEMINI),
        // DeepSeek (peak prices; off-peak is half)
        "deepseek-flash" to ModelPricing(0.30, 1.20, TokenizerFamily.DEEPSEEK, "0.15/0.60 off-peak"),
        "deepseek-v4-pro" to ModelPricing(1.32, 3.96, TokenizerFamily.DEEPSEEK, "0.66/1.98 off-peak"),
        // Alibaba
        "qwen3.8-flash" to ModelPricing(0.15, 0.47, TokenizerFamily.QWEN),
        "qwen3.7-plus" to ModelPricing(0.40, 1.60, TokenizerFamily.QWEN),
        "qwen3.8-max" to ModelPricing(2.00, 6.00, TokenizerFamily.QWEN),
        // Moonshot
        "kimi-k2.6" to ModelPricing(0.95, 4.00, TokenizerFamily.KIMI),
        "kimi-k3" to ModelPricing(3.00, 15.00, TokenizerFamily.KIMI),
        // Z.ai
        "glm-5.3-flash" to ModelPricing(0.15, 0.50, TokenizerFamily.GLM),
        "glm-5.3" to ModelPricing(1.40, 4.40, TokenizerFamily.GLM),
        "glm-4.7-flash" to ModelPricing(0.0, 0.0, TokenizerFamily.GLM, "free tier"),
        // xAI, Mistral (tokenizers unmeasured; o200k ratios used)
        "grok-4.20" to ModelPricing(1.25, 2.50),
        "mistral-small-2603" to ModelPricing(0.15, 0.60),
        "mistral-large-4" to ModelPricing(0.68, 2.09),
    )

    /** Other names for priced models (provider-specific ids, OpenRouter slugs). */
    private val aliases: Map<String, String> = mapOf(
        "deepseek-v4.1-flash" to "deepseek-flash",
        "deepseek-v4-flash" to "deepseek-flash",
        "deepseek-chat" to "deepseek-flash",
        "gemini-3.1-pro" to "gemini-3.1-pro-preview",
    )

    /**
     * Normalized form for matching: lower case, provider prefix ("openai/") and OpenRouter variant
     * suffix (":free") removed, '.' treated like '-' (so "claude-sonnet-5.5" matches "claude-sonnet-5-5").
     */
    internal fun normalizeId(model: String): String =
        model.trim().lowercase(Locale.ROOT).substringAfterLast('/').substringBefore(':').replace('.', '-')

    private val lookup: Map<String, String> by lazy {
        pricing.keys.associateBy { normalizeId(it) } + aliases.mapKeys { normalizeId(it.key) }
    }

    /** The price-table key for [model] (exact, dated snapshot, provider prefix or alias), or null. */
    fun resolveModelKey(model: String?): String? {
        if (model.isNullOrBlank()) return null
        val id = normalizeId(model)
        return lookup.entries
            .filter { (key, _) -> id == key || id.startsWith("$key-") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun pricingFor(model: String?): ModelPricing? = resolveModelKey(model)?.let { pricing[it] }

    fun isKnownModel(model: String?): Boolean = pricingFor(model) != null

    /** Tokenizer family of [model]: from the price table, else guessed from the name (default o200k). */
    fun familyFor(model: String?): TokenizerFamily {
        pricingFor(model)?.let { return it.family }
        val id = model.orEmpty().lowercase(Locale.ROOT)
        return when {
            "claude" in id -> TokenizerFamily.CLAUDE
            "gemini" in id || "gemma" in id -> TokenizerFamily.GEMINI
            "deepseek" in id -> TokenizerFamily.DEEPSEEK
            "qwen" in id -> TokenizerFamily.QWEN
            "glm" in id -> TokenizerFamily.GLM
            "kimi" in id || "moonshot" in id -> TokenizerFamily.KIMI
            else -> TokenizerFamily.OPENAI
        }
    }

    /** Prompt overhead for one chapter with [bibleTerms] Story Bible terms in the prompt. */
    fun overheadTokens(bibleTerms: Int): Int =
        PROMPT_OVERHEAD_TOKENS + min(bibleTerms.coerceAtLeast(0) * TOKENS_PER_BIBLE_TERM, MAX_BIBLE_TOKENS)

    /** Core estimate for one chapter of [chars] characters. */
    fun estimateChapter(
        chars: Int,
        pricing: ModelPricing?,
        tokensPerChar: TokensPerChar,
        sourceLang: String?,
        strictMode: Boolean,
        bibleTerms: Int = 0,
    ): Estimate {
        if (chars <= 0) return Estimate(0, 0, pricing?.let { 0.0 })
        val factor = if (strictMode) STRICT_RETRY_FACTOR else 1.0
        val input = ((chars * tokensPerChar.forLang(sourceLang) + overheadTokens(bibleTerms)) * factor).roundToInt()
        val output = (chars * OUTPUT_RATIO * OUTPUT_TOKENIZER.tokensPerChar.forLang(sourceLang) * factor).roundToInt()
        return Estimate(input, output, pricing?.let { usd(input, output, it) })
    }

    private fun usd(input: Int, output: Int, p: ModelPricing): Double =
        input / 1_000_000.0 * p.inputPerMillion + output / 1_000_000.0 * p.outputPerMillion

    /** Estimate for translating [sourceCharCount] characters of [sourceLang] text with [model] as one chapter. */
    fun estimate(
        sourceCharCount: Int,
        model: String,
        sourceLang: String? = TranslationLanguages.DEFAULT,
        strictMode: Boolean = false,
        bibleTerms: Int = 0,
    ): Estimate = estimateChapter(
        sourceCharCount, pricingFor(model), familyFor(model).tokensPerChar, sourceLang, strictMode, bibleTerms
    )

    /**
     * Estimate for [count] chapters of [avgCharsPerChapter] characters translated with [model]
     * (any id: exact, dated, "org/model" or alias). Unknown models get tokens but a null price.
     */
    fun estimateCostForChapters(
        count: Int,
        model: String,
        lang: String? = TranslationLanguages.DEFAULT,
        strictMode: Boolean = false,
        bibleTerms: Int = 0,
        avgCharsPerChapter: Int = DEFAULT_CHAPTER_CHARS,
    ): Estimate {
        if (count <= 0) return Estimate(0, 0, pricingFor(model)?.let { 0.0 })
        return estimate(avgCharsPerChapter, model, lang, strictMode, bibleTerms) * count
    }

    /** Same as [estimateCostForChapters] with the preset's own prices and tokenizer ratios. */
    fun estimateForPreset(
        preset: ModelPreset,
        count: Int,
        lang: String? = TranslationLanguages.DEFAULT,
        strictMode: Boolean = false,
        bibleTerms: Int = 0,
        avgCharsPerChapter: Int = DEFAULT_CHAPTER_CHARS,
    ): Estimate {
        val p = ModelPricing(preset.priceIn, preset.priceOut)
        if (count <= 0) return Estimate(0, 0, 0.0)
        return estimateChapter(avgCharsPerChapter, p, preset.tokensPerChar, lang, strictMode, bibleTerms) * count
    }

    /** Estimate for an engine config: preset prices when it came from a preset, else the price table. */
    fun estimateForConfig(
        config: EngineConfig,
        count: Int,
        lang: String? = TranslationLanguages.DEFAULT,
        strictMode: Boolean = false,
        bibleTerms: Int = 0,
        avgCharsPerChapter: Int = DEFAULT_CHAPTER_CHARS,
    ): Estimate = ModelPresets.byId(config.presetId)
        ?.let { estimateForPreset(it, count, lang, strictMode, bibleTerms, avgCharsPerChapter) }
        ?: estimateCostForChapters(count, config.model, lang, strictMode, bibleTerms, avgCharsPerChapter)

    /** Source characters per "Analyze novel" request. */
    const val ANALYSIS_CHARS_PER_CALL = 8000

    /** Analysis output (character sheet JSON) is ~0.3x its input. */
    private const val ANALYSIS_OUTPUT_RATIO = 0.3

    /** Prompt overhead per analysis call (instructions, known-term list, premise so far). */
    private const val ANALYSIS_OVERHEAD_TOKENS = 900

    /**
     * Estimate for the "Analyze novel" pre-pass over [chapterCount] chapters: input-heavy,
     * one call per [ANALYSIS_CHARS_PER_CALL] source characters.
     */
    fun estimateAnalysis(
        chapterCount: Int,
        model: String,
        sourceLang: String? = TranslationLanguages.DEFAULT,
        avgCharsPerChapter: Int = DEFAULT_CHAPTER_CHARS,
    ): Estimate {
        val p = pricingFor(model)
        if (chapterCount <= 0) return Estimate(0, 0, p?.let { 0.0 })
        val chars = chapterCount.toLong() * avgCharsPerChapter
        val calls = ((chars + ANALYSIS_CHARS_PER_CALL - 1) / ANALYSIS_CHARS_PER_CALL).toInt().coerceAtLeast(1)
        val inputTokens = (chars * familyFor(model).tokensPerChar.forLang(sourceLang)).roundToInt() +
            calls * ANALYSIS_OVERHEAD_TOKENS
        val outputTokens = (inputTokens * ANALYSIS_OUTPUT_RATIO).roundToInt()
        return Estimate(inputTokens, outputTokens, p?.let { usd(inputTokens, outputTokens, it) })
    }

    fun formatCost(cost: Double): String = when {
        cost <= 0.0 -> "\$0"
        cost < 0.01 -> "< \$0.01"
        else -> "≈ \$" + String.format(Locale.US, "%.2f", cost)
    }

    fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.US, "%.0fk", tokens / 1_000.0)
        else -> tokens.toString()
    }

    /** "≈ $0.12 (≈ 45k tokens)" or, for unknown models, "≈ 45k tokens (unknown price)". */
    fun formatEstimate(e: Estimate): String {
        val tokens = "≈ ${formatTokens(e.totalTokens)} tokens"
        return e.usd?.let { "${formatCost(it)} ($tokens)" } ?: "$tokens (unknown price)"
    }

    /** "≈ $0.29 / 100 ch" for a 100-chapter estimate; "unknown price" when unpriced. */
    fun formatPer100(e: Estimate): String =
        e.usd?.let { "≈ \$" + String.format(Locale.US, "%.2f", it) + " / 100 ch" } ?: "unknown price"
}
