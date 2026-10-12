package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.batch.BatchAssembler
import com.lagradost.quicknovel.translator.batch.BatchChapterInput
import com.lagradost.quicknovel.translator.batch.BatchChapterProcessor
import com.lagradost.quicknovel.translator.batch.BatchCost
import com.lagradost.quicknovel.translator.batch.BatchCustomId
import com.lagradost.quicknovel.translator.batch.BatchOutputParser
import com.lagradost.quicknovel.translator.batch.BatchRequestBuilder
import com.lagradost.quicknovel.translator.batch.BatchStatus
import com.lagradost.quicknovel.translator.batch.BuiltChapter
import com.lagradost.quicknovel.translator.batch.ChunkOutcome
import com.lagradost.quicknovel.translator.batch.OpenAiBatchClient
import com.lagradost.quicknovel.translator.db.GlossaryTerm
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Overnight batch: request building, custom ids, output parsing, reassembly, post-processing, client. */
class BatchTest {
    private val mapper = jacksonObjectMapper()
    private val novelId = "Biqu/斗破|苍穹"

    // ---- custom_id ----

    @Test
    fun customIdRoundTripsEvenWithPipesInTheNovelId() {
        val id = BatchCustomId(novelId, 12, 3)
        assertEquals("Biqu/斗破|苍穹|12|3", id.toString())
        assertEquals(id, BatchCustomId.parse(id.toString()))
        assertNull(BatchCustomId.parse("no-pipes"))
        assertNull(BatchCustomId.parse("n|x|1"))
        assertNull(BatchCustomId.parse("|1|2"))
        assertNull(BatchCustomId.parse("n|1|-2"))
        assertNull(BatchCustomId.parse(null))
    }

    // ---- request building ----

    private val linDong = GlossaryTerm(
        id = 1, novelId = novelId, source = "林动", english = "Lin Dong", category = GlossaryTerm.CATEGORY_CHARACTER,
        gender = "male", locked = true,
    )

    private fun longParagraph(seed: String, n: Int) = (1..n).joinToString("") { "$seed$it。" }

    @Test
    fun buildsOneRequestPerChunkWithTheLiveSystemPromptAndNoPreviousTail() {
        val config = ModelPresets.GPT_6_LUNA.toEngineConfig("k")
        // Chapter 4 has two ~1,400-char paragraphs: two chunks at the 2,000-char Chinese chunk size.
        val ch4 = "<p>林动来了。" + longParagraph("山", 280) + "</p><p>" + longParagraph("水", 280) + "</p>"
        val ch3 = "<p>第一段。</p><p>第二段。</p>"
        val built = BatchRequestBuilder(config, "zh").build(
            novelId,
            listOf(BatchChapterInput(4, "Chapter 5", ch4), BatchChapterInput(3, "Chapter 4", ch3)),
            listOf(linDong),
            StoryContext(premise = "A boy trains.", previousEnglishTail = "MUST NOT APPEAR", previousChapterSummary = "NOR THIS"),
        )
        assertEquals(listOf(3, 4), built.chapters.map { it.chapterNum })
        assertEquals(listOf(1, 2), built.chapters.map { it.chunks.size })
        assertEquals(listOf("$novelId|3|0", "$novelId|4|0", "$novelId|4|1"), built.lines.map { it.customId })
        assertEquals(SourceHash.of(ch4), built.chapters[1].sourceHash)
        assertEquals(BatchRequestBuilder.chunksOf(ch4, "zh"), built.chapters[1].chunks)

        val jsonl = BatchRequestBuilder.toJsonl(built.lines)
        val lines = jsonl.trimEnd('\n').split('\n').map { mapper.readTree(it) }
        assertEquals(3, lines.size)
        for (line in lines) {
            assertEquals("POST", line.path("method").asText())
            assertEquals("/v1/chat/completions", line.path("url").asText())
            val body = line.path("body")
            assertEquals("gpt-6-luna", body.path("model").asText())
            assertEquals("none", body.path("reasoning_effort").asText())
            assertEquals(0.3, body.path("temperature").asDouble(), 0.0)
            val messages = body.path("messages")
            assertEquals("system", messages.path(0).path("role").asText())
            assertEquals(PromptTemplates.systemPrompt("zh"), messages.path(0).path("content").asText())
            val user = messages.path(1).path("content").asText()
            assertTrue(user.contains("A boy trains."))
            assertFalse(user.contains("MUST NOT APPEAR"))
            assertFalse(user.contains("NOR THIS"))
        }
        // The chunk with 林动 gets its bible entry; the second chunk of the chapter still gets him
        // as a character seen earlier in the chapter.
        assertTrue(lines[1].path("body").path("messages").path(1).path("content").asText().contains("Lin Dong"))
        assertTrue(lines[2].path("body").path("messages").path(1).path("content").asText().contains("Lin Dong"))
        assertFalse(lines[0].path("body").path("messages").path(1).path("content").asText().contains("Lin Dong"))
    }

    @Test
    fun geminiStylePresetsOmitTemperature() {
        val built = BatchRequestBuilder(ModelPresets.GEMINI_3_1_FLASH_LITE.toEngineConfig(), "zh")
            .build("n", listOf(BatchChapterInput(0, "c", "一。")), emptyList(), StoryContext())
        assertFalse(built.lines.single().body.containsKey("temperature"))
    }

    // ---- status mapping ----

    @Test
    fun openAiStatusesMapToLocalOnes() {
        assertEquals(BatchStatus.VALIDATING, BatchStatus.fromOpenAi("validating", false))
        assertEquals(BatchStatus.IN_PROGRESS, BatchStatus.fromOpenAi("in_progress", false))
        assertEquals(BatchStatus.FINALIZING, BatchStatus.fromOpenAi("finalizing", false))
        assertEquals(BatchStatus.CANCELLING, BatchStatus.fromOpenAi("cancelling", false))
        assertEquals(BatchStatus.PROCESSING, BatchStatus.fromOpenAi("completed", true))
        assertEquals(BatchStatus.FAILED, BatchStatus.fromOpenAi("completed", false))
        assertEquals(BatchStatus.PROCESSING, BatchStatus.fromOpenAi("expired", true))
        assertEquals(BatchStatus.EXPIRED, BatchStatus.fromOpenAi("expired", false))
        assertEquals(BatchStatus.PROCESSING, BatchStatus.fromOpenAi("cancelled", true))
        assertEquals(BatchStatus.CANCELLED, BatchStatus.fromOpenAi("cancelled", false))
        assertEquals(BatchStatus.FAILED, BatchStatus.fromOpenAi("failed", false))
        assertEquals(BatchStatus.IN_PROGRESS, BatchStatus.fromOpenAi("some_future_state", false))
        assertEquals(BatchStatus.DONE, BatchStatus.afterProcessing("completed"))
        assertEquals(BatchStatus.EXPIRED, BatchStatus.afterProcessing("expired"))
        assertEquals(BatchStatus.CANCELLED, BatchStatus.afterProcessing("cancelled"))
        assertTrue(BatchStatus.PROCESSING.isActive)
        assertFalse(BatchStatus.PREPARING.isActive)
        assertTrue(BatchStatus.DONE.isFinished)
        assertTrue(BatchStatus.PREPARING.name in BatchStatus.UNFINISHED_NAMES)
    }

    // ---- output parsing and reassembly (recorded output file) ----

    private fun outputLine(customId: String, content: String, finish: String = "stop", prompt: Int = 1000, completion: Int = 400) =
        mapper.writeValueAsString(
            mapOf(
                "id" to "batch_req_$customId", "custom_id" to customId,
                "response" to mapOf(
                    "status_code" to 200, "request_id" to "req_x",
                    "body" to mapOf(
                        "id" to "chatcmpl-x", "object" to "chat.completion", "model" to "gpt-6-luna",
                        "choices" to listOf(mapOf("index" to 0, "message" to mapOf("role" to "assistant", "content" to content), "finish_reason" to finish)),
                        "usage" to mapOf("prompt_tokens" to prompt, "completion_tokens" to completion, "total_tokens" to prompt + completion),
                    ),
                ),
                "error" to null,
            )
        )

    private val reply1 = "Chapter one, part one.\n===TERMS===\n[]\n===SUMMARY===\nPart one."
    private val reply2 = "Chapter one, part two.\n===TERMS===\n[]\n===SUMMARY===\nPart two."

    /** Chapter 0: 3 chunks (chunk 1 failed with HTTP 400, chunk 2 missing). Chapter 1: 1 chunk, cut off. Chapter 2: complete. */
    private val chapters = listOf(
        BuiltChapter(0, "c0", "h0", listOf("第一部分。", "第二部分。", "第三部分。")),
        BuiltChapter(1, "c1", "h1", listOf("第四部分。")),
        BuiltChapter(2, "c2", "h2", listOf("第五部分。")),
    )

    private val recordedOutput = listOf(
        outputLine("$novelId|2|0", reply2, prompt = 900, completion = 300),
        outputLine("$novelId|0|0", reply1),
        mapper.writeValueAsString(
            mapOf(
                "id" to "batch_req_y", "custom_id" to "$novelId|0|1",
                "response" to mapOf(
                    "status_code" to 400, "request_id" to "req_y",
                    "body" to mapOf("error" to mapOf("message" to "Invalid value for 'temperature'", "type" to "invalid_request_error")),
                ),
                "error" to null,
            )
        ),
        outputLine("$novelId|1|0", "Cut off mid", finish = "length", prompt = 800, completion = 4096),
        outputLine("other novel|0|0", reply1), // not ours: ignored
        "not json at all",
    ).joinToString("\n")

    private val recordedErrors = mapper.writeValueAsString(
        mapOf(
            "id" to "batch_req_z", "custom_id" to "$novelId|0|1", "response" to null,
            "error" to mapOf("code" to "batch_expired", "message" to "This request could not be executed before the completion window expired."),
        )
    )

    @Test
    fun parsesOutputAndErrorLines() {
        val results = BatchOutputParser.parse(recordedOutput) + BatchOutputParser.parse(recordedErrors)
        assertEquals(6, results.size)
        val ok = results.first { it.customId == "$novelId|0|0" }
        assertTrue(ok.succeeded)
        assertEquals(reply1, ok.content)
        assertEquals(TokenUsage(1000, 400), ok.usage)
        val bad = results.first { it.customId == "$novelId|0|1" && it.statusCode == 400 }
        assertFalse(bad.succeeded)
        assertEquals("Invalid value for 'temperature'", bad.error)
        val expired = results.last()
        assertNull(expired.statusCode)
        assertTrue(expired.error!!.contains("completion window"))
        assertTrue(BatchOutputParser.parse(null).isEmpty())
    }

    @Test
    fun reassemblesPerChapterInChunkOrderWithMissingAndFailedChunks() {
        val results = BatchOutputParser.parse(recordedOutput) + BatchOutputParser.parse(recordedErrors)
        val assembled = BatchAssembler.assemble(novelId, chapters, results)
        assertEquals(listOf(0, 1, 2), assembled.map { it.chapterNum })

        val c0 = assembled[0]
        assertEquals(ChunkOutcome.Ok(reply1, "stop"), c0.outcomes[0])
        assertTrue((c0.outcomes[1] as ChunkOutcome.Failed).reason.contains("temperature"))
        assertEquals(ChunkOutcome.Missing, c0.outcomes[2])
        assertEquals(1, c0.okCount)
        assertFalse(c0.complete)
        // Only the successful line carried usage (failed requests are not billed).
        assertEquals(TokenUsage(1000, 400), c0.usage)

        val c1 = assembled[1]
        assertTrue((c1.outcomes.single() as ChunkOutcome.Failed).reason.contains("length"))
        assertEquals(TokenUsage(800, 4096), c1.usage) // a cut-off reply is still billed

        assertTrue(assembled[2].complete)
        assertEquals(TokenUsage(900, 300), assembled[2].usage)
    }

    @Test
    fun batchCostIsHalfTheListPrice() {
        val luna = SpendPricing.pricingFor(ModelPresets.GPT_6_LUNA.toEngineConfig())
        val usage = TokenUsage(1000 + 800 + 900, 400 + 4096 + 300)
        val list = SpendPricing.usd(usage, luna)
        assertEquals(list * 0.5, BatchCost.actual(usage, luna), 1e-12)
        // 2,700 × $0.05/M + 4,796 × $0.25/M
        assertEquals(2700 * 0.05 / 1e6 + 4796 * 0.25 / 1e6, BatchCost.actual(usage, luna), 1e-12)
        assertEquals(0.0, BatchCost.actual(usage, null), 0.0)

        val config = ModelPresets.GPT_6_LUNA.toEngineConfig()
        val live = CostEstimator.estimateForConfig(config, 10, "zh", strictMode = false, bibleTerms = 5).usd!!
        assertEquals(live * 0.5, BatchCost.estimateForChapters(config, 10, "zh", 5)!!, 1e-12)
        assertNull(BatchCost.estimate(chapters, null, ModelPresets.GPT_6_LUNA.tokensPerChar, "zh", 0))
    }

    // ---- post-processing with the live path for failed chunks (fake HTTP) ----

    private inner class FakeLive(private val replies: List<String>) {
        val requests = CopyOnWriteArrayList<JsonNode>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val buffer = Buffer().also { request.body?.writeTo(it) }
            requests.add(mapper.readTree(buffer.readUtf8()))
            val content = replies.getOrElse(requests.size - 1) { replies.last() }
            val json = mapper.writeValueAsString(
                mapOf(
                    "choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")),
                    "usage" to mapOf("prompt_tokens" to 100, "completion_tokens" to 50),
                )
            )
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private fun engine(fake: FakeLive) = TranslationEngine(
        apiKey = "k", model = "gpt-6-luna", baseUrl = "http://live.test/v1", sourceLang = "zh", client = fake.client,
        usageSink = UsageSink { },
    )

    @Test
    fun missingAndFailedChunksAreRetranslatedLiveInOrder() = runBlocking {
        val fake = FakeLive(
            listOf(
                "LIVE part two.\n===TERMS===\n[]\n===SUMMARY===\nLive two.",
                "LIVE part three.\n===TERMS===\n[]\n===SUMMARY===\nLive three.",
            )
        )
        val results = BatchOutputParser.parse(recordedOutput) + BatchOutputParser.parse(recordedErrors)
        val c0 = BatchAssembler.assemble(novelId, chapters, results)[0]
        val processor = BatchChapterProcessor(engine(fake), TranslationOptions(strictMode = true))
        val outcome = processor.process(novelId, 0, chapters[0].chunks, c0.outcomes, emptyList(), StoryContext())
        val result = outcome.result
        assertNotNull(outcome.error, result)
        assertEquals(
            "<p>Chapter one, part one.</p>\n<p>LIVE part two.</p>\n<p>LIVE part three.</p>",
            result!!.englishText,
        )
        assertEquals(2, outcome.liveChunks)
        assertEquals(2, fake.requests.size)
        // The live request for chunk 2 sees chunk 1's English as preceding context.
        val user = fake.requests[1].path("messages").path(1).path("content").asText()
        assertTrue(user.contains("LIVE part two."))
        assertEquals("Part one. Live two. Live three.", result.chapterSummary)
    }

    @Test
    fun withoutLiveCallsAnIncompleteChapterIsLeftForTheNormalPath() = runBlocking {
        val fake = FakeLive(listOf("unused"))
        val results = BatchOutputParser.parse(recordedOutput)
        val c0 = BatchAssembler.assemble(novelId, chapters, results)[0]
        val processor = BatchChapterProcessor(engine(fake), TranslationOptions(), allowLive = { false })
        val outcome = processor.process(novelId, 0, chapters[0].chunks, c0.outcomes, emptyList(), StoryContext())
        assertNull(outcome.result)
        assertTrue(outcome.error!!.contains("passage 2"))
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun aCompleteChapterNeedsNoLiveCallAndFoldsDiscoveredTermsIntoTheBible() = runBlocking {
        val fake = FakeLive(listOf("unused"))
        val reply = "Xiao Yan stood up.\n===TERMS===\n" +
            """[{"source":"萧炎","english":"Xiao Yan","category":"character","gender":"male","confidence":0.9}]""" +
            "\n===SUMMARY===\nXiao Yan stands.\nCharacters: 萧炎"
        val processor = BatchChapterProcessor(engine(fake), TranslationOptions())
        val outcome = processor.process(
            novelId, 5, listOf("萧炎站了起来。"), listOf(ChunkOutcome.Ok(reply, "stop")), emptyList(), StoryContext(),
        )
        assertEquals(0, fake.requests.size)
        val result = outcome.result!!
        assertEquals("<p>Xiao Yan stood up.</p>", result.englishText)
        assertEquals(listOf("萧炎"), result.autoAddedTerms.map { it.source })
        assertEquals(listOf("萧炎"), result.charactersPresent)
    }

    @Test
    fun aChunkFailingTheChecksGetsTheStrictRetry() = runBlocking {
        val fake = FakeLive(listOf("Lin Dong came.\n===TERMS===\n[]\n===SUMMARY===\nHe came."))
        val processor = BatchChapterProcessor(engine(fake), TranslationOptions(strictMode = true))
        val outcome = processor.process(
            novelId, 0, listOf("林动来了。"),
            listOf(ChunkOutcome.Ok("Lin Tung came.\n===TERMS===\n[]\n===SUMMARY===\nHe came.", "stop")),
            listOf(linDong), StoryContext(),
        )
        assertEquals(1, fake.requests.size)
        assertEquals(1, outcome.strictRetries)
        assertEquals("<p>Lin Dong came.</p>", outcome.result!!.englishText)
        assertEquals(0, outcome.result!!.glossaryViolations)
        assertEquals(1, outcome.result!!.retries)
    }

    // ---- Batch API client (fake HTTP) ----

    private inner class FakeOpenAi {
        val requests = CopyOnWriteArrayList<Pair<Request, String>>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            requests.add(request to body)
            val path = request.url.encodedPath
            val (code, json) = when {
                path == "/v1/files" -> 200 to """{"id":"file-in","object":"file","purpose":"batch"}"""
                path == "/v1/batches" -> 200 to batchJson("validating")
                path == "/v1/batches/batch_1" -> 200 to batchJson("completed", output = "file-out")
                path == "/v1/batches/batch_1/cancel" -> 200 to batchJson("cancelling")
                path == "/v1/files/file-out/content" -> 200 to recordedOutput
                path == "/v1/batches/batch_bad" -> 401 to """{"error":{"message":"Incorrect API key provided"}}"""
                else -> 404 to """{"error":{"message":"not found"}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }).build()

        private fun batchJson(status: String, output: String? = null) = mapper.writeValueAsString(
            mapOf(
                "id" to "batch_1", "object" to "batch", "endpoint" to "/v1/chat/completions", "status" to status,
                "input_file_id" to "file-in", "output_file_id" to output, "error_file_id" to null,
                "request_counts" to mapOf("total" to 6, "completed" to 4, "failed" to 2),
                "errors" to null,
            )
        )
    }

    @Test
    fun clientUploadsCreatesPollsDownloadsAndCancels() = runBlocking {
        val fake = FakeOpenAi()
        val client = OpenAiBatchClient("sk-test", "https://api.openai.com/v1", fake.client)
        assertEquals("file-in", client.uploadJsonl("{}\n", "x.jsonl"))
        val upload = fake.requests[0]
        assertEquals("Bearer sk-test", upload.first.header("Authorization"))
        assertTrue(upload.second.contains("name=\"purpose\"") && upload.second.contains("batch"))
        assertTrue(upload.second.contains("filename=\"x.jsonl\""))

        val created = client.createBatch("file-in", mapOf("app" to "QuickNovel"))
        assertEquals("validating", created.status)
        val createBody = mapper.readTree(fake.requests[1].second)
        assertEquals("/v1/chat/completions", createBody.path("endpoint").asText())
        assertEquals("24h", createBody.path("completion_window").asText())
        assertEquals("file-in", createBody.path("input_file_id").asText())

        val polled = client.getBatch("batch_1")
        assertEquals("completed", polled.status)
        assertEquals("file-out", polled.outputFileId)
        assertNull(polled.errorFileId)
        assertEquals(6, polled.total)
        assertEquals(4, polled.completed)
        assertEquals(2, polled.failed)
        assertEquals(BatchStatus.PROCESSING, BatchStatus.fromOpenAi(polled.status, polled.outputFileId != null))

        assertEquals(recordedOutput, client.downloadFile("file-out"))
        assertEquals("cancelling", client.cancelBatch("batch_1").status)
        assertEquals("POST", fake.requests.last().first.method)

        try {
            client.getBatch("batch_bad")
            fail("expected an error")
        } catch (e: TranslationApiException) {
            assertTrue(e.message!!.startsWith("Invalid API key"))
        }
    }

    @Test
    fun onlyOpenAiItselfHasTheBatchApi() {
        assertTrue(OpenAiBatchClient.supports("https://api.openai.com/v1"))
        assertTrue(OpenAiBatchClient.supports(ModelPresets.GPT_6_SOL.baseUrl))
        assertFalse(OpenAiBatchClient.supports(ModelPresets.OR_GPT_6_LUNA.baseUrl))
        assertFalse(OpenAiBatchClient.supports(ModelPresets.GEMINI_3_1_FLASH_LITE.baseUrl))
        assertFalse(OpenAiBatchClient.supports("http://localhost:1234/v1"))
    }

    @Test
    fun failedBatchErrorsAreReadable() {
        val info = OpenAiBatchClient.parseBatch(
            mapper.readTree(
                """{"id":"b","status":"failed","errors":{"object":"list","data":[{"code":"token_limit_exceeded","message":"Enqueued token limit reached"}]}}"""
            )
        )
        assertEquals("token_limit_exceeded: Enqueued token limit reached", info.errors)
        assertEquals(BatchStatus.FAILED, BatchStatus.fromOpenAi(info.status, false))
    }
}
