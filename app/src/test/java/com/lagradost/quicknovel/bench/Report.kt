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
    val judgeAvg: Double?,
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
) {
    private val sampleChars = samples.associate { it.id to it.chars }

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
                judgeAvg = ok.mapNotNull { it.judge?.average }.takeIf { it.isNotEmpty() }?.average(),
                adherence = ok.mapNotNull { it.metrics?.adherencePct }.takeIf { it.isNotEmpty() }?.average(),
                leaks = ok.sumOf { it.metrics?.cjkLeak ?: 0 },
                fidelity = ok.mapNotNull { it.metrics?.paragraphFidelity }.takeIf { it.isNotEmpty() }?.average(),
                genderMismatches = ok.sumOf { it.metrics?.genderMismatches ?: 0 },
                refusals = rs.count { it.metrics?.refusal != null },
                latencySec = ok.map { it.output.latencyMs / 1000.0 }.takeIf { it.isNotEmpty() }?.average(),
                usdSpent = rs.sumOf { it.output.usage.usd + (it.judge?.usd ?: 0.0) },
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

    fun leaderboardMarkdown(): String = buildString {
        appendLine("| # | Model | Provider | Reasoning | Judge (1–10) | Adherence % | CJK leaks | Para fidelity | Gender mism. | Refusals | Latency s | $/chapter | $/100 ch | $/1,000 ch | Meets $${fmt0(cfg.targetUsdPer100Chapters)}/100 | Errors |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { i, r ->
            if (r.unavailable) {
                appendLine("| – | ${r.model.id} | ${r.model.provider} | – | ${r.status} | | | | | | | | | | n/a | ${cell(r.errors.firstOrNull() ?: "")} |")
            } else {
                appendLine("| ${i + 1} | ${r.model.id} | ${r.model.provider} | ${r.effort ?: "n/a"} | ${f(r.judgeAvg)} | ${f(r.adherence, 0)} | ${r.leaks} | ${f(r.fidelity)} | ${r.genderMismatches} | ${r.refusals} | ${f(r.latencySec, 1)} | ${usd(r.usdPerChapter)} | ${usd(r.usdPer100)} | ${usd(r.usdPer1000)} | ${r.target(cfg)} | ${r.errors.size} |")
            }
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
            val j = r.results.sumOf { it.judge?.usd ?: 0.0 }
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
                val j = pr.judge?.let { " · judge ${f(it.average)} (${f(it.accuracy, 0)}/${f(it.fluency, 0)}/${f(it.terminology, 0)}) — ${it.note}" } ?: ""
                appendLine("**${r.model.id}**$j")
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
        appendLine("- **Judge**: ${cfg.judge.model} scores accuracy / fluency / terminology 1–10; a cheap model judging stronger ones is noisy — treat ±0.5 as a tie.")
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
        append("<h2>Leaderboard</h2><div class=\"scroll\"><table><tr><th>Model</th><th>Provider</th><th>Reasoning</th><th>Judge</th><th>Adherence</th><th>CJK leaks</th><th>Para fidelity</th><th>Gender mism.</th><th>Refusals</th><th>Latency</th><th>\$/chapter</th><th>\$/100 ch</th><th>\$/1,000 ch</th><th>Target</th><th>Errors</th></tr>")
        for (r in rows) {
            if (r.unavailable) {
                append("<tr><td>${esc(r.model.id)}</td><td colspan=\"14\" class=\"bad\">${esc(r.status ?: "")} — ${esc(r.errors.firstOrNull() ?: "")}</td></tr>"); continue
            }
            val tgt = r.target(cfg)
            append("<tr><td>${esc(r.model.id)}</td><td>${esc(r.model.provider)}</td><td>${esc(r.effort ?: "n/a")}</td><td>${f(r.judgeAvg)}</td><td>${f(r.adherence, 0)}%</td><td>${r.leaks}</td><td>${f(r.fidelity)}</td><td>${r.genderMismatches}</td><td class=\"${if (r.refusals > 0) "bad" else ""}\">${r.refusals}</td><td>${f(r.latencySec, 1)} s</td><td>${usd(r.usdPerChapter)}</td><td>${usd(r.usdPer100)}</td><td>${usd(r.usdPer1000)}</td><td class=\"${if (tgt.startsWith("YES")) "good" else if (tgt.startsWith("stretch")) "warn" else "bad"}\">${esc(tgt)}</td><td>${r.errors.size}</td></tr>")
        }
        append("</table></div>")
        for (s in samples) {
            append("<h2>${esc(s.id)} — ${esc(s.title)}</h2><p class=\"muted\">${s.lang} · ${s.chars} chars · ${s.paragraphs.size} paragraphs · ${esc(s.origin)}</p>")
            val present = rows.mapNotNull { r -> r.results.firstOrNull { it.sampleId == s.id }?.let { r to it } }
            append("<div class=\"scroll\"><table class=\"cols\"><tr><th>Source</th>")
            present.forEach { (r, _) -> append("<th>${esc(r.model.id)}</th>") }
            append("</tr><tr><td><div class=\"text\">${esc(s.text)}</div></td>")
            for ((_, pr) in present) {
                append("<td>")
                pr.judge?.let { append("<span class=\"chip\">judge ${f(it.average)}</span>") }
                pr.metrics?.let { m ->
                    append("<span class=\"chip\">adh ${f(m.adherencePct, 0)}%</span>")
                    append("<span class=\"chip ${if (m.cjkLeak > 0) "bad" else ""}\">leak ${m.cjkLeak}</span>")
                    append("<span class=\"chip ${if (m.paragraphFidelity !in 0.8..1.25) "warn" else ""}\">para ${f(m.paragraphFidelity)}</span>")
                    append("<span class=\"chip ${if (m.genderMismatches > 0) "warn" else ""}\">gender ${m.genderMismatches}</span>")
                    append("<span class=\"chip ${if (m.probableOmission) "bad" else ""}\">len ${f(m.lengthRatio)}</span>")
                }
                append("<span class=\"chip\">${usd(pr.output.usage.usd)} · ${f(pr.output.latencyMs / 1000.0, 1)} s</span>")
                pr.judge?.note?.takeIf { it.isNotBlank() }?.let { append("<div class=\"muted\">${esc(it)}</div>") }
                pr.output.error?.let { append("<div class=\"bad\">${esc(it)}</div>") }
                append("<div class=\"text\">${esc(pr.output.text)}</div></td>")
            }
            append("</tr></table></div>")
        }
        append("</main></body></html>")
    }

    fun write(dir: File, runId: String, runUsd: Double, ledgerUsd: Double, dryRun: Boolean) {
        dir.mkdirs()
        File(dir, "report.md").writeText(markdown(runId, runUsd, ledgerUsd, dryRun))
        File(dir, "report.html").writeText(html(runId, runUsd, ledgerUsd, dryRun))
        benchMapper.writeValue(File(dir, "metrics.json"), mapOf(
            "runId" to runId, "dryRun" to dryRun, "runUsd" to runUsd, "ledgerUsd" to ledgerUsd,
            "leaderboard" to rows.map { r ->
                mapOf("model" to r.model.id, "effort" to r.effort, "provider" to r.model.provider, "status" to r.status, "judgeAvg" to r.judgeAvg,
                    "adherencePct" to r.adherence, "cjkLeaks" to r.leaks, "paragraphFidelity" to r.fidelity,
                    "genderMismatches" to r.genderMismatches, "latencySec" to r.latencySec, "usdSpent" to r.usdSpent,
                    "usdPerChapter" to r.usdPerChapter, "usdPer100Chapters" to r.usdPer100, "usdPer1000Chapters" to r.usdPer1000,
                    "meetsTarget" to r.target(cfg), "errors" to r.errors)
            },
            "results" to results,
        ))
    }
}
