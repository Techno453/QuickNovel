package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CostEstimatorTest {

    @Test
    fun knownModelsArePriced() {
        val mini = CostEstimator.estimate(4000, "gpt-4o-mini")
        val full = CostEstimator.estimate(4000, "gpt-4o")
        assertNotNull(mini.usd)
        assertNotNull(full.usd)
        assertTrue(full.usd!! > mini.usd!!)
        // 4000 * 0.6 + 500 = 2900 input, 4350 output
        assertEquals(2900, mini.inputTokens)
        assertEquals(4350, mini.outputTokens)
        assertEquals(2900 / 1e6 * 0.15 + 4350 / 1e6 * 0.60, mini.usd!!, 1e-9)
    }

    @Test
    fun datedSnapshotsAndProviderPrefixesResolve() {
        assertEquals(CostEstimator.pricingFor("gpt-4o-mini"), CostEstimator.pricingFor("gpt-4o-mini-2024-07-18"))
        assertEquals(CostEstimator.pricingFor("gpt-4o"), CostEstimator.pricingFor("gpt-4o-2024-08-06"))
        assertEquals(CostEstimator.pricingFor("gpt-4o-mini"), CostEstimator.pricingFor("openai/gpt-4o-mini"))
        assertEquals(CostEstimator.pricingFor("gpt-4.1-mini"), CostEstimator.pricingFor("GPT-4.1-mini"))
    }

    @Test
    fun unknownModelHasTokensButNoPrice() {
        val e = CostEstimator.estimate(4000, "deepseek-chat")
        assertNull(e.usd)
        assertTrue(e.totalTokens > 0)
        assertEquals(false, CostEstimator.isKnownModel("my-local-llama"))
        val text = CostEstimator.formatEstimate(e)
        assertTrue(text, text.contains("tokens") && text.contains("pricing unknown") && !text.contains("$"))
    }

    @Test
    fun chapterEstimateScalesLinearly() {
        val one = CostEstimator.estimateForChapters(1, "gpt-4o-mini")
        val ten = CostEstimator.estimateForChapters(10, "gpt-4o-mini")
        assertEquals(one.totalTokens * 10, ten.totalTokens)
        assertEquals(one.usd!! * 10, ten.usd!!, 1e-9)
        assertEquals(0, CostEstimator.estimateForChapters(0, "gpt-4o").totalTokens)
    }

    @Test
    fun languageAffectsTokenEstimate() {
        val zh = CostEstimator.estimate(1000, "gpt-4o-mini", "zh")
        val ja = CostEstimator.estimate(1000, "gpt-4o-mini", "ja")
        val ko = CostEstimator.estimate(1000, "gpt-4o-mini", "ko")
        assertTrue(ja.inputTokens > zh.inputTokens)
        assertTrue(ko.inputTokens < zh.inputTokens)
    }

    @Test
    fun formatting() {
        assertEquals("< \$0.01", CostEstimator.formatCost(0.001))
        assertEquals("~\$1.23", CostEstimator.formatCost(1.234))
        assertEquals("~45k", "~" + CostEstimator.formatTokens(45_200))
        assertTrue(CostEstimator.formatEstimate(CostEstimator.estimate(4000, "gpt-4o")).startsWith("~$"))
    }

    @Test
    fun backCompatPairUsesMiniPricingForUnknown() {
        val (tokens, usd) = CostEstimator.estimateCost(4000, "unknown-model")
        assertEquals(CostEstimator.estimate(4000, "gpt-4o-mini").usd!!, usd, 1e-12)
        assertTrue(tokens > 0)
    }
}
