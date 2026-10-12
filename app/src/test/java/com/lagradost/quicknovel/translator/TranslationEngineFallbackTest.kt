package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Content-filter / refusal fallback and per-config request fields, against a scripted fake endpoint. */
class TranslationEngineFallbackTest {
    private val mapper = jacksonObjectMapper()

    private data class Reply(val code: Int = 200, val content: String = "", val finishReason: String = "stop", val errorBody: String? = null)

    /** Routes by host: each host has its own reply script; records every request. */
    private inner class FakeHosts(private val scripts: Map<String, List<Reply>>) {
        val requests = CopyOnWriteArrayList<Pair<Request, JsonNode>>()

        fun count(host: String) = requests.count { it.first.url.host == host }

        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body?.writeTo(it) }
            val body = mapper.readTree(buffer.readUtf8())
            val host = request.url.host
            val index = count(host)
            requests += request to body
            val script = scripts.getValue(host)
            val reply = script.getOrElse(index) { script.last() }
            val json = reply.errorBody ?: mapper.writeValueAsString(
                mapOf("choices" to listOf(mapOf("message" to mapOf("content" to reply.content), "finish_reason" to reply.finishReason)))
            )
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(reply.code)
                .message("x")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private val source = "第一段。\n第二段。\n第三段。\n第四段。"
    private val good = "One.\nTwo.\nThree.\nFour.\n===TERMS===\n[]\n===SUMMARY===\nFour paragraphs."
    private val refusal = "I can't help translate this, as the novel is protected by copyright."

    private val primary = EngineConfig(baseUrl = "http://primary.test/v1", model = "primary-model", apiKey = "k1")
    private val fallback = EngineConfig(
        baseUrl = "http://fallback.test/v1", model = "fallback-model", apiKey = "k2",
        extraBody = mapOf("reasoning_effort" to "minimal"), sendTemperature = false,
        headers = mapOf("X-Title" to "QuickNovel"),
    )

    private fun engine(fake: FakeHosts, withFallback: Boolean = true) =
        TranslationEngine(primary, "zh", fake.client, if (withFallback) fallback else null)

    @Test
    fun deepSeekContentFilterErrorUsesFallback() = runBlocking {
        val fake = FakeHosts(
            mapOf(
                "primary.test" to listOf(Reply(400, errorBody = """{"error":{"message":"Content Exists Risk","type":"invalid_request_error"}}""")),
                "fallback.test" to listOf(Reply(content = good)),
            )
        )
        val result = engine(fake).translateChapter(source, "n", 0, emptyList())
        assertEquals(1, fake.count("primary.test")) // not retried: content-filter errors are final
        assertEquals(1, fake.count("fallback.test"))
        assertEquals(1, result.fallbackChunks)
        assertTrue(result.usedFallback)
        assertEquals("fallback-model", result.modelUsed)
        assertTrue(result.englishText.contains("<p>Four.</p>"))
    }

    @Test
    fun fallbackRequestUsesItsOwnConfig() = runBlocking {
        val fake = FakeHosts(
            mapOf(
                "primary.test" to listOf(Reply(content = refusal)),
                "fallback.test" to listOf(Reply(content = good)),
            )
        )
        engine(fake).translateChapter(source, "n", 0, emptyList())
        val (primaryReq, primaryBody) = fake.requests.first { it.first.url.host == "primary.test" }
        val (fallbackReq, fallbackBody) = fake.requests.first { it.first.url.host == "fallback.test" }
        assertEquals("Bearer k1", primaryReq.header("Authorization"))
        assertTrue(primaryBody.has("temperature"))
        assertEquals("Bearer k2", fallbackReq.header("Authorization"))
        assertEquals("QuickNovel", fallbackReq.header("X-Title"))
        assertEquals("fallback-model", fallbackBody.path("model").asText())
        assertEquals("minimal", fallbackBody.path("reasoning_effort").asText())
        assertFalse(fallbackBody.has("temperature"))
    }

    @Test
    fun refusalTriggersFallback() = runBlocking {
        val fake = FakeHosts(
            mapOf(
                "primary.test" to listOf(Reply(content = refusal)),
                "fallback.test" to listOf(Reply(content = good)),
            )
        )
        val result = engine(fake).translateChapter(source, "n", 0, emptyList())
        assertEquals(1, fake.count("primary.test")) // no strict retry with the refusing model
        assertEquals(1, result.fallbackChunks)
        assertFalse(result.englishText.contains("copyright"))
    }

    @Test
    fun contentFilterFinishReasonTriggersFallback() = runBlocking {
        val fake = FakeHosts(
            mapOf(
                "primary.test" to listOf(Reply(content = "One.", finishReason = "content_filter")),
                "fallback.test" to listOf(Reply(content = good)),
            )
        )
        val result = engine(fake).translateChapter(source, "n", 0, emptyList())
        assertEquals(1, result.fallbackChunks)
    }

    @Test
    fun withoutFallbackARefusalIsKeptAndAFilterErrorFails() = runBlocking {
        val refusing = FakeHosts(mapOf("primary.test" to listOf(Reply(content = refusal))))
        val kept = engine(refusing, withFallback = false).translateChapter(source, "n", 0, emptyList())
        assertEquals(0, kept.fallbackChunks)
        assertEquals("primary-model", kept.modelUsed)

        val blocked = FakeHosts(
            mapOf("primary.test" to listOf(Reply(400, errorBody = """{"error":{"code":"data_inspection_failed","message":"Input data may contain inappropriate content."}}""")))
        )
        try {
            engine(blocked, withFallback = false).translateChapter(source, "n", 0, emptyList())
            fail("expected a content-filter error")
        } catch (e: TranslationApiException) {
            assertTrue(e.contentFiltered)
            assertFalse(e.retryable)
        }
        assertEquals(1, blocked.count("primary.test"))
    }

    @Test
    fun failingFallbackKeepsThePrimaryOutcome() = runBlocking {
        val fake = FakeHosts(
            mapOf(
                "primary.test" to listOf(Reply(content = refusal)),
                "fallback.test" to listOf(Reply(401, errorBody = """{"error":{"message":"bad key"}}""")),
            )
        )
        val result = engine(fake).translateChapter(source, "n", 0, emptyList(), options = TranslationOptions(strictMode = false))
        assertEquals(0, result.fallbackChunks)
        assertTrue(result.englishText.contains("copyright"))
    }

    @Test
    fun sameModelFallbackIsIgnored() = runBlocking {
        val fake = FakeHosts(mapOf("primary.test" to listOf(Reply(content = refusal))))
        val engine = TranslationEngine(primary, "zh", fake.client, primary.copy(apiKey = "other"))
        assertEquals(null, engine.fallbackModelId)
    }

    @Test
    fun configWithoutTemperatureNeverSendsIt() = runBlocking {
        val fake = FakeHosts(mapOf("fallback.test" to listOf(Reply(content = good))))
        TranslationEngine(fallback, "zh", fake.client).translateChapter(source, "n", 0, emptyList())
        assertTrue(fake.requests.none { it.second.has("temperature") })
    }

    @Test
    fun contentFilterErrorDetection() {
        fun filtered(code: Int, body: String) = TranslationEngine.isContentFilterError(code, body)
        assertTrue(filtered(400, """{"error":{"message":"Content Exists Risk"}}"""))
        assertTrue(filtered(400, """{"error":{"code":"data_inspection_failed","message":"Input data may contain inappropriate content."}}"""))
        assertTrue(filtered(400, """{"error":{"message":"The request was rejected because it was considered high risk","type":"content_filter"}}"""))
        assertTrue(filtered(400, """{"error":{"code":"1301","message":"系统检测到输入或生成内容可能包含不安全或敏感内容"}}"""))
        assertTrue(filtered(400, """{"error":{"code":1301,"message":"unsafe"}}"""))
        assertTrue(filtered(400, """[{"error":{"code":400,"message":"Request blocked: PROHIBITED_CONTENT","status":"INVALID_ARGUMENT"}}]"""))
        assertFalse(filtered(400, """{"error":{"code":"13010","message":"other"}}"""))
        assertFalse(filtered(400, """{"error":{"message":"Unsupported value: 'temperature'"}}"""))
        assertFalse(filtered(401, """{"error":{"message":"content_filter"}}"""))
        assertFalse(filtered(500, """{"error":{"message":"Content Exists Risk"}}"""))
    }

    @Test
    fun contentFilterFinishDetection() {
        assertTrue(TranslationEngine.isContentFilterFinish("content_filter"))
        assertTrue(TranslationEngine.isContentFilterFinish("sensitive"))
        assertFalse(TranslationEngine.isContentFilterFinish("stop"))
        assertFalse(TranslationEngine.isContentFilterFinish(null))
        assertFalse(TranslationEngine.isContentFilterFinish("eos"))
        assertTrue(TranslationEngine.isContentFilterFinish("other", gemini = true))
        assertFalse(TranslationEngine.isContentFilterFinish("length", gemini = true))
    }

    @Test
    fun refusalDetection() {
        assertTrue(QualityChecks.looksLikeRefusal(source, refusal))
        assertTrue(QualityChecks.looksLikeRefusal(source, "Sorry, I am unable to provide a full translation. Summary: ..."))
        // Low fidelity alone (merged paragraphs) is not a refusal.
        assertFalse(QualityChecks.looksLikeRefusal(source, "One. Two. Three. Four."))
        // A faithful translation that mentions copyright is not a refusal.
        assertFalse(QualityChecks.looksLikeRefusal(source, "I can't believe it.\nTwo.\nThree.\nFour."))
        // Phrase after the first 300 characters does not count.
        assertFalse(QualityChecks.looksLikeRefusal(source, "x".repeat(320) + " I cannot"))
    }
}
