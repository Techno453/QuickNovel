package com.lagradost.quicknovel.ui.setup

import com.lagradost.quicknovel.translator.CostEstimator
import com.lagradost.quicknovel.translator.LlmProviders
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.TranslationLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChoicesTest {
    @Test
    fun shownOnlyOnFirstRunWithoutKeys() {
        assertTrue(SetupChoices.shouldShowOnLaunch(setupCompleted = false, hasAnyApiKey = false))
        assertFalse(SetupChoices.shouldShowOnLaunch(setupCompleted = true, hasAnyApiKey = false))
        // Existing users who updated and already have a key never see it.
        assertFalse(SetupChoices.shouldShowOnLaunch(setupCompleted = false, hasAnyApiKey = true))
    }

    @Test
    fun laterSkipsTestAndModel() {
        assertEquals(listOf(SetupStep.WELCOME, SetupStep.KEY, SetupStep.DONE), SetupChoices.steps(null))
        assertEquals(SetupStep.entries.toList(), SetupChoices.steps(SetupProvider.OPENAI))
    }

    @Test
    fun modelChoicesOnlyUseTheChosenProvidersKey() {
        for (provider in SetupProvider.entries) {
            val choices = SetupChoices.modelChoices(provider)
            assertTrue(choices.isNotEmpty())
            choices.forEach { assertEquals(provider.provider.id, it.preset.provider.id) }
        }
        assertEquals(
            listOf("gpt-6-luna", "gpt-6-sol"),
            SetupChoices.modelChoices(SetupProvider.OPENAI).map { it.preset.id }
        )
        assertEquals(
            listOf("gemini-3.1-flash-lite"),
            SetupChoices.modelChoices(SetupProvider.GOOGLE).map { it.preset.id }
        )
        assertEquals(3, SetupChoices.modelChoices(SetupProvider.OPENROUTER).size)
        assertTrue(SetupChoices.modelChoices(null).isEmpty())
    }

    @Test
    fun defaultIsLunaAndMeasuredCostsMatchTheDocs() {
        val openAi = SetupChoices.modelChoices(SetupProvider.OPENAI)
        assertEquals(ModelPresets.DEFAULT, openAi.first().preset)
        assertEquals(0.19, openAi.first { it.role == SetupModelRole.DEFAULT }.measuredUsdPer100, 1e-9)
        assertEquals(3.73, openAi.first { it.role == SetupModelRole.QUALITY }.measuredUsdPer100, 1e-9)
        assertEquals(0.57, SetupChoices.modelChoices(SetupProvider.GOOGLE).single().measuredUsdPer100, 1e-9)
    }

    @Test
    fun initialModelKeepsTheCurrentChoiceWhenOffered() {
        assertEquals("gpt-6-sol", SetupChoices.initialModel(SetupProvider.OPENAI, "gpt-6-sol")?.id)
        assertEquals("gpt-6-luna", SetupChoices.initialModel(SetupProvider.OPENAI, "gemini-3.1-flash-lite")?.id)
        assertEquals("gemini-3.1-flash-lite", SetupChoices.initialModel(SetupProvider.GOOGLE, "gpt-6-luna")?.id)
        assertEquals("or-gpt-6-luna", SetupChoices.initialModel(SetupProvider.OPENROUTER, "custom")?.id)
        assertNull(SetupChoices.initialModel(null, "gpt-6-luna"))
    }

    @Test
    fun estimatesComeFromCostEstimator() {
        val luna = ModelPresets.GPT_6_LUNA
        val estimate = SetupChoices.estimatePer100(luna, TranslationLanguages.CHINESE, strictMode = true)
        assertEquals(CostEstimator.estimateForPreset(luna, 100, TranslationLanguages.CHINESE, true), estimate)
        // Strict mode adds re-requests, so it can only cost more.
        assertTrue(estimate.usd!! > SetupChoices.estimatePer100(luna, TranslationLanguages.CHINESE, false).usd!!)
    }

    @Test
    fun pricingLanguageFollowsTheFirstReadLanguage() {
        assertEquals("zh", SetupChoices.pricingLanguage(emptySet()))
        assertEquals("ja", SetupChoices.pricingLanguage(setOf("ko", "ja")))
        assertEquals("ko", SetupChoices.pricingLanguage(setOf("ko")))
    }

    @Test
    fun providerLanguagesOnlyChangeCjk() {
        val current = setOf("en", "zh", "ja", "ko", "ru")
        assertEquals(setOf("en", "ru", "ja"), SetupChoices.providerLanguages(current, setOf("ja")))
        assertEquals(setOf("en", "zh", "ko"), SetupChoices.providerLanguages(setOf("en"), setOf("zh", "ko")))
        // Nothing picked: leave the setting alone.
        assertEquals(current, SetupChoices.providerLanguages(current, emptySet()))
    }

    @Test
    fun readAheadToggle() {
        assertEquals(0, SetupChoices.readAheadAfterToggle(false, 5, 2))
        assertEquals(5, SetupChoices.readAheadAfterToggle(true, 5, 2))
        assertEquals(2, SetupChoices.readAheadAfterToggle(true, 0, 2))
    }

    @Test
    fun keyPagesPointAtTheProviders() {
        assertEquals(LlmProviders.OPENAI, SetupProvider.OPENAI.provider)
        assertEquals(SetupProvider.GOOGLE, SetupProvider.forProviderId("google"))
        assertNull(SetupProvider.forProviderId("deepseek"))
        SetupProvider.entries.forEach { assertTrue(it.keyPageUrl.startsWith("https://")) }
    }
}
