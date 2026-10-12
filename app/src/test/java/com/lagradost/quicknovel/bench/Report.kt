package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.File
import java.util.Locale

@JsonIgnoreProperties(ignoreUnknown = true)
data class PairResult(
    val runId: String,
    val sampleId: String,
    val modelId: String,
    val output: BenchOutput,
    val metrics: Metrics?,
    val judge: JudgeScore?,
)

data class ModelRow(
    val model: ModelSpec,
    val effort: String?,
    val results: List<PairResult>,
    /** Panel mean (all judges except the model's own); the v1 single-judge mean when there is no panel. */
    val judgeAvg: Double?,
    /** Mean of the v1 judge (luna) including its self-score: the old single-judge leaderboard. */
    val baselineAvg: Double? = null,
    val perJudge: Map<String, Double?> = emptyMap(),
    val judgeCount: Int = 0,
    /** Panel mean without the leniency adjustment. */
    val panelRaw: Double? = null,
    val judgeUsd: Double = 0.0,
    val adherence: Double?,
    val leaks: Int,
    val fidelity: Double?,
    val genderMismatches: Int,
    val refusals: Int,
    val latencySec: Double?,
    val usdSpent: Double,
    val usdPerChapter: Double?,
    val usdPer100: Double?,
    val usdPer1000: Double?,
    val errors: List<String>,
    val status: String?,
) {
    val unavailable: Boolean get() = status != null
    fun target(cfg: BenchConfig): String = when {
        unavailable || usdPer100 == null -> "n/a"
        usdPer100 <= cfg.targetUsdPer100Chapters -> "YES (≤\$${fmt0(cfg.targetUsdPer100Chapters)})"
        usdPer100 <= cfg.stretchUsdPer100Chapters -> "stretch (≤\$${fmt0(cfg.stretchUsdPer100Chapters)})"
        else -> "no"
    }
}

private fun f(v: Double?, d: Int = 2) = v?.let { String.format(Locale.US, "%.${d}f", it) } ?: "–"
private fun fmt0(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else f(v)
private fun usd(v: Double?) = v?.let { if (it < 0.01) String.format(Locale.US, "\$%.4f", it) else String.format(Locale.US, "\$%.3f", it) } ?: "–"
private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

/** Reasoning setting actually used: OpenAI effort after any step-down, or the OpenRouter `reasoning` object. */
internal fun effortLabel(model: ModelSpec, effective: String?): String? {
    (effective ?: model.reasoningEffort)?.let { return it }
    @Suppress("UNCHECKED_CAST")
    val r = model.extraBody["reasoning"] as? Map<String, Any?> ?: return null
    return when {
        r["enabled"] == false -> "off"
        r["effort"] != null -> r["effort"].toString()
        else -> r.toString()
    }
}

class ReportBuilder(
    private val cfg: BenchConfig,
    private val samples: List<Sample>,
    private val bibles: Map<String, Bible>,
    private val results: List<PairResult>,
    private val adjustments: Map<String, List<String>>,
    private val notes: List<String>,
    private val board: JudgeBoard? = null,
) {
    private val sampleChars = samples.associate { it.id to it.chars }
    private val panel: JudgeBoard? get() = board?.takeIf { !it.isEmpty }
    private val baselineLabel: String get() = (panel?.baselineJudge ?: cfg.judge.model).substringAfterLast('/') + "-only"

    val rows: List<ModelRow> by lazy {
        val bibleUsdPerChapter = bibles.values.filter { it.usd > 0 }.mapNotNull { b ->
            sampleChars[b.sampleId]?.let { b.usd * cfg.chapterChars / it }
        }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        cfg.models.filter { it.enabled }.map { m ->
            val rs = results.filter { it.modelId == m.id && it.sampleId in sampleChars }
            val ok = rs.filter { it.output.error == null && !it.output.unavailable }
            val perChapter = ok.mapNotNull { r ->
                sampleChars[r.sampleId]?.takeIf { it > 0 }?.let { r.output.usage.usd * cfg.chapterChars / it }
            }.takeIf { it.isNotEmpty() }?.average()
            val per100 = perChapter?.let { it * 100 + bibleUsdPerChapter * cfg.bibleCallsPer100Chapters }
            ModelRow(
                model = m,
                effort = effortLabel(m, rs.firstNotNullOfOrNull { it.output.effectiveEffort }),
                results = rs,
                judgeAvg = panel.let { b -> if (b != null) b.panelMean(m.id) else ok.mapNotNull { it.judge?.average }.takeIf { it.isNotEmpty() }?.average() },
                baselineAvg = panel.let { b -> if (b != null) b.baselineMean(m.id) else ok.mapNotNull { it.judge?.average }.takeIf { it.isNotEmpty() }?.average() },
                perJudge = panel?.judges?.associate { j -> j.id to panel!!.judgeMean(j.id, m.id) }.orEmpty(),
                judgeCount = panel?.judgeCount(m.id) ?: 0,
                panelRaw = panel?.panelMeanRaw(m.id),
                judgeUsd = panel?.let { b -> rs.sumOf { r -> b.allScores(r.sampleId, m.id).values.sumOf { it.usd } } } ?: rs.sumOf { it.judge?.usd ?: 0.0 },
                adherence = ok.mapNotNull { it.metrics?.adherencePct }.takeIf { it.isNotEmpty() }?.average(),
                leaks = ok.sumOf { it.metrics?.cjkLeak ?: 0 },
                fidelity = ok.mapNotNull { it.metrics?.paragraphFidelity }.takeIf { it.isNotEmpty() }?.average(),
                genderMismatches = ok.sumOf { it.metrics?.genderMismatches ?: 0 },
                refusals = rs.count { it.metrics?.refusal != null },
                latencySec = ok.map { it.output.latencyMs / 1000.0 }.takeIf { it.isNotEmpty() }?.average(),
                usdSpent = rs.sumOf { it.output.usage.usd } + (panel?.let { b -> rs.sumOf { r -> b.allScores(r.sampleId, m.id).values.sumOf { it.usd } } } ?: rs.sumOf { it.judge?.usd ?: 0.0 }),
                usdPerChapter = perChapter,
                usdPer100 = per100,
                usdPer1000 = per100?.times(10),
                errors = rs.flatMap { r ->
                    listOfNotNull(r.output.error?.let { "${r.sampleId}: $it" }) +
                            r.output.finishReasons.filter { it != "stop" }.map { "${r.sampleId}: finish_reason=$it" } +
                            r.output.httpErrors.map { "${r.sampleId}: $it" } +
                            listOfNotNull(r.metrics?.refusal?.let { "${r.sampleId}: refusal: $it" })
                },
                status = when {
                    rs.isEmpty() -> "not run"
                    rs.all { it.output.noKey } -> "no key (${m.keyName})"
                    rs.all { it.output.unavailable } -> "unavailable"
                    rs.all { it.output.budgetHit } -> "skipped (budget cap)"
                    else -> null
                },
            )
        }.sortedWith(compareBy<ModelRow> { it.unavailable }.thenByDescending { it.judgeAvg ?: -1.0 }.thenBy { it.usdPer100 ?: 1e9 })
    }

    /** Ranks (1-based) of available models by the v1 judge alone, ties broken by cost like the leaderboard. */
    val baselineRanks: Map<String, Int> by lazy {
        rows.filter { !it.unavailable }.sortedWith(compareByDescending<ModelRow> { it.baselineAvg ?: -1.0 }.thenBy { it.usdPer100 ?: 1e9 })
            .mapIndexed { i, r -> r.model.id to i + 1 }.toMap()
    }
    val panelRanks: Map<String, Int> by lazy { rows.filter { !it.unavailable }.mapIndexed { i, r -> r.model.id to i + 1 }.toMap() }

    /** Models whose rank moves by >= [minMove] places between the v1-only and the panel ranking. */
    fun rankMoves(minMove: Int = 3): List<ModelRow> = rows.filter { r ->
        val a = baselineRanks[r.model.id]; val b = panelRanks[r.model.id]
        a != null && b != null && kotlin.math.abs(a - b) >= minMove
    }

    private fun judgeHeader(id: String): String = panel?.judges?.firstOrNull { it.id == id }?.label ?: id

    fun leaderboardMarkdown(): String = buildString {
        val js = panel?.judges.orEmpty()
        val judgeCols = if (panel != null) " Panel raw | Judges | $baselineLabel | Rank vs $baselineLabel |" + js.joinToString("") { " ${judgeHeader(it.id)} |" } else ""
        appendLine("| # | Model | Provider | Reasoning | ${if (panel != null) "Panel adj. (1–10)" else "Judge (1–10)"} |$judgeCols Adherence % | CJK leaks | Para fidelity | Gender mism. | Refusals | Latency s | $/chapter | $/100 ch | $/1,000 ch | Meets $${fmt0(cfg.targetUsdPer100Chapters)}/100 | Errors |")
        appendLine("|---|---|---|---|---|" + (if (panel != null) "---|---|---|---|" + js.joinToString("") { "---|" } else "") + "---|---|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { i, r ->
            if (r.unavailable) {
                val blanks = if (panel != null) " | | | |" + js.joinToString("") { " |" } else ""
                appendLine("| – | ${r.model.id} | ${r.model.provider} | – | ${r.status} |$blanks | | | | | | | | | n/a | ${cell(r.errors.firstOrNull() ?: "")} |")
            } else {
                val jc = if (panel != null) " ${f(r.panelRaw)} | ${r.judgeCount} | ${f(r.baselineAvg)} | ${rankDelta(r)} |" + js.joinToString("") { j -> " ${if (isSelfJudgment(j.id, r.model.id)) "(self)" else f(r.perJudge[j.id])} |" } else ""
                appendLine("| ${i + 1} | ${r.model.id} | ${r.model.provider} | ${r.effort ?: "n/a"} | ${f(r.judgeAvg)} |$jc ${f(r.adherence, 0)} | ${r.leaks} | ${f(r.fidelity)} | ${r.genderMismatches} | ${r.refusals} | ${f(r.latencySec, 1)} | ${usd(r.usdPerChapter)} | ${usd(r.usdPer100)} | ${usd(r.usdPer1000)} | ${r.target(cfg)} | ${r.errors.size} |")
            }
        }
    }

    /** "#4 → #1 (+3)" style: v1 rank to panel rank. */
    private fun rankDelta(r: ModelRow): String {
        val a = baselineRanks[r.model.id] ?: return "–"
        val b = panelRanks[r.model.id] ?: return "–"
        val d = a - b
        return "#$a → #$b" + when {
            d > 0 -> " (+$d)"
            d < 0 -> " ($d)"
            else -> ""
        } + if (kotlin.math.abs(d) >= 3) " ⚑" else ""
    }

    private fun scoreTriple(s: PanelScore) = "${f(s.average)} (${f(s.accuracy, 0)}/${f(s.fluency, 0)}/${f(s.consistency, 0)})"

    /** Inter-judge agreement, exclusions, rank moves and disagreements (markdown). */
    fun agreementMarkdown(): String = buildString {
        val b = panel ?: return@buildString
        val sampleIds = samples.map { it.id }
        appendLine("## Judge panel")
        appendLine()
        appendLine("Scores are accuracy / fluency / consistency, 1–10, averaged. The panel mean of an output is the mean over every judge except the output's own model (a judge never scores itself); a model's panel mean is the mean over samples. Blind rubric prompt: source, story bible and translation only, never the model name.")
        appendLine()
        appendLine("**Panel adj.** removes each judge's leniency bias (how far its scores sit above or below the other judges' on the same outputs) before averaging. With every judge present this equals the raw mean; it only matters where a judge is missing, e.g. a strict judge excluded from its own model's outputs would otherwise lift that model. **Panel raw** is the plain mean.")
        appendLine()
        appendLine("| Judge | Kind | Outputs scored | Mean score given | Leniency bias | Spend |")
        appendLine("|---|---|---|---|---|---|")
        for (j in b.judges) {
            val all = sampleIds.flatMap { s -> rows.mapNotNull { r -> b.allScores(s, r.model.id)[j.id] } }
            val valid = all.filter { it.average != null }
            val kind = when (j.kind) {
                PanelScore.KIND_LEGACY -> "LLM, v1 prompt (single-judge run; already paid)"
                PanelScore.KIND_MANUAL -> "manual"
                else -> "LLM, blind rubric"
            }
            val bias = b.judgeBias[j.id]?.let { (if (it >= 0) "+" else "") + f(it) } ?: "–"
            appendLine("| ${j.label} | $kind | ${valid.size}${if (all.size > valid.size) " (+${all.size - valid.size} errors)" else ""} | ${f(valid.mapNotNull { it.average }.takeIf { it.isNotEmpty() }?.average())} | $bias | ${usd(all.sumOf { it.usd })} |")
        }
        appendLine()
        val excluded = b.excluded.filter { e -> rows.any { it.model.id == e.modelId } }
        if (excluded.isNotEmpty()) {
            appendLine("**Self-judging exclusions** (these cells are scored by the remaining judges only):")
            appendLine()
            excluded.groupBy { it.judge to it.modelId }.forEach { (k, cells) ->
                appendLine("- ${k.first} did not score **${k.second}** on ${cells.joinToString { it.sampleId }}" +
                        if (k.first == b.baselineJudge) " (its stored v1 self-scores stay in the $baselineLabel column only)" else "")
            }
            appendLine()
        }
        appendLine("## Inter-judge agreement")
        appendLine()
        appendLine("Spearman rank correlation between the model rankings of each pair of judges (each judge's per-model mean; computed over the models both judges scored, n in brackets; 1 = identical order).")
        appendLine()
        val pairs = b.spearman()
        val js = b.judges.filter { j -> pairs.any { it.a == j.id || it.b == j.id } }
        appendLine("| | " + js.joinToString(" | ") { it.label } + " |")
        appendLine("|---|" + js.joinToString("") { "---|" })
        for (a in js) {
            appendLine("| ${a.label} | " + js.joinToString(" | ") { c ->
                if (a.id == c.id) "1" else pairs.firstOrNull { (it.a == a.id && it.b == c.id) || (it.a == c.id && it.b == a.id) }
                    ?.let { "${f(it.rho)} [${it.n}]" } ?: "–"
            } + " |")
        }
        appendLine()
        val moves = rankMoves()
        appendLine("### Rank moves ≥ 3 places ($baselineLabel → panel)")
        appendLine()
        if (moves.isEmpty()) appendLine("None.") else {
            appendLine("| Model | $baselineLabel rank | Panel rank | $baselineLabel mean | Panel mean | " + b.judges.joinToString(" | ") { it.label } + " |")
            appendLine("|---|---|---|---|---|" + b.judges.joinToString("") { "---|" })
            for (r in moves) {
                appendLine("| ${r.model.id} | ${baselineRanks[r.model.id]} | ${panelRanks[r.model.id]} | ${f(r.baselineAvg)} | ${f(r.judgeAvg)} | " +
                        b.judges.joinToString(" | ") { j -> if (isSelfJudgment(j.id, r.model.id)) "(self)" else f(r.perJudge[j.id]) } + " |")
            }
            for (r in moves) {
                appendLine()
                appendLine("**${r.model.id}** — notes per sample:")
                for (s in sampleIds) {
                    val sc = b.allScores(s, r.model.id).filterValues { it.average != null }
                    if (sc.isEmpty()) continue
                    appendLine("- $s: " + sc.entries.joinToString("; ") { (j, v) ->
                        "${judgeHeader(j)}${if (isSelfJudgment(j, r.model.id)) " [self, excluded]" else ""} ${f(v.average)} — ${cell(v.note.take(220))}"
                    })
                }
            }
        }
        appendLine()
        appendLine("### Biggest disagreements")
        appendLine()
        appendLine("Outputs with the largest spread (max − min of the panel judges' averages).")
        for ((cellKey, spread) in b.disagreements(8)) {
            val (s, m) = cellKey
            appendLine()
            appendLine("**$m / $s** — spread ${f(spread)}, panel ${f(b.cellPanel(s, m))}")
            b.panelScores(s, m).forEach { (j, v) -> appendLine("- ${judgeHeader(j)} ${scoreTriple(v)}: ${cell(v.note)}") }
        }
        appendLine()
        appendLine("## What changed vs single-judge")
        appendLine()
        appendLine(whatChanged())
        appendLine()
    }

    /** Short, generated comparison of the panel ranking with the v1 (luna-only) ranking. */
    fun whatChanged(): String {
        val b = panel ?: return ""
        val avail = rows.filter { !it.unavailable && it.judgeAvg != null }
        if (avail.isEmpty()) return ""
        val baseTop = avail.minByOrNull { baselineRanks[it.model.id] ?: 99 }!!
        val panelTop = avail.first()
        val moves = rankMoves()
        val meanShift = avail.mapNotNull { r -> baselineRanks[r.model.id]?.let { kotlin.math.abs(it - panelRanks.getValue(r.model.id)) } }.average()
        val rho = Stats.spearman(avail.mapNotNull { it.baselineAvg }.takeIf { it.size == avail.size } ?: emptyList(), avail.map { it.judgeAvg!! })
        val cheap = avail.filter { (it.usdPer100 ?: 1e9) <= cfg.targetUsdPer100Chapters }
        val cheapBase = cheap.minByOrNull { baselineRanks[it.model.id] ?: 99 }
        val cheapPanel = cheap.firstOrNull()
        val self = b.baselineJudge?.let { bj -> avail.firstOrNull { isSelfJudgment(bj, it.model.id) } }
        return buildString {
            append("Panel of ${b.judges.size} judges (${b.judges.joinToString { it.label }}). ")
            append("Top model: ${baseTop.model.id} under $baselineLabel, ${panelTop.model.id} under the panel. ")
            if (rho != null) append("The two rankings correlate at Spearman ${f(rho)}; models move ${f(meanShift, 1)} places on average. ")
            append(if (moves.isEmpty()) "No model moves 3 or more places. " else "Moved 3+ places: " + moves.joinToString { "${it.model.id} #${baselineRanks[it.model.id]} → #${panelRanks[it.model.id]}" } + ". ")
            if (self != null) append("${self.model.id} gave itself ${f(b.judgeMean(b.baselineJudge!!, self.model.id, includeSelf = true))} as judge; the other judges give it ${f(self.judgeAvg)}. ")
            if (cheapBase != null && cheapPanel != null) {
                append("Best within ≤\$${fmt0(cfg.targetUsdPer100Chapters)}/100 chapters: ${cheapBase.model.id} under $baselineLabel, ${cheapPanel.model.id} under the panel")
                append(if (cheapBase.model.id == cheapPanel.model.id) " (unchanged)" else "")
                val cheapRaw = cheap.maxByOrNull { it.panelRaw ?: -1.0 }
                if (cheapRaw != null && cheapRaw.model.id != cheapPanel.model.id) append(" (${cheapRaw.model.id} on the raw panel mean, ${f(cheapRaw.panelRaw)} vs ${f(cheapPanel.panelRaw)})")
                val top = cheap.take(4).joinToString { "${it.model.id} ${f(it.judgeAvg)}" }
                append(". Top of that tier: $top; gaps under ~0.5 are ties for judges this noisy. ")
            }
            val lifted = avail.filter { r -> r.panelRaw != null && r.judgeAvg != null && kotlin.math.abs(r.judgeAvg - r.panelRaw) >= 0.1 }
            if (lifted.isNotEmpty()) append("Leniency adjustment changed only the self-excluded models: " +
                    lifted.joinToString { "${it.model.id} ${f(it.panelRaw)} → ${f(it.judgeAvg)}" } + ".")
        }
    }

    fun costTableMarkdown(): String = buildString {
        appendLine("| Model | Provider | Price in/out (\$/1M) | Prompt tok | Completion tok | of which reasoning | Translate \$ | Judge \$ | Total \$ |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (r in rows.filter { it.results.isNotEmpty() }) {
            val p = r.results.sumOf { it.output.usage.promptTokens }
            val c = r.results.sumOf { it.output.usage.completionTokens }
            val re = r.results.sumOf { it.output.usage.reasoningTokens }
            val t = r.results.sumOf { it.output.usage.usd }
            val j = r.judgeUsd
            appendLine("| ${r.model.id} | ${r.model.provider} | ${f(r.model.inputPerM)} / ${f(r.model.outputPerM)}${if (r.model.priceNote.isNotBlank()) " (" + r.model.priceNote + ")" else ""} | $p | $c | $re | ${usd(t)} | ${usd(j)} | ${usd(t + j)} |")
        }
    }

    fun markdown(runId: String, runUsd: Double, ledgerUsd: Double, dryRun: Boolean): String = buildString {
        appendLine("# Translation benchmark — $runId${if (dryRun) " (DRY RUN — fake outputs, no API calls)" else ""}")
        appendLine()
        appendLine("Samples: " + samples.joinToString("; ") { "**${it.id}** (${it.lang}, ${it.chars} chars of ${it.fullChars}, ${it.paragraphs.size} paragraphs) — ${it.title} <${it.origin}>" })
        appendLine()
        appendLine("Cost columns are scaled from measured tokens to a ${cfg.chapterChars}-source-char chapter (includes the system prompt and per-chunk glossary actually sent); \$/100 ch also adds ${cfg.bibleCallsPer100Chapters} bible pre-passes per 100 chapters. Target: ≤ \$${fmt0(cfg.targetUsdPer100Chapters)} per 100 chapters (stretch ≤ \$${fmt0(cfg.stretchUsdPer100Chapters)}).")
        appendLine()
        notes.forEach { appendLine("> $it") }
        if (notes.isNotEmpty()) appendLine()
        appendLine("## Leaderboard")
        appendLine()
        append(leaderboardMarkdown())
        appendLine()
        append(agreementMarkdown())
        appendLine("## Spend per model (this report's results)")
        appendLine()
        append(costTableMarkdown())
        appendLine()
        val adj = adjustments.filterValues { it.isNotEmpty() }
        if (adj.isNotEmpty()) {
            appendLine("## Request-parameter adjustments")
            appendLine()
            adj.forEach { (m, list) -> list.distinct().forEach { appendLine("- **$m**: ${cell(it)}") } }
            appendLine()
        }
        appendLine("## Per-sample excerpts")
        for (s in samples) {
            appendLine()
            appendLine("### ${s.id} — ${s.title}")
            appendLine()
            appendLine("**Source** (first 600 chars):")
            appendLine()
            appendLine("```")
            appendLine(s.text.take(600))
            appendLine("```")
            for (r in rows) {
                val pr = r.results.firstOrNull { it.sampleId == s.id } ?: continue
                appendLine()
                val b = panel
                if (b != null) {
                    val spread = b.cellSpread(s.id, r.model.id)?.let { ", spread ${f(it)}" } ?: ""
                    appendLine("**${r.model.id}** · panel ${f(b.cellPanel(s.id, r.model.id))} (raw ${f(b.cellPanelRaw(s.id, r.model.id))}, ${b.panelScores(s.id, r.model.id).size} judges$spread)")
                    appendLine()
                    b.allScores(s.id, r.model.id).forEach { (jid, v) ->
                        val tag = if (isSelfJudgment(jid, r.model.id)) " [self, not in panel]" else ""
                        appendLine("- ${judgeHeader(jid)}$tag: ${v.error?.let { "error: ${cell(it)}" } ?: "${scoreTriple(v)} — ${cell(v.note)}"}")
                    }
                } else {
                    val j = pr.judge?.let { " · judge ${f(it.average)} (${f(it.accuracy, 0)}/${f(it.fluency, 0)}/${f(it.terminology, 0)}) — ${it.note}" } ?: ""
                    appendLine("**${r.model.id}**$j")
                }
                appendLine()
                appendLine("```")
                appendLine(if (pr.output.unavailable) "(unavailable) ${pr.output.error ?: ""}" else (pr.output.text.take(600).ifBlank { "(no output) ${pr.output.error ?: ""}" }))
                appendLine("```")
            }
        }
        appendLine()
        appendLine("## Glossary misses, leaks and gender flags")
        for (r in rows) for (pr in r.results) {
            val m = pr.metrics ?: continue
            if (m.termMisses.isEmpty() && m.leakSnippets.isEmpty() && m.genderDetails.isEmpty() && m.refusal == null && pr.output.error == null) continue
            appendLine()
            appendLine("**${r.model.id} / ${pr.sampleId}** — adherence ${f(m.adherencePct, 0)}% of ${m.termsChecked} terms; ${m.cjkLeak} CJK chars; ${m.genderMismatches} pronoun mismatches in ${m.genderChecked} checked sentences; length ratio ${f(m.lengthRatio)}${if (m.probableOmission) " (PROBABLE OMISSION)" else ""}")
            if (m.termMisses.isNotEmpty()) appendLine("- misses: " + m.termMisses.joinToString("; "))
            if (m.leakSnippets.isNotEmpty()) appendLine("- leaked: " + m.leakSnippets.joinToString(" "))
            m.genderDetails.forEach { appendLine("- gender: ${cell(it)}") }
            m.refusal?.let { appendLine("- REFUSAL: ${cell(it)}") }
            pr.output.error?.let { appendLine("- error: ${cell(it)}") }
        }
        appendLine()
        appendLine("## Metric notes")
        appendLine()
        appendLine("- **Adherence**: share of bible terms whose source string occurs in the sample and whose English rendering appears in the output (case-insensitive, allows 's / plural). A correct but different rendering counts as a miss.")
        appendLine("- **CJK leaks**: Han/Hiragana/Katakana/Hangul characters left in the output.")
        appendLine("- **Para fidelity**: output paragraphs / source paragraphs (1.00 = one-to-one).")
        appendLine("- **Gender mismatches**: in output sentences naming exactly one bible character with a known gender, count of opposite-gender pronouns. Crude: the pronoun may refer to an unnamed or other person in the same sentence, so small non-zero counts are noise; compare models relative to each other.")
        appendLine("- **Length ratio**: English words / source chars; < 0.25 flags probable omission/summarizing (Korean source counts spaces, so its ratios run lower).")
        if (panel != null) {
            appendLine("- **Panel**: mean of the judges' accuracy / fluency / consistency (1–10) over every judge except the output's own model. **${baselineLabel}** is the v1 single judge alone (including its self-score), for comparison. **Rank vs ${baselineLabel}**: v1 rank → panel rank; ⚑ marks moves of 3+ places. Treat differences under ~0.5 as ties.")
        } else {
            appendLine("- **Judge**: ${cfg.judge.model} scores accuracy / fluency / terminology 1–10; a cheap model judging stronger ones is noisy — treat ±0.5 as a tie.")
        }
        appendLine()
        appendLine("## Spend")
        appendLine()
        appendLine("- This run: ${usd(runUsd)}")
        appendLine("- Ledger total (all runs): ${usd(ledgerUsd)} of cap \$${f(cfg.budgetUsd)}")
        bibles.values.forEach { appendLine("- Bible ${it.sampleId}: ${it.entries.size} entries by ${it.seededBy}, ${usd(it.usd)}") }
    }

    fun html(runId: String, runUsd: Double, ledgerUsd: Double, dryRun: Boolean): String = buildString {
        append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Translation Bench</title><style>
:root{--bg:#fbfbf9;--fg:#1d1d1b;--muted:#6b6b66;--line:#deddd6;--card:#ffffff;--good:#1f7a3a;--warn:#9a6700;--bad:#b42318;--chip:#efeee8}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--bg:#161615;--fg:#ecebe6;--muted:#a3a29c;--line:#34332f;--card:#1f1f1d;--good:#5cc27f;--warn:#e0b04a;--bad:#f07a6e;--chip:#2a2a27}}
:root[data-theme="dark"]{--bg:#161615;--fg:#ecebe6;--muted:#a3a29c;--line:#34332f;--card:#1f1f1d;--good:#5cc27f;--warn:#e0b04a;--bad:#f07a6e;--chip:#2a2a27}
body{background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;margin:0;padding:24px 16px}
main{max-width:1600px;margin:0 auto}h1{font-size:22px}h2{font-size:18px;margin-top:32px}h3{font-size:16px}
.muted{color:var(--muted)}.scroll{overflow-x:auto;border:1px solid var(--line);border-radius:8px;background:var(--card)}
table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid var(--line);padding:6px 10px;text-align:left;vertical-align:top;font-variant-numeric:tabular-nums}
th{font-weight:600;white-space:nowrap}.cols td{min-width:320px;max-width:420px}
.text{white-space:pre-wrap;font-size:14px}.chip{display:inline-block;background:var(--chip);border-radius:10px;padding:1px 8px;margin:0 4px 4px 0;font-size:12px}
.good{color:var(--good)}.warn{color:var(--warn)}.bad{color:var(--bad)}
</style></head><body><main>""")
        append("<h1>Translation benchmark ${esc(runId)}${if (dryRun) " — DRY RUN" else ""}</h1>")
        append("<p class=\"muted\">Spend this run ${usd(runUsd)} · ledger total ${usd(ledgerUsd)} of cap \$${f(cfg.budgetUsd)} · cost scaled to a ${cfg.chapterChars}-char chapter · target ≤ \$${fmt0(cfg.targetUsdPer100Chapters)}/100 chapters</p>")
        notes.forEach { append("<p class=\"muted\">${esc(it)}</p>") }
        val hj = panel?.judges.orEmpty()
        val hJudgeHead = if (panel != null) "<th>Panel raw</th><th>Judges</th><th>${esc(baselineLabel)}</th><th>Rank vs ${esc(baselineLabel)}</th>" + hj.joinToString("") { "<th>${esc(it.label)}</th>" } else ""
        append("<h2>Leaderboard</h2><div class=\"scroll\"><table><tr><th>#</th><th>Model</th><th>Provider</th><th>Reasoning</th><th>${if (panel != null) "Panel adj." else "Judge"}</th>$hJudgeHead<th>Adherence</th><th>CJK leaks</th><th>Para fidelity</th><th>Gender mism.</th><th>Refusals</th><th>Latency</th><th>\$/chapter</th><th>\$/100 ch</th><th>\$/1,000 ch</th><th>Target</th><th>Errors</th></tr>")
        for (r in rows) {
            if (r.unavailable) {
                append("<tr><td>–</td><td>${esc(r.model.id)}</td><td colspan=\"${14 + if (panel != null) 4 + hj.size else 0}\" class=\"bad\">${esc(r.status ?: "")} — ${esc(r.errors.firstOrNull() ?: "")}</td></tr>"); continue
            }
            val tgt = r.target(cfg)
            val hJudgeCells = if (panel != null) "<td>${f(r.panelRaw)}</td><td>${r.judgeCount}</td><td>${f(r.baselineAvg)}</td><td>${esc(rankDelta(r))}</td>" + hj.joinToString("") { j -> "<td>${if (isSelfJudgment(j.id, r.model.id)) "<span class=\"muted\">self</span>" else f(r.perJudge[j.id])}</td>" } else ""
            append("<tr><td>${panelRanks[r.model.id] ?: "–"}</td><td>${esc(r.model.id)}</td><td>${esc(r.model.provider)}</td><td>${esc(r.effort ?: "n/a")}</td><td><b>${f(r.judgeAvg)}</b></td>$hJudgeCells<td>${f(r.adherence, 0)}%</td><td>${r.leaks}</td><td>${f(r.fidelity)}</td><td>${r.genderMismatches}</td><td class=\"${if (r.refusals > 0) "bad" else ""}\">${r.refusals}</td><td>${f(r.latencySec, 1)} s</td><td>${usd(r.usdPerChapter)}</td><td>${usd(r.usdPer100)}</td><td>${usd(r.usdPer1000)}</td><td class=\"${if (tgt.startsWith("YES")) "good" else if (tgt.startsWith("stretch")) "warn" else "bad"}\">${esc(tgt)}</td><td>${r.errors.size}</td></tr>")
        }
        append("</table></div>")
        if (panel != null) append(agreementHtml())
        for (s in samples) {
            append("<h2>${esc(s.id)} — ${esc(s.title)}</h2><p class=\"muted\">${s.lang} · ${s.chars} chars · ${s.paragraphs.size} paragraphs · ${esc(s.origin)}</p>")
            val present = rows.mapNotNull { r -> r.results.firstOrNull { it.sampleId == s.id }?.let { r to it } }
            append("<div class=\"scroll\"><table class=\"cols\"><tr><th>Source</th>")
            present.forEach { (r, _) -> append("<th>${esc(r.model.id)}</th>") }
            append("</tr><tr><td><div class=\"text\">${esc(s.text)}</div></td>")
            for ((_, pr) in present) {
                append("<td>")
                val b = panel
                if (b != null) {
                    append("<span class=\"chip\">panel ${f(b.cellPanel(s.id, pr.modelId))}</span>")
                    b.cellSpread(s.id, pr.modelId)?.let { append("<span class=\"chip ${if (it >= 2.0) "warn" else ""}\">spread ${f(it)}</span>") }
                } else pr.judge?.let { append("<span class=\"chip\">judge ${f(it.average)}</span>") }
                pr.metrics?.let { m ->
                    append("<span class=\"chip\">adh ${f(m.adherencePct, 0)}%</span>")
                    append("<span class=\"chip ${if (m.cjkLeak > 0) "bad" else ""}\">leak ${m.cjkLeak}</span>")
                    append("<span class=\"chip ${if (m.paragraphFidelity !in 0.8..1.25) "warn" else ""}\">para ${f(m.paragraphFidelity)}</span>")
                    append("<span class=\"chip ${if (m.genderMismatches > 0) "warn" else ""}\">gender ${m.genderMismatches}</span>")
                    append("<span class=\"chip ${if (m.probableOmission) "bad" else ""}\">len ${f(m.lengthRatio)}</span>")
                }
                append("<span class=\"chip\">${usd(pr.output.usage.usd)} · ${f(pr.output.latencyMs / 1000.0, 1)} s</span>")
                val bn = panel
                if (bn != null) {
                    bn.allScores(s.id, pr.modelId).forEach { (jid, v) ->
                        val self = isSelfJudgment(jid, pr.modelId)
                        append("<div class=\"muted\"><b>${esc(judgeHeader(jid))}${if (self) " (self, excluded)" else ""}</b> ${v.error?.let { esc(it) } ?: "${esc(scoreTriple(v))} — ${esc(v.note)}"}</div>")
                    }
                } else pr.judge?.note?.takeIf { it.isNotBlank() }?.let { append("<div class=\"muted\">${esc(it)}</div>") }
                pr.output.error?.let { append("<div class=\"bad\">${esc(it)}</div>") }
                append("<div class=\"text\">${esc(pr.output.text)}</div></td>")
            }
            append("</tr></table></div>")
        }
        append("</main></body></html>")
    }

    /** HTML version of [agreementMarkdown]. */
    fun agreementHtml(): String = buildString {
        val b = panel ?: return@buildString
        val sampleIds = samples.map { it.id }
        append("<h2>What changed vs single-judge</h2><p>${esc(whatChanged())}</p>")
        append("<h2>Judge panel</h2><p class=\"muted\">Panel mean = mean over every judge except the output's own model. Blind rubric: source, story bible and translation, never the model name. Scores: accuracy / fluency / consistency, 1–10.</p>")
        append("<p class=\"muted\">Panel adj. removes each judge's leniency bias before averaging; it equals the raw mean where every judge scored the output and corrects outputs where a judge is missing (self-exclusion).</p>")
        append("<div class=\"scroll\"><table><tr><th>Judge</th><th>Kind</th><th>Outputs scored</th><th>Mean given</th><th>Leniency bias</th><th>Spend</th></tr>")
        for (j in b.judges) {
            val all = sampleIds.flatMap { s -> rows.mapNotNull { r -> b.allScores(s, r.model.id)[j.id] } }
            val valid = all.filter { it.average != null }
            val kind = when (j.kind) { PanelScore.KIND_LEGACY -> "LLM, v1 prompt (already paid)"; PanelScore.KIND_MANUAL -> "manual"; else -> "LLM, blind rubric" }
            append("<tr><td>${esc(j.label)}</td><td>$kind</td><td>${valid.size}</td><td>${f(valid.mapNotNull { it.average }.takeIf { it.isNotEmpty() }?.average())}</td><td>${b.judgeBias[j.id]?.let { (if (it >= 0) "+" else "") + f(it) } ?: "–"}</td><td>${usd(all.sumOf { it.usd })}</td></tr>")
        }
        append("</table></div>")
        val excluded = b.excluded.filter { e -> rows.any { it.model.id == e.modelId } }
        if (excluded.isNotEmpty()) {
            append("<p class=\"muted\">Self-judging exclusions: ")
            append(excluded.groupBy { it.judge to it.modelId }.entries.joinToString("; ") { (k, c) -> "${esc(k.first)} → ${esc(k.second)} (${c.joinToString { it.sampleId }})" })
            append("</p>")
        }
        append("<h2>Inter-judge agreement</h2><p class=\"muted\">Spearman rank correlation of each judge pair's model ranking (n = models both scored).</p>")
        val pairs = b.spearman()
        val js = b.judges.filter { j -> pairs.any { it.a == j.id || it.b == j.id } }
        append("<div class=\"scroll\"><table><tr><th></th>${js.joinToString("") { "<th>${esc(it.label)}</th>" }}</tr>")
        for (a in js) {
            append("<tr><th>${esc(a.label)}</th>")
            js.forEach { c ->
                val v = if (a.id == c.id) "1" else pairs.firstOrNull { (it.a == a.id && it.b == c.id) || (it.a == c.id && it.b == a.id) }?.let { "${f(it.rho)} <span class=\"muted\">n=${it.n}</span>" } ?: "–"
                append("<td>$v</td>")
            }
            append("</tr>")
        }
        append("</table></div>")
        val moves = rankMoves()
        append("<h3>Rank moves of 3+ places (${esc(baselineLabel)} → panel)</h3>")
        if (moves.isEmpty()) append("<p>None.</p>") else {
            append("<div class=\"scroll\"><table><tr><th>Model</th><th>${esc(baselineLabel)} rank</th><th>Panel rank</th><th>${esc(baselineLabel)}</th><th>Panel</th>${b.judges.joinToString("") { "<th>${esc(it.label)}</th>" }}</tr>")
            for (r in moves) append("<tr><td>${esc(r.model.id)}</td><td>${baselineRanks[r.model.id]}</td><td>${panelRanks[r.model.id]}</td><td>${f(r.baselineAvg)}</td><td>${f(r.judgeAvg)}</td>${b.judges.joinToString("") { j -> "<td>${if (isSelfJudgment(j.id, r.model.id)) "self" else f(r.perJudge[j.id])}</td>" }}</tr>")
            append("</table></div>")
        }
        append("<h3>Biggest disagreements</h3><div class=\"scroll\"><table><tr><th>Output</th><th>Spread</th><th>Panel</th><th>Judges</th></tr>")
        for ((k, spread) in b.disagreements(8)) {
            append("<tr><td>${esc(k.second)}<br><span class=\"muted\">${esc(k.first)}</span></td><td>${f(spread)}</td><td>${f(b.cellPanel(k.first, k.second))}</td><td>")
            b.panelScores(k.first, k.second).forEach { (j, v) -> append("<div><b>${esc(judgeHeader(j))}</b> ${esc(scoreTriple(v))} — ${esc(v.note)}</div>") }
            append("</td></tr>")
        }
        append("</table></div>")
    }

    fun write(dir: File, runId: String, runUsd: Double, ledgerUsd: Double, dryRun: Boolean) {
        dir.mkdirs()
        File(dir, "report.md").writeText(markdown(runId, runUsd, ledgerUsd, dryRun))
        File(dir, "report.html").writeText(html(runId, runUsd, ledgerUsd, dryRun))
        benchMapper.writeValue(File(dir, "metrics.json"), mapOf(
            "runId" to runId, "dryRun" to dryRun, "runUsd" to runUsd, "ledgerUsd" to ledgerUsd,
            "leaderboard" to rows.map { r ->
                mapOf("model" to r.model.id, "effort" to r.effort, "provider" to r.model.provider, "status" to r.status, "judgeAvg" to r.judgeAvg,
                    "panelRaw" to r.panelRaw, "baselineJudgeAvg" to r.baselineAvg, "perJudge" to r.perJudge, "panelRank" to panelRanks[r.model.id], "baselineRank" to baselineRanks[r.model.id],
                    "adherencePct" to r.adherence, "cjkLeaks" to r.leaks, "paragraphFidelity" to r.fidelity,
                    "genderMismatches" to r.genderMismatches, "latencySec" to r.latencySec, "usdSpent" to r.usdSpent,
                    "usdPerChapter" to r.usdPerChapter, "usdPer100Chapters" to r.usdPer100, "usdPer1000Chapters" to r.usdPer1000,
                    "meetsTarget" to r.target(cfg), "errors" to r.errors)
            },
            "results" to results,
            "judges" to panel?.let { b -> mapOf(
                "judges" to b.judges, "baseline" to b.baselineJudge, "judgeBias" to b.judgeBias, "spearman" to b.spearman(), "excluded" to b.excluded,
                "rankMoves" to rankMoves().map { it.model.id },
                "scores" to samples.flatMap { s -> rows.flatMap { r -> b.allScores(s.id, r.model.id).values } },
            ) },
        ))
    }
}
