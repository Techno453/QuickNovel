package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** Usage reporting from the engine, pricing and the pure parts of the spend ledger. */
class SpendLedgerTest {
    private val mapper = jacksonObjectMapper()

    private fun client(content: String, usage: Map<String, Int>?) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val body = buildMap<String, Any> {
            put("choices", listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")))
            if (usage != null) put("usage", usage)
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(mapper.writeValueAsString(body).toResponseBody("application/json".toMediaType())).build()
    }).build()

    private class RecordingSink : UsageSink {
        val events = CopyOnWriteArrayList<UsageEvent>()
        override suspend fun record(event: UsageEvent) {
            events += event
        }
    }

    private val good = "One.\nTwo.\n===TERMS===\n[]\n===SUMMARY===\nTwo lines."

    @Test
    fun engineReportsUsageWithTheTagAndPresetPrice() = runBlocking {
        val sink = RecordingSink()
        val config = ModelPresets.GPT_6_LUNA.toEngineConfig("k")
        val engine = TranslationEngine(
            apiKey = config.apiKey, model = config.model, baseUrl = config.baseUrl, sourceLang = "zh",
            client = client(good, mapOf("prompt_tokens" to 1_000_000, "completion_tokens" to 200_000)),
            pricing = SpendPricing.pricingFor(config), providerId = config.providerId, usageSink = sink,
        )
        val meter = SpendMeter()
        withContext(SpendTag(SpendKind.READ_AHEAD, "novel", 7, meter = meter)) {
            engine.translateChapter("第一段。\n第二段。", "novel", 7, emptyList(), options = TranslationOptions(strictMode = false))
        }
        assertEquals(1, sink.events.size)
        val e = sink.events.single()
        assertEquals(SpendKind.READ_AHEAD, e.kind)
        assertEquals("novel", e.novelId)
        assertEquals(7, e.chapterNum)
        assertEquals("openai", e.providerId)
        assertEquals("gpt-6-luna", e.model)
        assertEquals(1_000_000, e.promptTokens)
        assertEquals(200_000, e.completionTokens)
        // 1M × $0.10 + 0.2M × $0.50
        assertEquals(0.20, e.usd, 1e-9)
        assertTrue(e.priced)
        assertEquals(0.20, meter.usd, 1e-9)
    }

    @Test
    fun untaggedCallsAreOtherAndUnknownModelsAreUnpriced() = runBlocking {
        val sink = RecordingSink()
        val engine = TranslationEngine(
            apiKey = "k", model = "my-local-model", baseUrl = "http://localhost.test/v1",
            client = client("OK", mapOf("prompt_tokens" to 10, "completion_tokens" to 2)), usageSink = sink,
        )
        assertNull(engine.verify())
        val e = sink.events.single()
        assertEquals(SpendKind.OTHER, e.kind)
        assertNull(e.novelId)
        assertFalse(e.priced)
        assertEquals(0.0, e.usd, 0.0)
        assertEquals(10, e.promptTokens)
    }

    @Test
    fun missingUsageIsRecordedAsUnpriced() = runBlocking {
        val sink = RecordingSink()
        val engine = TranslationEngine(
            apiKey = "k", model = "gpt-6-luna", client = client("OK", null), usageSink = sink,
        )
        engine.verify()
        val e = sink.events.single()
        assertFalse(e.priced)
        assertEquals(0, e.promptTokens)
    }

    @Test
    fun pricingPrefersThePresetThenThePriceTable() {
        val preset = ModelPresets.GEMINI_3_1_FLASH_LITE.toEngineConfig()
        assertEquals(0.25, SpendPricing.pricingFor(preset)!!.inputPerMillion, 0.0)
        val custom = EngineConfig(baseUrl = "https://example.test/v1", model = "openai/gpt-6-sol")
        assertEquals(2.0, SpendPricing.pricingFor(custom)!!.inputPerMillion, 0.0)
        assertNull(SpendPricing.pricingFor(EngineConfig(baseUrl = "http://x.test", model = "llama-local")))
        val usage = TokenUsage(2_000_000, 1_000_000)
        assertEquals(1.5, SpendPricing.usd(usage, CostEstimator.ModelPricing(0.25, 1.0)), 1e-9)
        assertEquals(0.75, SpendPricing.usd(usage, CostEstimator.ModelPricing(0.25, 1.0), SpendPricing.BATCH_DISCOUNT), 1e-9)
        assertEquals(0.0, SpendPricing.usd(usage, null), 0.0)
    }

    @Test
    fun periodsStartAtLocalMidnightAndTheFirstOfTheMonth() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_791_720_000_000L // 2026-10-11T12:00:00Z
        assertEquals(1_791_676_800_000L, SpendPeriods.startOfDay(now, utc)) // 2026-10-11T00:00Z
        assertEquals(1_790_812_800_000L, SpendPeriods.startOfMonth(now, utc)) // 2026-10-01T00:00Z
    }

    @Test
    fun breakdownKeepsTheTopTenAndSumsTheRest() {
        val rows = (1..13).map { SpendRow("n$it", it.toDouble(), 1) }
        val out = SpendBreakdown.topWithRest(rows, 10)
        assertEquals(11, out.size)
        assertEquals("n13", out.first().name)
        assertEquals(1.0 + 2.0 + 3.0, out.last().usd, 1e-9)
        assertEquals(3, out.last().calls)
        assertTrue(out.last().name.contains("(3)"))
        assertEquals(3, SpendBreakdown.topWithRest(rows.take(3), 10).size)
    }

    @Test
    fun meterAddsInMicroDollars() {
        val m = SpendMeter()
        repeat(1000) { m.add(0.000123) }
        m.add(-5.0) // ignored
        assertEquals(0.123, m.usd, 1e-9)
    }
}
