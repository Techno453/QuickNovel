package com.lagradost.quicknovel.bench

import com.lagradost.quicknovel.translator.AnalysisFilter
import com.lagradost.quicknovel.translator.AuthorNotes
import com.lagradost.quicknovel.translator.DiscoveredTerm
import com.lagradost.quicknovel.translator.PromptTemplates
import com.lagradost.quicknovel.translator.TranslationProtocol
import java.io.File
import java.util.Locale

/**
 * `-Dbench.analyzeOnly=true`: runs the app's real "Analyze novel" prompt (the Story Bible
 * pre-pass, [PromptTemplates.analysisSystemPrompt]) on every sample with each model in
 * `-Dbench.analyzeModels` (default `gpt-6-luna,gpt-6-sol`), `-Dbench.analyzeRuns` times each
 * (default 2), and writes the extracted bibles plus a side-by-side comparison. No translation,
 * no judging; spend goes on the shared ledger like every other call.
 *
 * Output: `<run>/analysis/<sample>.<model>.r<n>.json` (parsed terms + raw reply) and
 * `<run>/analysis-compare.md`. As in the app, author's notes are stripped from the excerpt
 * ([AuthorNotes]); the report also shows what [AnalysisFilter] would drop from each run.
 */
object AnalysisCompare {

    data class Extraction(
        val sampleId: String,
        val modelId: String,
        val run: Int,
        val premise: String,
        val terms: List<DiscoveredTerm>,
        val usd: Double,
        val promptTokens: Long,
        val completionTokens: Long,
        val latencyMs: Long,
        val error: String?,
        val raw: String,
    )

    fun run(
        env: BenchEnv,
        cfg: BenchConfig,
        api: BenchOpenAi,
        samples: List<Sample>,
        runDir: File,
        log: (String) -> Unit,
    ): File {
        val modelIds = (env.setting("analyzeModels") ?: "gpt-6-luna,gpt-6-sol")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val models = modelIds.map { cfg.model(it) ?: error("Unknown analyze model '$it' (add it to bench.config.json)") }
        val runs = (env.setting("analyzeRuns")?.toIntOrNull() ?: 2).coerceIn(1, 5)
        val outDir = File(runDir, "analysis").apply { mkdirs() }

        val extractions = mutableListOf<Extraction>()
        for (sample in samples) for (model in models) for (r in 1..runs) {
            val system = PromptTemplates.analysisSystemPrompt(sample.lang)
            val user = PromptTemplates.buildAnalysisPrompt(
                excerpt = AuthorNotes.strip(sample.text), sourceLang = sample.lang, knownSources = emptyList(),
                previousPremise = "", style = null, chapterLabel = "chapter 1",
            )
            val started = System.currentTimeMillis()
            // Same call shape as TranslationEngine.extractStoryBible (temperature 0.1, plain text reply).
            val reply = api.chat(model, "analysis", sample.id, system, user, maxCompletionTokens = 4000, temperature = 0.1)
            val latency = System.currentTimeMillis() - started
            val parsed = if (reply.error == null) TranslationProtocol.parseAnalysis(reply.content) else null
            val e = Extraction(
                sampleId = sample.id, modelId = model.id, run = r,
                premise = parsed?.premise.orEmpty(), terms = parsed?.terms.orEmpty(),
                usd = reply.call?.usd ?: 0.0,
                promptTokens = reply.call?.promptTokens ?: 0L,
                completionTokens = reply.call?.completionTokens ?: 0L,
                latencyMs = latency, error = reply.error, raw = reply.content,
            )
            extractions += e
            benchMapper.writeValue(File(outDir, "${sample.id}.${fileSafe(model.id)}.r$r.json"), e)
            log(
                "analysis ${sample.id} / ${model.id} r$r: " + (e.error?.let { "ERROR $it" }
                    ?: "${e.terms.size} terms (${e.terms.count { it.category == "character" }} characters), " +
                    "${e.promptTokens}+${e.completionTokens} tokens, ${"%.4f".format(Locale.US, e.usd)} USD, ${latency / 1000.0}s"),
            )
        }

        val report = File(runDir, "analysis-compare.md")
        report.writeText(render(samples, models.map { it.id }, runs, extractions))
        log("analysis comparison → $report (total ${"%.4f".format(Locale.US, extractions.sumOf { it.usd })} USD)")
        return report
    }

    private fun fileSafe(id: String) = id.replace('/', '_')

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    /** Markdown: per sample, premises, per-run counts, then the union of sources with every run's rendering. */
    fun render(samples: List<Sample>, modelIds: List<String>, runs: Int, extractions: List<Extraction>): String {
        val sb = StringBuilder("# Analysis pre-pass comparison\n\n")
        sb.append("Each cell is `english (gender, category, confidence)` as extracted by that model/run with the app's ")
            .append("\"Analyze novel\" prompt on the benchmark excerpt. `—` = the run did not list that source string. ")
            .append("Rows flagged ⚑ have models disagreeing on the English rendering or the gender.\n\n")
        val cols = modelIds.flatMap { m -> (1..runs).map { m to it } }

        val textOf = samples.associate { it.id to it.text }
        // What the app's deterministic filter would drop from each run: (sample, model, run) -> source -> reason.
        val filtered = extractions.associate { e ->
            Triple(e.sampleId, e.modelId, e.run) to
                AnalysisFilter.filter(e.terms, textOf[e.sampleId].orEmpty()).dropped.associate { it.term.source.trim() to it.reason }
        }
        sb.append("## Spend and size\n\n`Kept` = terms left after the app's `AnalysisFilter`.\n\n")
        sb.append("| Sample | Model | Run | Terms | Kept | Characters | Gendered | Tokens in+out | USD | Latency s |\n|---|---|---|---|---|---|---|---|---|---|\n")
        for (e in extractions) {
            val chars = e.terms.filter { it.category == "character" }
            val dropped = filtered[Triple(e.sampleId, e.modelId, e.run)].orEmpty().size
            sb.append("| ${e.sampleId} | ${e.modelId} | r${e.run} | ${e.terms.size} | ${e.terms.size - dropped} | ${chars.size} | ${chars.count { it.gender != "unknown" }} | ")
                .append("${e.promptTokens}+${e.completionTokens} | ${"%.4f".format(Locale.US, e.usd)} | ${"%.1f".format(Locale.US, e.latencyMs / 1000.0)}")
                .append(e.error?.let { " | ERROR: $it" } ?: "").append(" |\n")
        }
        sb.append("\nTotal: ${"%.4f".format(Locale.US, extractions.sumOf { it.usd })} USD; per model: ")
            .append(modelIds.joinToString { m -> "$m ${"%.4f".format(Locale.US, extractions.filter { it.modelId == m }.sumOf { it.usd })}" })
            .append(".\n\n")

        for (sample in samples) {
            val ex = extractions.filter { it.sampleId == sample.id }
            sb.append("## ${sample.id} (${sample.lang}, ${sample.text.length} chars)\n\n### Premises\n\n")
            for (e in ex) sb.append("- **${e.modelId} r${e.run}:** ${e.premise.ifBlank { "(none)" }.replace("\n", " ")}\n")

            // Self-consistency: how many of a model's run-1 sources reappear in run 2 with the same English.
            if (runs >= 2) {
                sb.append("\n### Run-to-run consistency (same model)\n\n")
                for (m in modelIds) {
                    val a = ex.firstOrNull { it.modelId == m && it.run == 1 }?.terms.orEmpty().associateBy { it.source.trim() }
                    val b = ex.firstOrNull { it.modelId == m && it.run == 2 }?.terms.orEmpty().associateBy { it.source.trim() }
                    val shared = a.keys intersect b.keys
                    val sameEnglish = shared.count { norm(a.getValue(it).english) == norm(b.getValue(it).english) }
                    val sameGender = shared.count { a.getValue(it).gender == b.getValue(it).gender }
                    sb.append("- **$m:** ${a.size} / ${b.size} terms, ${shared.size} shared sources, $sameEnglish same English, $sameGender same gender\n")
                }
            }

            val bySourceCol = HashMap<String, HashMap<Pair<String, Int>, DiscoveredTerm>>()
            val order = LinkedHashMap<String, Int>() // first-seen order, characters first within a run
            for (e in ex.sortedWith(compareBy({ modelIds.indexOf(it.modelId) }, { it.run }))) {
                for (t in e.terms.sortedByDescending { it.category == "character" }) {
                    val key = t.source.trim()
                    if (key.isEmpty()) continue
                    order.putIfAbsent(key, order.size)
                    bySourceCol.getOrPut(key) { HashMap() }[e.modelId to e.run] = t
                }
            }
            sb.append("\n### Terms\n\n| Source | ")
            sb.append(cols.joinToString(" | ") { "${it.first} r${it.second}" }).append(" | Flags |\n|---|")
            sb.append(cols.joinToString("") { "---|" }).append("---|\n")
            for ((source, _) in order.entries.sortedBy { it.value }) {
                val row = bySourceCol.getValue(source)
                val englishes = row.values.map { norm(it.english) }.toSet()
                val genders = row.values.filter { it.category == "character" }.map { it.gender }.toSet()
                val perModel = modelIds.associateWith { m -> row.filterKeys { it.first == m }.values }
                val missingInModel = perModel.filterValues { it.isEmpty() }.keys
                val flags = buildList {
                    if (englishes.size > 1) add("english differs")
                    if (genders.size > 1) add("gender differs")
                    if (missingInModel.isNotEmpty() && missingInModel.size < modelIds.size) add("only " + (modelIds - missingInModel).joinToString("/"))
                    val reasons = cols.mapNotNull { (m, r) -> filtered[Triple(sample.id, m, r)]?.get(source) }.toSet()
                    if (reasons.isNotEmpty()) add("filtered: " + reasons.joinToString("/") { it.name.lowercase(Locale.ROOT) })
                }
                sb.append("| ").append(source).append(" | ")
                sb.append(cols.joinToString(" | ") { c ->
                    row[c]?.let { t ->
                        val g = if (t.category == "character") ", ${t.gender}" else ""
                        "${t.english} (${t.category}$g, ${"%.1f".format(Locale.US, t.confidence)})"
                    } ?: "—"
                })
                sb.append(" | ").append(if (flags.isEmpty()) "" else "⚑ " + flags.joinToString("; ")).append(" |\n")
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
