package com.lagradost.quicknovel.translator

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Rough token / cost estimates for translating source-language text into English.
 * Pure Kotlin so it can be unit tested.
 */
object CostEstimator {
    /** USD per 1M tokens. */
    data class ModelPricing(val inputPerMillion: Double, val outputPerMillion: Double)

    data class Estimate(
        val inputTokens: Int,
        val outputTokens: Int,
        /** Null when the model's pricing is unknown (custom / self-hosted models). */
        val usd: Double?,
    ) {
        val totalTokens: Int get() = inputTokens + outputTokens
    }

    /** Known list prices. Keys are matched exactly or as a prefix followed by '-' (dated snapshots). */
    private val pricing: Map<String, ModelPricing> = mapOf(
        LlmConfig.MODEL_GPT4O to ModelPricing(2.50, 10.00),
        LlmConfig.MODEL_GPT4O_MINI to ModelPricing(0.15, 0.60),
        "gpt-4.1" to ModelPricing(2.00, 8.00),
        "gpt-4.1-mini" to ModelPricing(0.40, 1.60),
        "gpt-4.1-nano" to ModelPricing(0.10, 0.40),
    )

    /** Approximate input tokens per source character. */
    private fun tokensPerChar(sourceLang: String?): Double = when (TranslationLanguages.normalize(sourceLang)) {
        TranslationLanguages.JAPANESE -> 0.7
        TranslationLanguages.KOREAN -> 0.5 // Hangul text includes spaces; denser per char
        else -> 0.6
    }

    /** Prompt overhead per chunk/request (system prompt, instructions, glossary). */
    private const val PROMPT_OVERHEAD_TOKENS = 500

    /** English output is ~1.5x the source token count. */
    private const val OUTPUT_RATIO = 1.5

    /**
     * Looks up pricing for [model]. Accepts provider prefixes ("openai/gpt-4o-mini") and dated
     * snapshots ("gpt-4o-2024-08-06"); the longest matching key wins. Returns null if unknown.
     */
    fun pricingFor(model: String?): ModelPricing? {
        if (model.isNullOrBlank()) return null
        val id = model.trim().lowercase(Locale.ROOT).substringAfterLast('/')
        return pricing.entries
            .filter { (key, _) -> id == key || id.startsWith("$key-") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    fun isKnownModel(model: String?): Boolean = pricingFor(model) != null

    /** Estimate for translating [sourceCharCount] characters of [sourceLang] text with [model]. */
    fun estimate(sourceCharCount: Int, model: String, sourceLang: String? = TranslationLanguages.DEFAULT): Estimate {
        if (sourceCharCount <= 0) return Estimate(0, 0, pricingFor(model)?.let { 0.0 })
        val inputTokens = (sourceCharCount * tokensPerChar(sourceLang) + PROMPT_OVERHEAD_TOKENS).roundToInt()
        val outputTokens = (inputTokens * OUTPUT_RATIO).roundToInt()
        val usd = pricingFor(model)?.let { p ->
            inputTokens / 1_000_000.0 * p.inputPerMillion + outputTokens / 1_000_000.0 * p.outputPerMillion
        }
        return Estimate(inputTokens, outputTokens, usd)
    }

    /**
     * Estimate for a range of chapters, using an average chapter length when the
     * actual content is not available.
     */
    fun estimateForChapters(
        chapterCount: Int,
        model: String,
        sourceLang: String? = TranslationLanguages.DEFAULT,
        avgCharsPerChapter: Int = 4000
    ): Estimate {
        if (chapterCount <= 0) return Estimate(0, 0, pricingFor(model)?.let { 0.0 })
        val perChapter = estimate(avgCharsPerChapter, model, sourceLang)
        return Estimate(
            perChapter.inputTokens * chapterCount,
            perChapter.outputTokens * chapterCount,
            perChapter.usd?.times(chapterCount)
        )
    }

    /** Back-compat: (total tokens, USD) with unknown models priced as gpt-4o-mini. */
    fun estimateCost(sourceCharCount: Int, model: String): Pair<Int, Double> {
        val e = estimate(sourceCharCount, model)
        val usd = e.usd ?: estimate(sourceCharCount, LlmConfig.MODEL_GPT4O_MINI).usd ?: 0.0
        return e.totalTokens to usd
    }

    fun formatCost(cost: Double): String =
        if (cost < 0.01) "< \$0.01" else "~\$" + String.format(Locale.US, "%.2f", cost)

    fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.US, "%.0fk", tokens / 1_000.0)
        else -> tokens.toString()
    }

    /** "~$0.12 (~45k tokens)" or, for unknown models, "~45k tokens (pricing unknown)". */
    fun formatEstimate(e: Estimate): String {
        val tokens = "~${formatTokens(e.totalTokens)} tokens"
        return e.usd?.let { "${formatCost(it)} ($tokens)" } ?: "$tokens (pricing unknown)"
    }
}
