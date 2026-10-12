package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Translation benchmark: runs the app's real TranslationEngine against several models on
 * zh/ja/ko web-novel samples and writes a report. SKIPPED unless BENCH=1 (env) or -Dbench=1,
 * so normal unit test runs never call an API. See docs/translation-bench.md.
 */
class TranslationBench {
    private fun log(msg: String) = println("[bench] " + Secrets.scrub(msg))

    @Test
    fun run() = runBlocking {
        val env = BenchEnv.load()
        assumeTrue("Translation bench is opt-in: set BENCH=1 or -Dbench=1", env.enabled)
        val base = BenchConfig.loadDefault()
        val cfg = env.applyOverrides(base)
        if (!cfg.dryRun) assumeTrue("No ${ModelSpec.OPENAI_KEY} available (env or bench.local.properties)", env.hasKey())

        val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val runDir = File(env.outputRoot, runId)
        val ledger = CostLedger(File(env.outputRoot, "ledger.json"), cfg.budgetUsd, runId)
        log("run $runId → $runDir")
        cfg.models.map { it.keyName }.distinct().forEach { log(env.keyDescription(it)) }
        log("ledger total so far ${usd(ledger.totalUsd)} of cap ${usd(cfg.budgetUsd)}; dryRun=${cfg.dryRun}, judge=${cfg.judge.enabled}")
        log("models: " + cfg.models.filter { it.enabled }.joinToString { it.id })

        val notes = mutableListOf<String>()
        val samples = loadSamples(env, cfg, notes)
        require(samples.isNotEmpty()) { "No samples could be loaded" }
        File(runDir, "samples").mkdirs()
        samples.forEach { File(runDir, "samples/${it.id}.txt").writeText(it.text) }

        if (cfg.dryRun) {
            dryRun(env, cfg, samples, runDir, ledger, notes)
            return@runBlocking
        }
        if (env.setting("reportOnly")?.let { it == "1" || it.toBoolean() } == true) {
            // No API calls: rebuild the report from stored results and cached bibles.
            val seeder = BibleSeeder(null, cfg.models.first(), env.cacheDir)
            val bibles = samples.associate { s -> s.id to (seeder.cached(s.id) ?: Bible(s.id, seededBy = "missing")) }
            notes.add("Report rebuilt from stored results (reportOnly); no API calls in this run.")
            mergedReport(env, base, cfg, samples, bibles, emptyMap(), notes, runDir, runId, ledger)
            return@runBlocking
        }

        val api = BenchOpenAi({ env.key(it) }, cfg.baseUrl, ledger)
        val cheap = cfg.model(cfg.bibleModel) ?: error("bibleModel ${cfg.bibleModel} not in models")

        // 1. Smoke test: one tiny call.
        val smoke = api.chat(cheap, "smoke", "", null, "Reply with OK.", maxCompletionTokens = 5)
        log("smoke ${cheap.id}: ${smoke.error ?: "HTTP 200, finish_reason=${smoke.finishReason}, ${smoke.call?.promptTokens}+${smoke.call?.completionTokens} tokens, ${usd(smoke.call?.usd ?: 0.0)}"}")
        assertTrue("Smoke test failed: ${smoke.error}", smoke.error == null || !(smoke.error.contains("401") || smoke.error.contains("403")))
        if (smoke.error != null) notes.add("Smoke test on ${cheap.id} failed: ${smoke.error}")

        // 2. Model availability (free listing calls).
        val listings = cfg.models.filter { it.enabled }.groupBy { it.baseUrlOr(cfg.baseUrl) to it.keyName }
            .mapValues { (k, _) -> api.listModels(k.first, k.second) }

        // 3. Bibles (cached per sample so every model and every run uses the same one).
        val seeder = BibleSeeder(api, cheap, env.cacheDir)
        val bibles = samples.associate { s ->
            val (bible, err) = seeder.seed(s)
            if (err != null) notes.add("Bible for ${s.id} failed: $err (translations run without a glossary)")
            log("bible ${s.id}: ${bible.entries.size} entries (${bible.seededBy}, ${usd(bible.usd)})")
            s.id to bible
        }
        File(runDir, "bibles").mkdirs()
        bibles.values.forEach { benchMapper.writeValue(File(runDir, "bibles/${it.sampleId}.json"), it) }

        // 4. Translate, one provider phase at a time.
        val adapter = BenchEngineAdapter()
        val judge = if (cfg.judge.enabled) cfg.model(cfg.judge.model)?.let { Judge(api, it) } else null
        val options = RunOptions({ env.key(it) }, cfg.baseUrl, ledger, cfg.engineOptions())
        val aborted = AtomicBoolean(false)
        val store = ResultStore(File(env.outputRoot, "results"))
        val phases = cfg.models.filter { it.enabled }.groupBy { it.provider }
            .toList().sortedBy { if (it.first == "openai") 0 else 1 }

        for ((provider, phaseModels) in phases) {
            if (aborted.get()) break
            if (!env.hasKey(phaseModels.first().keyName)) {
                log("phase $provider: no ${phaseModels.first().keyName}, skipped")
                notes.add("Phase $provider skipped: no ${phaseModels.first().keyName}.")
                phaseModels.forEach { m -> samples.forEach { s ->
                    store.save(PairResult(runId, s.id, m.id, BenchOutput("", Usage(), 0, "no key (${m.keyName})", noKey = true), null, null))
                } }
                continue
            }
            val models = phaseModels.toMutableList()
            var estimate = models.sumOf { estimateModelUsd(it, samples, cfg, cheap) }
            var drops = 0
            while (ledger.totalUsd + estimate > cfg.budgetUsd * 0.9 && drops < cfg.maxDropsPerPhase && models.size > 1) {
                val priciest = models.maxByOrNull { estimateModelUsd(it, samples, cfg, cheap) }!!
                models.remove(priciest)
                drops++
                notes.add("Dropped ${priciest.id} from phase $provider to stay under the cap (estimate ${usd(estimateModelUsd(priciest, samples, cfg, cheap))}).")
                estimate = models.sumOf { estimateModelUsd(it, samples, cfg, cheap) }
            }
            log("phase $provider: ${models.size} models, estimate ${usd(estimate)}, ledger ${usd(ledger.totalUsd)}")

            val listing = listings[(phaseModels.first().baseUrlOr(cfg.baseUrl)) to phaseModels.first().keyName]
            val sem = Semaphore(cfg.parallelism)
            val jobs = samples.flatMap { s -> models.map { m -> s to m } }.map { (s, m) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val bible = bibles.getValue(s.id)
                        val output = when {
                            listing != null && m.apiModel !in listing ->
                                BenchOutput("", Usage(), 0, "model id not in ${m.provider} /models listing", unavailable = true)
                            aborted.get() -> BenchOutput("", Usage(), 0, "skipped: budget cap reached", budgetHit = true)
                            else -> adapter.translate(s, bible, m, options)
                        }
                        if (output.budgetHit) aborted.set(true)
                        val metrics = if (output.text.isNotBlank()) MetricsCalculator.compute(s, bible, output.text) else null
                        val score = if (judge != null && output.text.isNotBlank() && !aborted.get()) {
                            withContext(Dispatchers.IO) { judge.score(s, bible, output.text) }.also {
                                if (it.error?.contains("budget cap") == true) aborted.set(true)
                            }
                        } else null
                        val raw = File(runDir, "raw/${safe(m.id)}/${s.id}.txt")
                        raw.parentFile.mkdirs()
                        raw.writeText(output.text.ifBlank { "(no output) ${output.error.orEmpty()}" })
                        val result = PairResult(runId, s.id, m.id, output, metrics, score)
                        store.save(result)
                        log("${m.id} / ${s.id}: ${output.error ?: "ok"} · ${usd(output.usage.usd)} · ${output.latencyMs / 1000}s · judge ${score?.average?.let { "%.1f".format(Locale.US, it) } ?: "–"}")
                        result
                    }
                }
            }
            jobs.awaitAll()
            if (aborted.get()) notes.add("Run aborted early: budget cap reached (partial report).")
        }

        // 5. Report from the latest stored result of every configured model (so a --models re-run merges).
        val adjustments = cfg.models.associate { it.id to adapter.adjustments(it.id) + api.stateFor(it).adjustments }
        mergedReport(env, base, cfg, samples, bibles, adjustments, notes, runDir, runId, ledger)
    }

    /** Builds the report from the result store, recomputing local metrics with the current code. */
    private fun mergedReport(
        env: BenchEnv, base: BenchConfig, cfg: BenchConfig, samples: List<Sample>, bibles: Map<String, Bible>,
        adjustments: Map<String, List<String>>, notes: List<String>, runDir: File, runId: String, ledger: CostLedger,
    ) {
        val reportModels = base.models.map { m -> m.copy(enabled = m.enabled || cfg.model(m.id)?.enabled == true) }
        val reportCfg = cfg.copy(models = reportModels)
        val byId = samples.associateBy { it.id }
        val results = ResultStore(File(env.outputRoot, "results"))
            .loadAll(byId.keys, reportModels.filter { it.enabled }.map { it.id }.toSet())
            .map { r ->
                val s = byId.getValue(r.sampleId)
                r.copy(metrics = r.output.text.takeIf { it.isNotBlank() }?.let { MetricsCalculator.compute(s, bibles.getValue(s.id), it) })
            }
        val runs = results.map { it.runId }.distinct().sorted()
        val allNotes = notes + if (runs.size > 1) listOf("Results merged from runs: ${runs.joinToString()}.") else emptyList()
        writeReport(reportCfg, samples, bibles, results, adjustments, allNotes, runDir, runId, ledger, dryRun = false)
    }

    private fun writeReport(
        cfg: BenchConfig, samples: List<Sample>, bibles: Map<String, Bible>, results: List<PairResult>,
        adjustments: Map<String, List<String>>, notes: List<String>, runDir: File, runId: String,
        ledger: CostLedger, dryRun: Boolean,
    ) {
        val report = ReportBuilder(cfg, samples, bibles, results, adjustments, notes)
        report.write(runDir, runId, ledger.runUsd, ledger.totalUsd, dryRun)
        File(runDir.parentFile, "LATEST").writeText(runDir.name)
        println()
        println(report.leaderboardMarkdown())
        println(report.costTableMarkdown())
        println("[bench] spend this run ${usd(ledger.runUsd)} · ledger total ${usd(ledger.totalUsd)} of cap ${usd(cfg.budgetUsd)}")
        println("[bench] report: ${File(runDir, "report.md").absolutePath}")
        println("[bench] report: ${File(runDir, "report.html").absolutePath}")
    }

    private fun loadSamples(env: BenchEnv, cfg: BenchConfig, notes: MutableList<String>): List<Sample> {
        val fetcher = SampleFetcher(env.cacheDir, cfg)
        val out = mutableListOf<Sample>()
        for (spec in cfg.samples.filter { it.enabled }) {
            try {
                out.add(fetcher.load(spec))
            } catch (e: Exception) {
                val fallback = File(env.localSamplesDir, "${spec.lang}.txt")
                if (fallback.isFile) {
                    notes.add("Sample ${spec.id}: ${spec.url} unreachable (${e.message}); used bench/samples/${spec.lang}.txt instead.")
                    out.add(fetcher.load(spec.copy(id = "${spec.lang}-local", source = "file", url = fallback.path, title = "local ${spec.lang}.txt")))
                } else {
                    notes.add("Sample ${spec.id} skipped: ${e.message}")
                }
            }
        }
        val wanted = env.setting("samples")?.split(',')?.map { it.trim() }?.toSet()
        out.addAll(fetcher.localSamples(env.localSamplesDir).filter { wanted == null || it.id in wanted })
        out.forEach { log("sample ${it.id}: ${it.chars} chars (${it.paragraphs.size} paragraphs) of ${it.fullChars}") }
        out.filter { it.chars < cfg.sampleMinChars }.forEach { notes.add("Sample ${it.id} is short (${it.chars} chars < ${cfg.sampleMinChars}); the source chapter excerpt is all there was.") }
        return out
    }

    /** No API calls: fake outputs exercise samples, metrics and the report end to end. */
    private suspend fun dryRun(env: BenchEnv, cfg: BenchConfig, samples: List<Sample>, runDir: File, ledger: CostLedger, notes: MutableList<String>) {
        val seeder = BibleSeeder(null, cfg.model(cfg.bibleModel) ?: cfg.models.first(), env.cacheDir)
        val bibles = samples.associate { s ->
            val cached = seeder.cached(s.id)
            val bible = cached ?: run {
                // Fake bible: the first CJK run in the text as a male character "Hero".
                val term = Regex("""[\p{IsHan}\p{IsKatakana}\p{IsHangul}]{2,3}""").find(s.text)?.value ?: "?"
                Bible(s.id, "dry run", listOf(BibleEntry(term, "Hero", "character", "male", "fake")), "dry-run fake")
            }
            s.id to bible
        }
        val fake = FakeTranslator()
        val options = RunOptions({ null }, cfg.baseUrl, ledger)
        val results = samples.flatMap { s ->
            cfg.models.filter { it.enabled }.map { m ->
                val out = fake.translate(s, bibles.getValue(s.id), m, options)
                PairResult("dry-run", s.id, m.id, out, MetricsCalculator.compute(s, bibles.getValue(s.id), out.text), null)
            }
        }
        for (r in results) {
            val m = r.metrics!!
            assertTrue("dry run: expected CJK leak in fake output for ${r.sampleId}", m.cjkLeak > 0)
            if (bibles.getValue(r.sampleId).characters.any { it.gender == "male" || it.gender == "female" }) {
                assertTrue("dry run: expected a gender mismatch for ${r.sampleId}: ${m.genderDetails}", m.genderMismatches >= 1)
            }
            assertTrue(m.inputParagraphs > 0 && m.outputParagraphs == m.inputParagraphs)
        }
        notes.add("DRY RUN: no API calls were made; outputs are fakes that deliberately leak CJK and use a wrong pronoun.")

        // Adapter smoke: every enabled model's resolved config (presets included) goes through
        // BenchEngineAdapter -> TranslationEngine, answered by a local fake endpoint (no network, no spend).
        val smokeLedger = CostLedger(File(runDir, "dry-run-ledger.json"), cfg.budgetUsd, "dry-run")
        val smokeOptions = RunOptions({ "dry-run-key" }, cfg.baseUrl, smokeLedger, cfg.engineOptions())
        val sample = samples.first()
        val enabled = cfg.models.filter { it.enabled }
        for (m in enabled) {
            val transport = FakeChatTransport()
            val out = BenchEngineAdapter(transport = transport).translate(sample, bibles.getValue(sample.id), m, smokeOptions)
            val problems = transport.problems(m, cfg.baseUrl)
            log("adapter smoke ${m.id}: ${transport.requests.size} requests, ${out.error ?: "ok"}${if (problems.isEmpty()) "" else " · " + problems.joinToString()}")
            assertTrue("adapter smoke ${m.id}: ${out.error} $problems", out.error == null && problems.isEmpty() && transport.requests.isNotEmpty())
        }
        notes.add("Adapter smoke: ${enabled.size} model configs ran through BenchEngineAdapter → TranslationEngine (story bible, context, options) against a local fake endpoint.")
        writeReport(cfg, samples, bibles, results, emptyMap(), notes, runDir, "dry-run-${runDir.name}", ledger, dryRun = true)
    }

    /** Pre-phase estimate for one model over all samples, incl. its judge calls. */
    private fun estimateModelUsd(m: ModelSpec, samples: List<Sample>, cfg: BenchConfig, judgeModel: ModelSpec): Double =
        samples.sumOf { s ->
            val chunks = (s.chars / 2000) + 1
            val prompt = (s.chars * 1.0 + 900 * chunks).toLong()
            val reasoning = TokenEstimate.reasoningAllowance(effortLabel(m, null)?.takeIf { it != "off" }) * chunks
            val completion = (s.chars * 1.2).toLong() + reasoning
            val judge = if (cfg.judge.enabled) judgeModel.costUsd(s.chars * 2L + 600, 150) else 0.0
            m.costUsd(prompt, completion) + judge
        }

    private fun safe(id: String) = id.replace(Regex("""[^\w.-]"""), "_")
    private fun usd(v: Double) = String.format(Locale.US, "\$%.4f", v)
}

/** Latest result per (sample, model), so re-running a subset of models merges into the report. */
class ResultStore(private val dir: File) {
    private fun file(sampleId: String, modelId: String) =
        File(dir, "$sampleId/${modelId.replace(Regex("""[^\w.-]"""), "_")}.json")

    @Synchronized
    fun save(r: PairResult) {
        val f = file(r.sampleId, r.modelId)
        f.parentFile.mkdirs()
        benchMapper.writeValue(f, r)
    }

    fun loadAll(sampleIds: Set<String>, modelIds: Set<String>): List<PairResult> =
        sampleIds.flatMap { s -> modelIds.mapNotNull { m ->
            file(s, m).takeIf { it.isFile }?.let { runCatching { benchMapper.readValue<PairResult>(it) }.getOrNull() }
        } }
}
