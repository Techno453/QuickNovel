package com.lagradost.quicknovel.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPresetsTest {
    @Test
    fun idsAreUniqueAndTheDefaultIsLuna() {
        assertEquals(ModelPresets.ALL.size, ModelPresets.ALL.map { it.id }.toSet().size)
        assertEquals("gpt-6-luna", ModelPresets.DEFAULT.model)
        assertEquals(LlmConfig.DEFAULT_PRESET_ID, ModelPresets.DEFAULT.id)
        assertEquals(LlmConfig.DEFAULT_BASE_URL, LlmConfig.normalizeBaseUrl(ModelPresets.DEFAULT.baseUrl))
        assertEquals(mapOf("reasoning_effort" to "none"), ModelPresets.DEFAULT.extraBody)
    }

    @Test
    fun haikuIsNotShipped() {
        assertTrue(ModelPresets.ALL.none { "haiku" in it.model.lowercase() })
    }

    @Test
    fun temperatureIsNotSentToGeminiOrClaude() {
        assertFalse(ModelPresets.GEMINI_3_1_FLASH_LITE.sendTemperature)
        assertFalse(ModelPresets.OR_GEMINI_3_1_FLASH_LITE.sendTemperature)
        assertFalse(ModelPresets.CLAUDE_SONNET_5_5.sendTemperature)
        assertTrue(ModelPresets.GPT_6_LUNA.sendTemperature)
    }

    @Test
    fun openRouterMirrorsDisableReasoning() {
        val mirrors = ModelPresets.ALL.filter { it.provider == LlmProviders.OPENROUTER }
        assertEquals(5, mirrors.size)
        mirrors.forEach {
            assertTrue(it.model.contains('/'))
            assertEquals(mapOf("enabled" to false), it.extraBody["reasoning"])
        }
    }

    @Test
    fun matchFindsPresetsByEndpointAndModel() {
        assertEquals(ModelPresets.GPT_4O_MINI, ModelPresets.match("https://api.openai.com/v1/", "gpt-4o-mini"))
        assertEquals(ModelPresets.OR_DEEPSEEK_FLASH, ModelPresets.match("openrouter.ai/api/v1", "deepseek/deepseek-v4.1-flash"))
        assertNull(ModelPresets.match("https://api.openai.com/v1", "my-model"))
        assertNull(ModelPresets.match("http://localhost:1234/v1", "gpt-6-luna"))
    }

    @Test
    fun providerFromBaseUrl() {
        assertEquals(LlmProviders.OPENAI, LlmProviders.forBaseUrl("https://api.openai.com/v1"))
        assertEquals(LlmProviders.OPENROUTER, LlmProviders.forBaseUrl("openrouter.ai/api/v1/chat/completions"))
        assertEquals(LlmProviders.GOOGLE, LlmProviders.forBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/"))
        assertEquals(LlmProviders.CUSTOM, LlmProviders.forBaseUrl("http://192.168.1.5:1234/v1"))
    }

    @Test
    fun engineConfigFromPreset() {
        val c = ModelPresets.OR_GPT_6_SOL.toEngineConfig("secret")
        assertEquals("openai/gpt-6-sol", c.model)
        assertEquals("openrouter", c.providerId)
        assertEquals("X-Title", c.headers.keys.last())
        assertFalse(c.isCustom)
        assertFalse(c.toString().contains("secret"))
        assertTrue(c.sameModelAs(EngineConfig(baseUrl = "https://openrouter.ai/api/v1/", model = "openai/gpt-6-sol")))
    }

    @Test
    fun legacySettingsMigration() {
        // Fresh install: GPT-6 Luna.
        assertEquals(LlmConfig.MigratedSelection("gpt-6-luna", null, "openai"), LlmConfig.migrateLegacySelection(null, null, false))
        // Key set but model never chosen: keep the old implicit default.
        assertEquals("gpt-4o-mini", LlmConfig.migrateLegacySelection(null, null, true).presetId)
        // Explicit preset-equivalent choices map onto presets.
        assertEquals("gpt-4o", LlmConfig.migrateLegacySelection("gpt-4o", null, true).presetId)
        assertEquals("gpt-4o-mini", LlmConfig.migrateLegacySelection(null, "https://api.openai.com/v1", true).presetId)
        val or = LlmConfig.migrateLegacySelection("qwen/qwen3.8-flash", "https://openrouter.ai/api/v1", true)
        assertEquals(LlmConfig.MigratedSelection("or-qwen3.8-flash", null, "openrouter"), or)
        // Anything else stays custom; the key goes to the provider of the base URL.
        assertEquals(
            LlmConfig.MigratedSelection("custom", "deepseek-chat", "deepseek"),
            LlmConfig.migrateLegacySelection("deepseek-chat", "https://api.deepseek.com", true)
        )
        assertEquals(
            LlmConfig.MigratedSelection("custom", "gpt-4o-mini", "custom"),
            LlmConfig.migrateLegacySelection(null, "http://localhost:1234/v1", false)
        )
    }

    @Test
    fun extraBodyValidation() {
        assertEquals(emptyMap<String, Any?>(), LlmConfig.parseExtraBodyOrNull(""))
        assertNull(LlmConfig.parseExtraBodyOrNull("{oops"))
        assertNull(LlmConfig.parseExtraBodyOrNull("[1]"))
        assertEquals(mapOf("enable_thinking" to false), LlmConfig.parseExtraBodyOrNull("""{"enable_thinking":false}"""))
    }
}
