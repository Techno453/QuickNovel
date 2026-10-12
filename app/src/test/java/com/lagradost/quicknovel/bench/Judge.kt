package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

/** v1 single-judge score, stored inside [PairResult.judge]; `terminology` is the v1 name of `consistency`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JudgeScore(
    val accuracy: Double?,
    val fluency: Double?,
    val terminology: Double?,
    val note: String,
    val usd: Double,
    val error: String? = null,
) {
    val average: Double?
        get() = listOfNotNull(accuracy, fluency, terminology).takeIf { it.size == 3 }?.average()
}

/** One judge's score of one (sample, model) output. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PanelScore(
    val judge: String,
    val sampleId: String,
    val modelId: String,
    /** [PairResult.runId] of the translation that was judged; a newer translation invalidates the score. */
    val translationRunId: String = "",
    val accuracy: Double? = null,
    val fluency: Double? = null,
    val consistency: Double? = null,
    val note: String = "",
    val usd: Double = 0.0,
    val error: String? = null,
    /** llm | legacy (v1 single-judge score) | manual */
    val kind: String = KIND_LLM,
    /** Hash of the exact prompt (rubric, bible, source, translation); a changed prompt makes the score stale. */
    val promptHash: String = "",
) {
    @get:JsonIgnore
    val average: Double?
        get() = if (error != null) null else listOfNotNull(accuracy, fluency, consistency).takeIf { it.size == 3 }?.average()

    companion object {
        const val KIND_LLM = "llm"
        const val KIND_LEGACY = "legacy"
        const val KIND_MANUAL = "manual"
    }
}

/** `judges` block of bench.config.json: the LLM judge panel (in addition to `judge.model`). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class JudgePanelSpec(
    val enabled: Boolean = true,
    /** Judge models, same fields as `models` entries (provider, prices, keyName, headers, extraBody). */
    val panel: List<ModelSpec> = emptyList(),
    /** Sent where the provider accepts it (dropped automatically when rejected). */
    val temperature: Double? = 0.0,
    val maxCompletionTokens: Int = 300,
    /** Estimated judge prompt overhead (rubric + bible) in tokens, for budget projections. */
    val promptOverheadTokens: Int = 900,
)

/** Model ids compared without provider prefix and case: `google/gemini-3.1-flash-lite` == `gemini-3.1-flash-lite`. */
fun normalizeModelId(id: String): String = id.trim().lowercase().substringAfterLast('/')

/** A judge never scores its own model's output. */
fun isSelfJudgment(judgeId: String, modelId: String): Boolean = normalizeModelId(judgeId) == normalizeModelId(modelId)

/** Blind, rubric-based LLM judge: sees source, story bible and translation, never the model name. */
class Judge(private val api: BenchOpenAi, val model: ModelSpec, private val spec: JudgePanelSpec = JudgePanelSpec()) {

    fun score(sample: Sample, bible: Bible, translation: String, modelId: String, translationRunId: String): PanelScore {
        val (system, user) = prompt(sample, bible, translation)
        val hash = promptHash(system, user)
        val tag = "${sample.id}:$modelId"
        var reply = api.chat(model, "judge", tag, system, user, spec.maxCompletionTokens, jsonObject = true, temperature = spec.temperature)
        var usd = reply.call?.usd ?: 0.0
        if (reply.error != null && reply.error!!.contains("response_format", ignoreCase = true)) {
            // Provider rejects JSON mode: ask again without it (rejected requests cost nothing).
            reply = api.chat(model, "judge", tag, system, user, spec.maxCompletionTokens, jsonObject = false, temperature = spec.temperature)
            usd += reply.call?.usd ?: 0.0
        }
        val base = PanelScore(model.id, sample.id, modelId, translationRunId, usd = usd, promptHash = hash)
        if (reply.error != null) return base.copy(error = reply.error)
        val parsed = parseScores(reply.content) ?: return base.copy(error = "judge reply unparseable: ${Secrets.scrub(reply.content.take(160))}")
        return base.copy(accuracy = parsed.accuracy, fluency = parsed.fluency, consistency = parsed.consistency, note = parsed.note)
    }

    companion object {
        data class Parsed(val accuracy: Double, val fluency: Double, val consistency: Double, val note: String)

        fun promptHash(system: String, user: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest((system + "\u0000" + user).toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }

        fun prompt(sample: Sample, bible: Bible, translation: String): Pair<String, String> {
            val system = "You are a strict bilingual editor grading an English machine translation of a web-novel excerpt. " +
                    "You are not told which system produced it. Reply with a single JSON object only."
            val user = """
Grade this English translation of a ${BibleSeeder.langName(sample.lang)} web-novel excerpt against the source and the story bible the translator was given.

Score each criterion with an integer from 1 to 10:
- accuracy: the meaning of every sentence is preserved; nothing omitted, summarized, added or invented.
- fluency: natural, readable English prose.
- consistency: names and terms are rendered as in the story bible, the same way every time; pronouns match each character's gender in the bible. Capitalization-only differences do not count. Terms the bible does not list are judged under accuracy, not consistency.
Anchors: 9-10 publishable as is; 7-8 minor slips; 5-6 noticeable errors; 3-4 major errors or omissions; 1-2 unusable (refusal, untranslated, mostly missing).

Reply with exactly: {"accuracy": n, "fluency": n, "consistency": n, "note": "<one sentence naming the most important problem>"}

Story bible (source = English; category; gender; role):
${bibleLines(bible)}

Source:
${sample.text}

Translation:
$translation
""".trimIndent()
            return system to user
        }

        fun bibleLines(bible: Bible): String = bible.entries.filterNot { it.inferred }.joinToString("\n") { e ->
            buildString {
                append("${e.source} = ${e.english}; ${e.category}")
                if (e.category == "character") append("; ${e.gender}")
                if (e.role.isNotBlank()) append("; ${e.role}")
                if (e.aliases.isNotEmpty()) append("; also written ${e.aliases.joinToString(", ")}")
            }
        }.ifBlank { "(empty)" }

        private val FENCE = Regex("""^```(?:json)?\s*|\s*```$""", RegexOption.IGNORE_CASE)

        /** Tolerates code fences, prose around the object, string numbers ("8", "8/10") and `terminology` for consistency. */
        fun parseScores(content: String): Parsed? {
            val text = content.trim().replace(FENCE, "").trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            val node: JsonNode? = if (start >= 0 && end > start) runCatching { benchMapper.readTree(text.substring(start, end + 1)) }.getOrNull() else null
            fun fromNode(f: String): Double? = node?.path(f)?.takeIf { !it.isMissingNode && !it.isNull }?.asText()?.let(::num)
            fun fromRegex(f: String): Double? =
                Regex(""""?$f"?\s*[:=]\s*"?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            fun field(vararg names: String): Double? = names.firstNotNullOfOrNull { fromNode(it) ?: fromRegex(it) }?.coerceIn(1.0, 10.0)
            val a = field("accuracy") ?: return null
            val f = field("fluency") ?: return null
            val c = field("consistency", "terminology") ?: return null
            val note = node?.path("note")?.asText("")?.takeIf { it.isNotBlank() }
                ?: Regex(""""note"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1).orEmpty()
            return Parsed(a, f, c, note.trim())
        }

        private fun num(s: String): Double? = Regex("""\d+(?:\.\d+)?""").find(s)?.value?.toDoubleOrNull()
    }
}

/** Panel scores per (judge, sample, model), so `-Dbench.rejudge` only pays for missing cells. */
class JudgeStore(private val dir: File) {
    private fun safe(s: String) = s.replace(Regex("""[^\w.-]"""), "_")
    private fun file(judge: String, sampleId: String, modelId: String) = File(dir, "${safe(judge)}/$sampleId/${safe(modelId)}.json")

    @Synchronized
    fun save(s: PanelScore) {
        val f = file(s.judge, s.sampleId, s.modelId)
        f.parentFile.mkdirs()
        benchMapper.writeValue(f, s)
    }

    fun load(judge: String, sampleId: String, modelId: String): PanelScore? =
        file(judge, sampleId, modelId).takeIf { it.isFile }?.let { runCatching { benchMapper.readValue<PanelScore>(it) }.getOrNull() }

    fun loadAll(judges: Collection<String>, results: List<PairResult>): List<PanelScore> =
        judges.flatMap { j -> results.mapNotNull { r -> load(j, r.sampleId, r.modelId)?.takeIf { it.translationRunId == r.runId } } }
}

/** One row of `manual-judges/<name>.json`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ManualScore(
    val sample: String = "",
    val model: String = "",
    val accuracy: Double? = null,
    val fluency: Double? = null,
    val consistency: Double? = null,
    val note: String = "",
)

/** Human (or offline) judges: every `<dir>/<name>.json` holding an array of [ManualScore] is one judge named `<name>`. */
object ManualJudges {
    fun load(dir: File, notes: MutableList<String>? = null): Map<String, List<PanelScore>> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: return emptyMap()
        return files.associate { f ->
            val name = f.nameWithoutExtension
            val rows = runCatching { benchMapper.readValue<List<ManualScore>>(f) }.getOrElse {
                notes?.add("Manual judge file ${f.name} ignored: ${it.message?.take(160)}")
                emptyList()
            }
            name to rows.filter { it.sample.isNotBlank() && it.model.isNotBlank() }.map { r ->
                PanelScore(name, r.sample.trim(), r.model.trim(), "", r.accuracy?.coerceIn(1.0, 10.0), r.fluency?.coerceIn(1.0, 10.0),
                    r.consistency?.coerceIn(1.0, 10.0), r.note, 0.0, null, PanelScore.KIND_MANUAL)
            }
        }.filterValues { it.isNotEmpty() }
    }
}

data class JudgeInfo(val id: String, val kind: String) {
    val label: String
        get() = when (kind) {
            PanelScore.KIND_MANUAL -> "$id (manual)"
            PanelScore.KIND_LEGACY -> "$id (v1)"
            else -> id
        }
}

data class SpearmanPair(val a: String, val b: String, val rho: Double?, val n: Int)

data class ExcludedCell(val judge: String, val sampleId: String, val modelId: String)

/**
 * All judges' scores for a report: panel mean per (sample, model) over every judge except the
 * model's own (self-judging exclusion), spread, per-judge means and rank agreement.
 */
class JudgeBoard(
    val judges: List<JudgeInfo>,
    scores: List<PanelScore>,
    /** The v1 single judge (luna); its column includes its self-score, for comparison with the old leaderboard. */
    val baselineJudge: String?,
    private val sampleIds: List<String>,
    private val modelIds: List<String>,
) {
    private val byCell: Map<Pair<String, String>, Map<String, PanelScore>> =
        scores.filter { s -> judges.any { it.id == s.judge } }
            .groupBy { it.sampleId to it.modelId }
            .mapValues { (_, list) -> list.associateBy { it.judge } }

    val isEmpty: Boolean get() = byCell.values.none { cell -> cell.values.any { it.average != null } }

    /** Every judge's raw score of a cell, including its own model's. */
    fun allScores(sampleId: String, modelId: String): Map<String, PanelScore> = byCell[sampleId to modelId].orEmpty()

    /** Valid scores that count toward the panel (self-judgments removed). */
    fun panelScores(sampleId: String, modelId: String): Map<String, PanelScore> =
        allScores(sampleId, modelId).filter { (j, s) -> s.average != null && !isSelfJudgment(j, modelId) }

    /** Raw panel mean of an output: plain mean over the judges that count (self excluded). */
    fun cellPanelRaw(sampleId: String, modelId: String): Double? =
        panelScores(sampleId, modelId).values.mapNotNull { it.average }.takeIf { it.isNotEmpty() }?.average()

    /**
     * Each judge's leniency: mean of (its score - mean of the other judges' scores) over the outputs it
     * shares with at least one other judge, scaled to a bias b_j (sum of b_j = 0 when every judge scores
     * every output). Without it, excluding a harsh judge from its own model's outputs would lift that model.
     */
    val judgeBias: Map<String, Double> by lazy {
        val n = judges.size.coerceAtLeast(2)
        judges.associate { j ->
            val diffs = sampleIds.flatMap { s ->
                modelIds.mapNotNull { m ->
                    val sc = panelScores(s, m)
                    val mine = sc[j.id]?.average ?: return@mapNotNull null
                    val others = sc.filterKeys { it != j.id }.values.mapNotNull { it.average }
                    if (others.isEmpty()) null else mine - others.average()
                }
            }
            j.id to (diffs.takeIf { it.isNotEmpty() }?.average()?.times((n - 1.0) / n) ?: 0.0)
        }
    }

    /**
     * Panel mean of an output after removing each judge's leniency bias. Equals the raw mean when all
     * judges scored the output; corrects outputs where a judge is missing (self-exclusion, partial manual files).
     */
    fun cellPanel(sampleId: String, modelId: String): Double? =
        panelScores(sampleId, modelId).mapNotNull { (j, s) -> s.average?.minus(judgeBias[j] ?: 0.0) }
            .takeIf { it.isNotEmpty() }?.average()?.coerceIn(1.0, 10.0)

    fun cellSpread(sampleId: String, modelId: String): Double? =
        panelScores(sampleId, modelId).values.mapNotNull { it.average }.takeIf { it.size >= 2 }?.let { it.max() - it.min() }

    /** Cells a judge did not score because the contestant is its own model. */
    val excluded: List<ExcludedCell> by lazy {
        judges.flatMap { j ->
            modelIds.filter { isSelfJudgment(j.id, it) }.flatMap { m -> sampleIds.map { s -> ExcludedCell(j.id, s, m) } }
        }
    }

    fun panelMean(modelId: String): Double? =
        sampleIds.mapNotNull { cellPanel(it, modelId) }.takeIf { it.isNotEmpty() }?.average()

    fun panelMeanRaw(modelId: String): Double? =
        sampleIds.mapNotNull { cellPanelRaw(it, modelId) }.takeIf { it.isNotEmpty() }?.average()

    /** Judge's mean over samples; self-judgments are excluded unless [includeSelf]. */
    fun judgeMean(judgeId: String, modelId: String, includeSelf: Boolean = false): Double? {
        if (!includeSelf && isSelfJudgment(judgeId, modelId)) return null
        return sampleIds.mapNotNull { allScores(it, modelId)[judgeId]?.average }.takeIf { it.isNotEmpty() }?.average()
    }

    fun baselineMean(modelId: String): Double? = baselineJudge?.let { judgeMean(it, modelId, includeSelf = true) }

    fun judgeCount(modelId: String): Int = judges.count { j -> sampleIds.any { panelScores(it, modelId).containsKey(j.id) } }

    /** Spearman rank correlation of the model rankings of each judge pair, over models both judges scored. */
    fun spearman(): List<SpearmanPair> {
        val active = judges.filter { j -> modelIds.any { judgeMean(j.id, it) != null } }
        return active.flatMapIndexed { i, a ->
            active.drop(i + 1).map { b ->
                val common = modelIds.filter { judgeMean(a.id, it) != null && judgeMean(b.id, it) != null }
                SpearmanPair(a.id, b.id,
                    Stats.spearman(common.map { judgeMean(a.id, it)!! }, common.map { judgeMean(b.id, it)!! }), common.size)
            }
        }
    }

    /** Cells with the largest spread between panel judges. */
    fun disagreements(limit: Int): List<Pair<Pair<String, String>, Double>> =
        sampleIds.flatMap { s -> modelIds.mapNotNull { m -> cellSpread(s, m)?.let { (s to m) to it } } }
            .sortedByDescending { it.second }.take(limit)

    companion object {
        /** Builds the board from stored results (v1 scores), the panel store and manual files. */
        fun build(
            cfg: BenchConfig, results: List<PairResult>, store: JudgeStore?, manual: Map<String, List<PanelScore>>,
            sampleIds: List<String>, modelIds: List<String>,
        ): JudgeBoard {
            val llm = llmJudgeIds(cfg)
            val stored = store?.loadAll(llm, results).orEmpty()
            val baseline = cfg.judge.model
            // v1 scores in PairResult.judge count as the baseline judge's, unless a panel score replaced them.
            val legacy = results.mapNotNull { r ->
                val j = r.judge ?: return@mapNotNull null
                if (j.average == null || stored.any { it.judge == baseline && it.sampleId == r.sampleId && it.modelId == r.modelId && it.average != null }) return@mapNotNull null
                PanelScore(baseline, r.sampleId, r.modelId, r.runId, j.accuracy, j.fluency, j.terminology, j.note, j.usd, j.error, PanelScore.KIND_LEGACY)
            }
            val manualScores = manual.values.flatten()
            val all = stored + legacy + manualScores
            val judges = llm.map { id ->
                val kinds = all.filter { it.judge == id }.map { it.kind }.toSet()
                JudgeInfo(id, if (kinds == setOf(PanelScore.KIND_LEGACY)) PanelScore.KIND_LEGACY else PanelScore.KIND_LLM)
            } + manual.keys.filter { it !in llm }.map { JudgeInfo(it, PanelScore.KIND_MANUAL) }
            return JudgeBoard(judges, all, baseline.takeIf { b -> all.any { it.judge == b } }, sampleIds, modelIds)
        }

        /** `judge.model` first (the v1 judge), then the `judges.panel` models. */
        fun llmJudgeIds(cfg: BenchConfig): List<String> = (listOf(cfg.judge.model) + cfg.judges.panel.map { it.id }).distinct()
    }
}

object Stats {
    /** Average ranks (1 = highest value), ties share the mean rank. */
    fun ranksDescending(values: List<Double>): List<Double> {
        val order = values.indices.sortedByDescending { values[it] }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val r = (i + j) / 2.0 + 1
            for (k in i..j) ranks[order[k]] = r
            i = j + 1
        }
        return ranks.toList()
    }

    /** Spearman's rho as the Pearson correlation of average ranks (handles ties); null if undefined. */
    fun spearman(a: List<Double>, b: List<Double>): Double? {
        if (a.size != b.size || a.size < 3) return null
        val ra = ranksDescending(a)
        val rb = ranksDescending(b)
        val ma = ra.average()
        val mb = rb.average()
        val cov = ra.indices.sumOf { (ra[it] - ma) * (rb[it] - mb) }
        val va = ra.sumOf { (it - ma) * (it - ma) }
        val vb = rb.sumOf { (it - mb) * (it - mb) }
        if (va == 0.0 || vb == 0.0) return null
        return cov / kotlin.math.sqrt(va * vb)
    }
}
