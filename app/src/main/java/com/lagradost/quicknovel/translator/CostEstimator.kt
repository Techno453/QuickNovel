package com.lagradost.quicknovel.translator

import com.lagradost.quicknovel.translator.SecurePrefs.MODEL_GPT4O
import com.lagradost.quicknovel.translator.SecurePrefs.MODEL_GPT4O_MINI

object CostEstimator {
    // Prices per 1M tokens (USD)
    private val inputPricePerMillion = mapOf(
        MODEL_GPT4O to 2.50,
        MODEL_GPT4O_MINI to 0.15
    )
    private val outputPricePerMillion = mapOf(
        MODEL_GPT4O to 10.00,
        MODEL_GPT4O_MINI to 0.60
    )

    /**
     * Estimate cost for translating a given number of Chinese characters.
     * @return Pair of (estimated total tokens, estimated USD cost)
     */
    fun estimateCost(chineseCharCount: Int, model: String): Pair<Int, Double> {
        val inputTokens = (chineseCharCount * 0.6 + 500).toInt()
        val outputTokens = (inputTokens * 1.5).toInt()
        val totalTokens = inputTokens + outputTokens

        val inputPrice = inputPricePerMillion[model] ?: inputPricePerMillion[MODEL_GPT4O_MINI]!!
        val outputPrice = outputPricePerMillion[model] ?: outputPricePerMillion[MODEL_GPT4O_MINI]!!

        val cost = (inputTokens / 1_000_000.0) * inputPrice +
                (outputTokens / 1_000_000.0) * outputPrice

        return totalTokens to cost
    }

    /**
     * Estimate cost for translating a range of chapters.
     * Uses a rough average of 4000 chars per chapter when actual content is not available.
     */
    fun estimateCostForChapters(chapterCount: Int, model: String, avgCharsPerChapter: Int = 4000): Pair<Int, Double> {
        return estimateCost(chapterCount * avgCharsPerChapter, model)
    }

    fun formatCost(cost: Double): String {
        return if (cost < 0.01) "< $0.01" else "~$%.2f".format(cost)
    }
}
