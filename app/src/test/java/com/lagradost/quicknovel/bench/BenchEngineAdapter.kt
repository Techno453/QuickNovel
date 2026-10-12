package com.lagradost.quicknovel.bench

import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Token usage summed over every HTTP call one translation made. */
data class Usage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val usd: Double = 0.0,
    val calls: Int = 0,
)

data class BenchOutput(
    /** Plain English text, one paragraph per line. */
    val text: String,
    val usage: Usage,
    val latencyMs: Long,
    val error: String? = null,
    val finishReasons: List<String> = emptyList(),
    val httpErrors: List<String> = emptyList(),
    val effectiveEffort: String? = null,
    val newTermMarkers: Int = 0,
    val chunks: Int = 0,
    val unavailable: Boolean = false,
    val budgetHit: Boolean = false,
    val noKey: Boolean = false,
)

/** [keys] resolves an API key by name (OPENAI_API_KEY, OPENROUTER_API_KEY, ...); [baseUrl] is the default endpoint. */
data class RunOptions(val keys: (String) -> String?, val baseUrl: String, val ledger: CostLedger)

interface BenchTranslator {
    suspend fun translate(sample: Sample, bible: Bible, model: ModelSpec, options: RunOptions): BenchOutput
}

/**
 * The ONLY place the bench touches the app's translator. When the engine's API changes (story
 * bible with gender/notes, context object, ===TERMS===/===SUMMARY=== tail), update this file.
 *
 * Per-model request fields (OpenRouter `reasoning`, `"temperature": null`) go through the engine's
 * `extraBody`. OpenAI reasoning_effort (with automatic step-down when rejected),
 * max_completion_tokens and cost metering are done by [MeteringInterceptor] on the OkHttp client
 * the engine is given.
 */
class BenchEngineAdapter(private val baseClient: OkHttpClient = TranslationEngine.defaultClient) : BenchTranslator {
    private val states = java.util.concurrent.ConcurrentHashMap<String, ParamState>()

    override suspend fun translate(sample: Sample, bible: Bible, model: ModelSpec, options: RunOptions): BenchOutput {
        val apiKey = options.keys(model.keyName)
            ?: return BenchOutput("", Usage(), 0, "no key (${model.keyName})", noKey = true)
        val state = states.getOrPut(model.id) { ParamState(model) }
        val meter = MeteringInterceptor(options.ledger, model, state, "translate", sample.id)
        val client = baseClient.newBuilder().addInterceptor(meter).build()
        val engine = TranslationEngine(
            apiKey = apiKey,
            model = model.id,
            baseUrl = model.baseUrlOr(options.baseUrl),
            sourceLang = sample.lang,
            client = client,
            extraBody = model.extraBody,
        )
        val t0 = System.nanoTime()
        var text = ""
        var error: String? = null
        var pending = 0
        var chunks = 0
        try {
            val result = engine.translateChapter(
                sourceText = sample.text,
                novelId = "bench-${sample.id}",
                chapterNum = 1,
                glossaryTerms = toGlossary(bible),
            )
            text = htmlToLines(result.englishText)
            pending = result.pendingTerms.size
            chunks = result.chunkCount
        } catch (e: Exception) {
            error = Secrets.scrub("${e.javaClass.simpleName}: ${e.message}")
        }
        val latency = (System.nanoTime() - t0) / 1_000_000
        val calls = meter.calls()
        val ok = calls.filter { it.status in 200..299 }
        return BenchOutput(
            text = text,
            usage = Usage(
                ok.sumOf { it.promptTokens }, ok.sumOf { it.completionTokens }, ok.sumOf { it.reasoningTokens },
                ok.sumOf { it.usd }, ok.size
            ),
            latencyMs = latency,
            error = error ?: if (text.isBlank()) "empty output" else null,
            finishReasons = ok.mapNotNull { it.finishReason },
            httpErrors = calls.filter { it.status !in 200..299 }.map { "HTTP ${it.status}: ${it.error}" },
            effectiveEffort = state.effort,
            newTermMarkers = pending,
            chunks = chunks,
            unavailable = meter.unavailable != null,
            budgetHit = meter.budgetHit != null,
        )
    }

    fun adjustments(modelId: String): List<String> = states[modelId]?.adjustments?.toList().orEmpty()

    companion object {
        /** Current engine API: a flat List<GlossaryTerm>; gender/role travel in the description. */
        fun toGlossary(bible: Bible): List<GlossaryTerm> = bible.entries.map { e ->
            GlossaryTerm(
                novelId = "bench-${bible.sampleId}",
                source = e.source,
                english = e.english,
                category = e.category,
                description = listOfNotNull(
                    e.gender.takeIf { e.category == "character" && it != "unknown" },
                    e.role.takeIf { it.isNotBlank() }
                ).joinToString("; "),
                aliases = benchMapper.writeValueAsString(e.aliases),
            )
        }

        /** The engine returns `<p>…</p>` lines; the bench works on plain paragraphs. */
        fun htmlToLines(html: String): String =
            Jsoup.parseBodyFragment(html).select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                .ifEmpty { html.lines().map { it.trim() }.filter { it.isNotEmpty() } }
                .joinToString("\n")
    }
}

/** Dry-run stand-in: no network; returns a fake translation that exercises every metric. */
class FakeTranslator : BenchTranslator {
    override suspend fun translate(sample: Sample, bible: Bible, model: ModelSpec, options: RunOptions): BenchOutput {
        val text = sample.paragraphs.mapIndexed { i, p ->
            when {
                i == 0 -> bible.characters.firstOrNull { it.gender == "male" || it.gender == "female" }.let { c ->
                    "${c?.english ?: "Hero"} opened ${if (c?.gender == "female") "his" else "her"} eyes. (dry run: deliberately wrong pronoun, ${p.length} source chars)"
                }
                i == 1 -> "Leaked: ${p.take(6)}"
                else -> "This is placeholder paragraph ${i + 1} standing in for a translation of the source text."
            }
        }.joinToString("\n")
        return BenchOutput(text, Usage(), 1, null, listOf("stop"), effectiveEffort = model.reasoningEffort, chunks = 1)
    }
}
