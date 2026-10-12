package com.lagradost.quicknovel.bench

import com.lagradost.quicknovel.translator.PromptProfiles
import com.lagradost.quicknovel.translator.PromptTemplates
import com.lagradost.quicknovel.translator.StoryContext
import com.lagradost.quicknovel.translator.TranslationEngine
import com.lagradost.quicknovel.translator.TranslationOptions
import com.lagradost.quicknovel.translator.db.GlossaryTerm
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
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

/**
 * [keys] resolves an API key by name (OPENAI_API_KEY, OPENROUTER_API_KEY, ...); [baseUrl] is the
 * default endpoint; [engineOptions] are the engine's strict-mode / context switches.
 */
data class RunOptions(
    val keys: (String) -> String?,
    val baseUrl: String,
    val ledger: CostLedger,
    val engineOptions: TranslationOptions = TranslationOptions(),
)

interface BenchTranslator {
    suspend fun translate(sample: Sample, bible: Bible, model: ModelSpec, options: RunOptions): BenchOutput
}

/**
 * The ONLY place the bench touches the app's translator: it calls the story-bible engine
 * (`translateChapter(..., context = StoryContext, options = TranslationOptions)`) exactly as the
 * app does, with the bench bible mapped onto `GlossaryTerm`s (gender, role as notes) and its
 * premise in the context.
 *
 * Per-model request fields (OpenRouter `reasoning`, `"temperature": null`) go through the engine's
 * `extraBody` / `sendTemperature` (from the app preset when the model references one). OpenAI
 * reasoning_effort (with automatic step-down when rejected), max_completion_tokens and cost
 * metering are done by [MeteringInterceptor] on the OkHttp client the engine is given.
 *
 * @param transport optional interceptor placed after the meter that answers instead of the
 *   network (dry-run smoke test, unit tests).
 */
class BenchEngineAdapter(
    private val baseClient: OkHttpClient = TranslationEngine.defaultClient,
    private val transport: Interceptor? = null,
) : BenchTranslator {
    private val states = java.util.concurrent.ConcurrentHashMap<String, ParamState>()

    override suspend fun translate(sample: Sample, bible: Bible, model: ModelSpec, options: RunOptions): BenchOutput {
        val apiKey = options.keys(model.keyName)
            ?: return BenchOutput("", Usage(), 0, "no key (${model.keyName})", noKey = true)
        val state = states.getOrPut(model.id) { ParamState(model) }
        val meter = MeteringInterceptor(options.ledger, model, state, "translate", sample.id)
        val client = baseClient.newBuilder().addInterceptor(meter)
            .apply { transport?.let { addInterceptor(it) } }
            .build()
        val engine = TranslationEngine(
            apiKey = apiKey,
            model = model.apiModel,
            baseUrl = model.baseUrlOr(options.baseUrl),
            sourceLang = sample.lang,
            client = client,
            extraBody = model.extraBody,
            sendTemperature = model.sendTemperature != false,
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
                context = toContext(bible),
                // The model's prompt profile goes through the engine options, as an override of the engine's own.
                options = options.engineOptions.copy(promptProfile = model.profileId),
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
        /** Story Bible terms: gender (characters) and role (as notes) go into the bible block of the prompt. */
        fun toGlossary(bible: Bible): List<GlossaryTerm> = bible.entries.map { e ->
            GlossaryTerm(
                novelId = "bench-${bible.sampleId}",
                source = e.source,
                english = e.english,
                category = GlossaryTerm.normalizeCategory(e.category),
                gender = if (e.category == GlossaryTerm.CATEGORY_CHARACTER) GlossaryTerm.normalizeGender(e.gender) else GlossaryTerm.GENDER_UNKNOWN,
                notes = e.role.trim(),
                aliases = benchMapper.writeValueAsString(e.aliases),
                locked = true,
            )
        }

        /** Continuity context for a single sample: the bible's premise, default style for the language. */
        fun toContext(bible: Bible): StoryContext = StoryContext(premise = bible.premise.trim())

        /** The engine returns `<p>…</p>` lines; the bench works on plain paragraphs. */
        fun htmlToLines(html: String): String =
            Jsoup.parseBodyFragment(html).select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
                .ifEmpty { html.lines().map { it.trim() }.filter { it.isNotEmpty() } }
                .joinToString("\n")
    }
}

/**
 * Local stand-in for a chat completions endpoint (placed after the meter): records every request
 * body and answers with a short protocol-conforming reply and zero usage. No network.
 */
class FakeChatTransport : Interceptor {
    data class Seen(val url: String, val body: com.fasterxml.jackson.databind.JsonNode, val headers: Map<String, String>)

    val requests = java.util.concurrent.CopyOnWriteArrayList<Seen>()

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val buffer = okio.Buffer().also { request.body?.writeTo(it) }
        requests += Seen(
            request.url.toString(),
            benchMapper.readTree(buffer.readUtf8()),
            request.headers.names().associateWith { request.header(it).orEmpty() },
        )
        val content = (1..40).joinToString("\n") { "Dry-run paragraph $it." } +
            "\n===TERMS===\n[]\n===SUMMARY===\nDry run.\nCharacters:"
        val json = benchMapper.writeValueAsString(
            mapOf(
                "choices" to listOf(mapOf("message" to mapOf("content" to content), "finish_reason" to "stop")),
                "usage" to mapOf("prompt_tokens" to 0, "completion_tokens" to 0),
            )
        )
        return okhttp3.Response.Builder()
            .request(request)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }

    /** Differences between what was sent and what [model]'s resolved config asks for (empty = fine). */
    fun problems(model: ModelSpec, defaultBaseUrl: String): List<String> = buildList {
        val endpoint = model.baseUrlOr(defaultBaseUrl).trimEnd('/') + "/chat/completions"
        for (r in requests) {
            if (r.url != endpoint) add("url ${r.url} != $endpoint")
            val sent = r.body.path("model").asText()
            if (sent != model.apiModel) add("model $sent != ${model.apiModel}")
            for ((key, value) in model.extraBody) {
                if (key == "reasoning_effort") continue // owned by the metering interceptor (step-down)
                if (value == null && r.body.has(key)) add("$key sent but the config removes it")
                if (value != null && !r.body.has(key)) add("$key missing")
            }
            if (model.sendTemperature == false && r.body.has("temperature")) add("temperature sent")
            val system = r.body.path("messages").firstOrNull { it.path("role").asText() == "system" }?.path("content")?.asText().orEmpty()
            val profileRules = PromptProfiles.byId(model.profileId).rules
            if (profileRules.isNotEmpty() && !system.contains(profileRules.first())) add("prompt profile ${model.profileId} missing from the system prompt")
            if (profileRules.isEmpty() && system.contains("\n${PromptTemplates.BASE_RULE_COUNT + 1}. ")) add("profile rules sent for the default profile")
            model.headers.forEach { (k, v) -> if (r.headers[k] != v) add("header $k missing") }
        }
    }.distinct()
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
