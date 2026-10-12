package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CostEstimatorTest {

    @Test
    fun lunaChineseChapterMatchesTheFormula() {
        // 4,500 zh chars: input 4500*0.97 + 1000 = 5365, output 0.9*4500*0.97 = 3928.5 -> 3929
        val e = CostEstimator.estimateCostForChapters(1, "gpt-6-luna", "zh", strictMode = false)
        assertEquals(5365, e.inputTokens)
        assertEquals(3929, e.outputTokens)
        assertEquals(5365 / 1e6 * 0.10 + 3929 / 1e6 * 0.50, e.usd!!, 1e-12)
        // About $0.25 per 100 chapters without strict retries.
        assertEquals(0.25, CostEstimator.estimateCostForChapters(100, "gpt-6-luna", "zh").usd!!, 0.005)
    }

    @Test
    fun strictModeAddsFifteenPercent() {
        val off = CostEstimator.estimateCostForChapters(1, "gpt-6-luna", "zh", strictMode = false)
        val on = CostEstimator.estimateCostForChapters(1, "gpt-6-luna", "zh", strictMode = true)
        assertEquals(off.usd!! * 1.15, on.usd!!, 1e-5)
        assertEquals((5365 * 1.15).roundToInt(), on.inputTokens)
    }

    @Test
    fun bibleTermsAddOverheadUpToTheCap() {
        val none = CostEstimator.estimate(4500, "gpt-6-luna", "zh")
        val ten = CostEstimator.estimate(4500, "gpt-6-luna", "zh", bibleTerms = 10)
        val many = CostEstimator.estimate(4500, "gpt-6-luna", "zh", bibleTerms = 500)
        assertEquals(1200, ten.inputTokens - none.inputTokens)
        assertEquals(2000, many.inputTokens - none.inputTokens)
        assertEquals(none.outputTokens, many.outputTokens)
    }

    @Test
    fun perLanguageEstimatesUseTheProviderTokenizer() {
        // OpenAI o200k: zh 0.97 > ja 0.89 > ko 0.86
        val zh = CostEstimator.estimate(4500, "gpt-6-luna", "zh")
        val ja = CostEstimator.estimate(4500, "gpt-6-luna", "ja")
        val ko = CostEstimator.estimate(4500, "gpt-6-luna", "ko")
        assertEquals(5365, zh.inputTokens)
        assertEquals(5005, ja.inputTokens)
        assertEquals(4870, ko.inputTokens)
        assertTrue(zh.usd!! > ja.usd!! && ja.usd!! > ko.usd!!)

        // DeepSeek's tokenizer is denser on Chinese but not on Korean.
        val dsZh = CostEstimator.estimate(4500, "deepseek-flash", "zh")
        val dsKo = CostEstimator.estimate(4500, "deepseek-flash", "ko")
        assertEquals(3835, dsZh.inputTokens)
        assertEquals(5455, dsKo.inputTokens)
        // English output is estimated on o200k regardless of the input tokenizer.
        assertEquals(zh.outputTokens, dsZh.outputTokens)

        // Claude tokenizes CJK more heavily than o200k.
        assertTrue(CostEstimator.estimate(4500, "claude-sonnet-5-5", "zh").inputTokens > zh.inputTokens)
    }

    @Test
    fun aliasesSnapshotsAndProviderPrefixesResolve() {
        val luna = CostEstimator.pricingFor("gpt-6-luna")
        assertNotNull(luna)
        assertEquals(luna, CostEstimator.pricingFor("gpt-6-luna-2026-09-30"))
        assertEquals(luna, CostEstimator.pricingFor("openai/gpt-6-luna"))
        assertEquals(luna, CostEstimator.pricingFor("GPT-6-Luna"))
        assertEquals("deepseek-flash", CostEstimator.resolveModelKey("deepseek/deepseek-v4.1-flash"))
        assertEquals("deepseek-flash", CostEstimator.resolveModelKey("deepseek-chat"))
        assertEquals("claude-sonnet-5-5", CostEstimator.resolveModelKey("anthropic/claude-sonnet-5.5"))
        assertEquals("qwen3.8-flash", CostEstimator.resolveModelKey("qwen/qwen3.8-flash:free"))
        assertEquals("gemini-3.1-flash-lite", CostEstimator.resolveModelKey("google/gemini-3.1-flash-lite-preview-09-2026"))
        assertEquals("glm-5.3-flash", CostEstimator.resolveModelKey("z-ai/glm-5.3-flash"))
        assertEquals("glm-5.3", CostEstimator.resolveModelKey("glm-5.3"))
        assertEquals("gemini-3.1-pro-preview", CostEstimator.resolveModelKey("gemini-3.1-pro"))
        // Longest key wins; dotted versions do not collide.
        assertEquals("gpt-4o-mini", CostEstimator.resolveModelKey("gpt-4o-mini-2024-07-18"))
        assertEquals("gpt-6.1-sol", CostEstimator.resolveModelKey("gpt-6.1-sol"))
        assertEquals("gpt-6-sol", CostEstimator.resolveModelKey("gpt-6-sol"))
        assertEquals("gpt-5-mini", CostEstimator.resolveModelKey("gpt-5-mini"))
        assertEquals("gpt-5.4-mini", CostEstimator.resolveModelKey("gpt-5.4-mini"))
        assertNull(CostEstimator.resolveModelKey("gpt-6-lunar"))
        assertEquals(CostEstimator.TokenizerFamily.DEEPSEEK, CostEstimator.familyFor("deepseek/deepseek-v4.1-flash"))
    }

    @Test
    fun unknownModelHasTokensButNoPrice() {
        val e = CostEstimator.estimateCostForChapters(3, "my-local-llama", "zh")
        assertNull(e.usd)
        assertTrue(e.totalTokens > 0)
        assertEquals(false, CostEstimator.isKnownModel("my-local-llama"))
        val text = CostEstimator.formatEstimate(e)
        assertTrue(text, text.contains("tokens") && text.contains("unknown price") && !text.contains("$"))
        assertEquals("unknown price", CostEstimator.formatPer100(e))
        // Unknown names still get a family guess for token counts.
        assertEquals(CostEstimator.TokenizerFamily.QWEN, CostEstimator.familyFor("qwen2.5-32b-instruct"))
    }

    @Test
    fun chapterEstimateScalesLinearly() {
        val one = CostEstimator.estimateCostForChapters(1, "gpt-6-luna", "ja", strictMode = true)
        val ten = CostEstimator.estimateCostForChapters(10, "gpt-6-luna", "ja", strictMode = true)
        assertEquals(one.totalTokens * 10, ten.totalTokens)
        assertEquals(one.usd!! * 10, ten.usd!!, 1e-9)
        assertEquals(0, CostEstimator.estimateCostForChapters(0, "gpt-6-luna").totalTokens)
    }

    @Test
    fun presetEstimatesAgreeWithThePriceTable() {
        for (preset in ModelPresets.ALL) {
            val byPreset = CostEstimator.estimateForPreset(preset, 100, "zh", strictMode = true)
            val byModel = CostEstimator.estimateCostForChapters(100, preset.model, "zh", strictMode = true)
            assertNotNull("${preset.model} is not in the price table", byModel.usd)
            assertEquals(preset.id, byModel.usd!!, byPreset.usd!!, 1e-9)
            assertEquals(preset.id, byModel.totalTokens, byPreset.totalTokens)
        }
        // Default preset stays well inside the $3-5 / 100 chapters target.
        val luna = CostEstimator.estimateForPreset(ModelPresets.DEFAULT, 100, "zh", strictMode = true)
        assertTrue(luna.usd!! < 0.5)
    }

    @Test
    fun configEstimateUsesPresetOrModelId() {
        val preset = ModelPresets.GPT_6_SOL.toEngineConfig("k")
        val custom = EngineConfig(baseUrl = "https://example.com/v1", model = "gpt-6-sol")
        assertEquals(
            CostEstimator.estimateForConfig(preset, 5, "ko", true).usd!!,
            CostEstimator.estimateForConfig(custom, 5, "ko", true).usd!!, 1e-12
        )
    }

    @Test
    fun analysisEstimateIsInputHeavy() {
        val e = CostEstimator.estimateAnalysis(5, "gpt-6-luna", "zh")
        assertTrue(e.inputTokens > e.outputTokens * 3)
        assertNull(CostEstimator.estimateAnalysis(5, "local-model", "zh").usd)
    }

    @Test
    fun formatting() {
        assertEquals("< \$0.01", CostEstimator.formatCost(0.001))
        assertEquals("\$0", CostEstimator.formatCost(0.0))
        assertEquals("~\$1.23", CostEstimator.formatCost(1.234))
        assertEquals("~45k", "~" + CostEstimator.formatTokens(45_200))
        assertTrue(CostEstimator.formatEstimate(CostEstimator.estimate(4500, "gpt-4o")).startsWith("~$"))
        assertEquals("≈ \$0.25 / 100 ch", CostEstimator.formatPer100(CostEstimator.estimateCostForChapters(100, "gpt-6-luna", "zh")))
    }
}
