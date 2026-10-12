package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.ModelPresets
import com.lagradost.quicknovel.translator.PromptProfiles
import com.lagradost.quicknovel.translator.QualityChecks
import com.lagradost.quicknovel.translator.SpendPricing
import com.lagradost.quicknovel.translator.StoryContext
import com.lagradost.quicknovel.translator.TokenUsage
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationOptions
import com.lagradost.quicknovel.translator.UsageSink
import com.lagradost.quicknovel.translator.batch.BatchAssembler
import com.lagradost.quicknovel.translator.batch.BatchChapterInput
import com.lagradost.quicknovel.translator.batch.BatchChapterProcessor
import com.lagradost.quicknovel.translator.batch.BatchCost
import com.lagradost.quicknovel.translator.batch.BatchCustomId
import com.lagradost.quicknovel.translator.batch.BatchLine
import com.lagradost.quicknovel.translator.batch.BatchOutputParser
import com.lagradost.quicknovel.translator.batch.BatchRequestBuilder
import com.lagradost.quicknovel.translator.batch.BatchStatus
import com.lagradost.quicknovel.translator.batch.ChunkOutcome
import com.lagradost.quicknovel.translator.batch.OpenAiBatchClient
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the overnight batch flow against the real OpenAI Batch API.
 * Skipped unless `-Dlive.batch=1` (or env `LIVE_BATCH=1`); needs `OPENAI_API_KEY` in the
 * environment or bench.local.properties, and the doupo sample in
 * app/build/translation-bench/cache/ (run the translation bench once to fetch it).
 *
 * Spend is a fraction of a cent (gpt-6-luna at batch price):
 * - batch A: one ~600-char chapter (real custom_id with a non-ASCII novel id, max_completion_tokens
 *   1,200) plus one request with an invalid temperature, which comes back in the error file;
 * - batch B: one tiny request whose custom_id is a long Japanese novel id (custom_id limits);
 * - batch C: one tiny request, cancelled right after creation.
 * Every batch it creates is cancelled if it is still running when the test ends.
 *
 * Raw API responses (no key; the Authorization header is never written) go to
 * app/build/batch-live/<timestamp>/.
 */
class BatchLiveTest {
    private val mapper = jacksonObjectMapper()

    private val enabled: Boolean
        get() = (System.getProperty("live.batch") ?: System.getenv("LIVE_BATCH"))?.trim() in setOf("1", "true", "yes")

    private val preset = ModelPresets.GPT_6_LUNA
    private val novelId = "Ixdzs/斗破苍穹|live"

    private val bible = listOf(
        GlossaryTerm(
            id = 1, novelId = novelId, source = "萧炎", english = "Xiao Yan",
            category = GlossaryTerm.CATEGORY_CHARACTER, gender = "male", locked = true,
        ),
        GlossaryTerm(id = 2, novelId = novelId, source = "魔石碑", english = "Magic Stone Stele", locked = true),
    )

    private fun log(msg: String) = println("[batch-live] " + Secrets.scrub(msg))

    /** A ~600-char slice of the doupo sample, whole paragraphs, as <p> HTML. */
    private fun sampleSlice(root: File): String? {
        var dir: File? = root
        var file: File? = null
        while (dir != null && file == null) {
            file = File(dir, "app/build/translation-bench/cache/sample-zh-doupo.json").takeIf { it.isFile }
            dir = dir.parentFile
        }
        file ?: return null
        val text = mapper.readTree(file).path("text").asText("")
        val paragraphs = mutableListOf<String>()
        var chars = 0
        for (line in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (chars >= 550) break
            paragraphs += line
            chars += line.length
        }
        return paragraphs.takeIf { it.isNotEmpty() }?.joinToString("") { "<p>$it</p>" }
    }

    private fun recordingClient(dir: File): OkHttpClient {
        var n = 0
        return TranslationEngine.defaultClient.newBuilder().addInterceptor(Interceptor { chain ->
            val request: Request = chain.request()
            val response = chain.proceed(request)
            val body = response.peekBody(4L * 1024 * 1024).string()
            synchronized(this) {
                n++
                val name = "%03d-%s-%s.txt".format(n, request.method, request.url.encodedPath.trim('/').replace('/', '_'))
                File(dir, name).writeText(
                    Secrets.scrub("${request.method} ${request.url}\nHTTP ${response.code}\n\n$body"), Charsets.UTF_8,
                )
            }
            response
        }).build()
    }

    private val terminal = setOf("completed", "failed", "expired", "cancelled")

    @Test
    fun overnightBatchAgainstTheRealApi() = runBlocking {
        assumeTrue("Live batch test disabled (use -Dlive.batch=1 or LIVE_BATCH=1)", enabled)
        val env = BenchEnv.load()
        val key = env.key(ModelSpec.OPENAI_KEY)
        assumeTrue("No ${ModelSpec.OPENAI_KEY} (env or bench.local.properties)", key != null)
        val source = sampleSlice(env.projectRoot)
        assumeTrue("Sample app/build/translation-bench/cache/sample-zh-doupo.json not found", source != null)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(env.projectRoot, "app/build/batch-live/$stamp").apply { mkdirs() }
        val client = OpenAiBatchClient(key!!, preset.baseUrl, recordingClient(outDir))
        val pricing = SpendPricing.pricingFor(preset.toEngineConfig())
        log("key: ${env.keyDescription(ModelSpec.OPENAI_KEY)}; raw responses in ${outDir.path}")

        // ---- batch A: the chapter + one request that must fail ----
        val extra = preset.extraBody + ("max_completion_tokens" to 1200)
        val builder = BatchRequestBuilder(preset.model, "zh", PromptProfiles.DEFAULT_ID, extra, sendTemperature = true)
        val built = builder.build(
            novelId,
            listOf(BatchChapterInput(0, "第一章", source!!), BatchChapterInput(1, "第二章", "<p>测试。</p>")),
            bible,
            StoryContext(premise = "A fallen genius trains to regain his strength."),
        )
        assertEquals(listOf(1, 1), built.chapters.map { it.chunks.size })
        val badId = BatchCustomId(novelId, 1, 0).toString()
        val linesA = built.lines.map { if (it.customId == badId) it.copy(body = it.body + ("temperature" to 5.0)) else it }
        val jsonlA = BatchRequestBuilder.toJsonl(linesA)
        File(outDir, "input-A.jsonl").writeText(jsonlA, Charsets.UTF_8)
        val chunk0 = built.chapters[0].chunks.single()
        log("chapter chunk: ${chunk0.length} chars, ${QualityChecks.countParagraphs(chunk0)} paragraphs; ids ${linesA.map { it.customId }}")

        // ---- batch B: long non-ASCII custom_id ----
        val longNovelId = "Syosetu/" + "異世界に転生した俺は最強の魔法使いになって世界を救う旅に出る".repeat(8) + "|x"
        val probeId = BatchCustomId(longNovelId, 0, 0).toString()
        val tiny = { TranslationEngine.buildChatBody(preset.model, null, "Reply with the single word OK.", 0.0, true, preset.extraBody + ("max_completion_tokens" to 16)) }
        val jsonlB = BatchRequestBuilder.toJsonl(listOf(BatchLine(probeId, tiny())))
        log("probe custom_id: ${probeId.length} chars, ${probeId.toByteArray().size} UTF-8 bytes")

        // ---- batch C: cancelled at once ----
        val jsonlC = BatchRequestBuilder.toJsonl(listOf(BatchLine(BatchCustomId(novelId, 9, 0).toString(), tiny())))

        val created = mutableListOf<String>()
        val finals = LinkedHashMap<String, OpenAiBatchClient.BatchInfo>()
        try {
            val fileA = client.uploadJsonl(jsonlA, "quicknovel-live-A.jsonl")
            val batchA = client.createBatch(fileA, mapOf("app" to "QuickNovel", "novel" to "斗破苍穹", "job" to "live-A"))
            created += batchA.id
            log("A: file $fileA, batch ${batchA.id} ${batchA.status}")
            val fileB = client.uploadJsonl(jsonlB, "quicknovel-live-B.jsonl")
            val batchB = client.createBatch(fileB, mapOf("app" to "QuickNovel", "job" to "live-B"))
            created += batchB.id
            log("B: file $fileB, batch ${batchB.id} ${batchB.status}")
            val fileC = client.uploadJsonl(jsonlC, "quicknovel-live-C.jsonl")
            val batchC = client.createBatch(fileC, mapOf("app" to "QuickNovel", "job" to "live-C"))
            created += batchC.id
            val cancelled = client.cancelBatch(batchC.id)
            log("C: file $fileC, batch ${batchC.id} ${batchC.status} → cancel returned ${cancelled.status}")
            assertTrue(cancelled.status in setOf("cancelling", "cancelled"))

            // ---- poll every 20 s, at most 25 minutes ----
            val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(25)
            while (true) {
                for (id in created) {
                    if (id in finals) continue
                    val info = client.getBatch(id)
                    log("poll $id: ${info.status} ${info.completed}/${info.total} failed ${info.failed}")
                    if (info.status in terminal) finals[id] = info
                }
                if (finals.size == created.size || System.currentTimeMillis() > deadline) break
                delay(20_000)
            }
            for (id in created) if (id !in finals) log("$id did not finish within 25 minutes")

            // ---- C: cancelled, nothing ran ----
            val c = finals[batchC.id]
            assertNotNull("batch C did not reach a final state", c)
            assertEquals("cancelled", c!!.status)
            log("C final: cancelled, completed ${c.completed}, usage ${c.usage}, output ${c.outputFileId}, error ${c.errorFileId}")
            assertEquals(BatchStatus.CANCELLED, BatchStatus.fromOpenAi(c.status, c.outputFileId != null, c.errorFileId != null))

            // ---- B: long non-ASCII custom_id ----
            val b = finals[batchB.id]
            assertNotNull("batch B did not reach a final state", b)
            log("B final: ${b!!.status}, errors ${b.errors}, usage ${b.usage}")
            var usageB = TokenUsage.ZERO
            if (b.status == "completed") {
                val out = BatchOutputParser.parse(b.outputFileId?.let { client.downloadFile(it) })
                File(outDir, "output-B.jsonl").writeText(out.joinToString("\n") { it.toString() }, Charsets.UTF_8)
                val line = out.single()
                assertEquals(probeId, line.customId)
                assertEquals(BatchCustomId(longNovelId, 0, 0), BatchCustomId.parse(line.customId))
                usageB = line.usage ?: TokenUsage.ZERO
                log("B: long custom_id round-tripped; reply '${line.content}'")
            }

            // ---- A: the chapter ----
            val a = finals[batchA.id]
            assertNotNull("batch A did not reach a final state", a)
            log("A final: ${a!!.status}, output ${a.outputFileId}, error ${a.errorFileId}, ${a.completed}/${a.total} failed ${a.failed}, usage ${a.usage}, errors ${a.errors}")
            assertEquals("completed", a.status)
            val output = a.outputFileId?.let { client.downloadFile(it) }
            val errors = a.errorFileId?.let { client.downloadFile(it) }
            output?.let { File(outDir, "output-A.jsonl").writeText(it, Charsets.UTF_8) }
            errors?.let { File(outDir, "errors-A.jsonl").writeText(it, Charsets.UTF_8) }
            val results = BatchOutputParser.parse(output) + BatchOutputParser.parse(errors)
            assertEquals(linesA.map { it.customId }.toSet(), results.map { it.customId }.toSet())
            for (r in results) assertNotNull("custom_id ${r.customId} does not parse", BatchCustomId.parse(r.customId))

            val assembled = BatchAssembler.assemble(novelId, built.chapters, results)
            val ch0 = assembled[0]
            val ch1 = assembled[1]
            assertTrue("chapter 0: ${ch0.outcomes}", ch0.complete)
            val failed = ch1.outcomes.single()
            assertTrue("chapter 1 should have failed: $failed", failed is ChunkOutcome.Failed)
            log("chapter 1 (bad temperature): ${(failed as ChunkOutcome.Failed).reason}")
            assertEquals(TokenUsage.ZERO, ch1.usage)

            // Post-processing with the app's code; no live calls allowed (spend stays bounded).
            val engine = TranslationEngine(
                apiKey = "no-live-calls", model = preset.model, baseUrl = "http://no-live.invalid/v1",
                sourceLang = "zh", usageSink = UsageSink { },
            )
            val processor = BatchChapterProcessor(engine, TranslationOptions(strictMode = true), allowLive = { false })
            val outcome = processor.process(novelId, 0, built.chapters[0].chunks, ch0.outcomes, bible, StoryContext())
            val result = outcome.result
            assertNotNull("chapter 0 not stored: ${outcome.error}", result)
            result!!
            File(outDir, "chapter-0.html").writeText(result.englishText, Charsets.UTF_8)
            val english = result.englishText
            val paragraphs = Regex("<p>").findAll(english).count()
            val cjk = english.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            val latin = english.count { it in 'a'..'z' || it in 'A'..'Z' }
            log(
                "chapter 0: ${english.length} chars, $paragraphs paragraphs (source ${QualityChecks.countParagraphs(chunk0)}), " +
                    "CJK $cjk, violations ${result.glossaryViolations}, leaks ${result.untranslatedFragments}, " +
                    "auto-added ${result.autoAddedTerms.map { it.source + "→" + it.english }}, pending ${result.pendingTerms.size}, " +
                    "summary '${result.chapterSummary.take(120)}'"
            )
            assertFalse(english.contains("===TERMS==="))
            assertFalse(english.contains("===SUMMARY==="))
            assertTrue(result.chapterSummary.isNotBlank())
            assertTrue("not English", latin > 300 && cjk < latin / 50)
            assertTrue(english.contains("Xiao Yan"))
            assertEquals(QualityChecks.countParagraphs(chunk0), paragraphs)
            assertEquals(0, outcome.liveChunks)

            // ---- cost ----
            val usageA = ch0.usage + ch1.usage
            val usdA = BatchCost.actual(usageA, pricing)
            val usdB = BatchCost.actual(usageB, pricing)
            log("cost: A $usageA = \$${"%.6f".format(usdA)}, B $usageB = \$${"%.6f".format(usdB)}, total \$${"%.6f".format(usdA + usdB)}")
            a.usage?.let { log("A batch-level usage $it (per-line sum $usageA)") }
            b.usage?.let { log("B batch-level usage $it (per-line sum $usageB)") }
            assertTrue(usageA.promptTokens > 0 && usageA.completionTokens > 0)
            assertTrue(usdA > 0.0 && usdA < 0.01)
            assertEquals(
                usageA.promptTokens * 0.05 / 1e6 + usageA.completionTokens * 0.25 / 1e6, usdA, 1e-12,
            )
        } finally {
            // No stray batch: cancel anything of ours that is still running.
            for (id in created) {
                if (finals[id]?.status in terminal) continue
                try {
                    val info = client.getBatch(id)
                    if (info.status !in terminal && info.status != "cancelling") {
                        log("cleanup: cancelling $id (${info.status}) → ${client.cancelBatch(id).status}")
                    }
                } catch (e: Exception) {
                    log("cleanup of $id failed: ${e.message}")
                }
            }
            listRecent(key, created)
        }
    }

    /** GET /v1/batches?limit=5: logs the recent batches and flags any of ours still running. */
    private fun listRecent(key: String, ours: List<String>) {
        try {
            val request = Request.Builder().url("https://api.openai.com/v1/batches?limit=5")
                .header("Authorization", "Bearer $key").get().build()
            TranslationEngine.defaultClient.newCall(request).execute().use { response ->
                val node = mapper.readTree(response.body?.string().orEmpty())
                for (b in node.path("data")) {
                    val id = b.path("id").asText()
                    val status = b.path("status").asText()
                    log("recent: $id $status${if (id in ours) " (this run)" else ""}")
                    if (id in ours) assertNull("$id still running", status.takeIf { it in setOf("validating", "in_progress") })
                }
            }
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            log("listing batches failed: ${e.message}")
        }
    }
}
