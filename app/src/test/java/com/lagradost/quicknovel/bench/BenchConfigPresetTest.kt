package com.lagradost.quicknovel.bench

import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.TranslationOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Bench config preset resolution and the engine adapter, offline (no API calls; always runs). */
class BenchConfigPresetTest {
    private val config = BenchConfig.loadDefault()

    @Test
    fun presetReferencesResolve() {
        val luna = config.model("gpt-6-luna")!!
        assertEquals("gpt-6-luna", luna.apiModel)
        assertEquals(0.10, luna.inputPerM, 0.0)
        assertEquals(0.50, luna.outputPerM, 0.0)
        assertEquals("none", luna.reasoningEffort)
        assertEquals(ModelSpec.OPENAI_KEY, luna.keyName)

        val gemini = config.model("google/gemini-3.1-flash-lite")!!
        assertEquals("OPENROUTER_API_KEY", gemini.keyName)
        assertEquals("https://openrouter.ai/api/v1", gemini.baseUrl)
        assertEquals(mapOf("enabled" to false), gemini.extraBody["reasoning"])
        assertTrue(gemini.extraBody.containsKey("temperature") && gemini.extraBody["temperature"] == null)
        assertEquals(false, gemini.sendTemperature)
        assertEquals("OpenRouter default route, estimate", gemini.priceNote)

        val direct = config.model("deepseek-flash")!!
        assertEquals("DEEPSEEK_API_KEY", direct.keyName)
        assertFalse(direct.enabled)
        assertEquals(mapOf("type" to "disabled"), direct.extraBody["thinking"])

        // Explicit entries still work and keep their own fields.
        val kimi = config.model("moonshotai/kimi-k2.6")!!
        assertNull(kimi.preset)
        assertEquals(0.65, kimi.inputPerM, 0.0)
        assertEquals("OPENROUTER_API_KEY", kimi.keyName)
    }

    @Test
    fun explicitFieldsOverrideThePreset() {
        val spec = ModelSpec(preset = "gpt-6-sol", id = "sol-low", reasoningEffort = "low", inputPerM = 1.0, extraBody = mapOf("verbosity" to "low")).resolved()
        assertEquals("sol-low", spec.id)
        assertEquals("gpt-6-sol", spec.apiModel)
        assertEquals("low", spec.reasoningEffort)
        assertEquals(1.0, spec.inputPerM, 0.0)
        assertEquals(10.0, spec.outputPerM, 0.0)
        assertEquals("low", spec.extraBody["verbosity"])
        assertEquals("none", spec.extraBody["reasoning_effort"])
    }

    @Test
    fun everyPresetCanBeReferenced() {
        ModelPresets.ALL.forEach { p ->
            val spec = ModelSpec(preset = p.id).resolved()
            assertEquals(p.model, spec.id)
            assertTrue(spec.keyName.endsWith("_API_KEY"))
        }
    }

    @Test
    fun adapterCallsTheStoryBibleEngineWithTheResolvedConfig() = runBlocking {
        val dir = Files.createTempDirectory("bench-test").toFile()
        try {
            val ledger = CostLedger(java.io.File(dir, "ledger.json"), 1.0, "test")
            val options = RunOptions({ "test-key" }, config.baseUrl, ledger, TranslationOptions(strictMode = false))
            val sample = Sample("zh-test", "zh", "test", "", "林风笑了。\n他走了。", 9)
            val bible = Bible(
                "zh-test", premise = "A cultivation story.",
                entries = listOf(BibleEntry("林风", "Lin Feng", "character", "male", "protagonist")),
            )
            for (id in listOf("gpt-6-luna", "google/gemini-3.1-flash-lite", "deepseek-flash", "moonshotai/kimi-k2.6")) {
                val model = config.model(id)!!
                val transport = FakeChatTransport()
                val out = BenchEngineAdapter(transport = transport).translate(sample, bible, model, options)
                assertNull(id, out.error)
                assertEquals(id, emptyList<String>(), transport.problems(model, config.baseUrl))
                val prompt = transport.requests.first().body.path("messages").last().path("content").asText()
                assertTrue("premise in prompt", prompt.contains("A cultivation story."))
                assertTrue("bible term in prompt", prompt.contains("Lin Feng"))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
