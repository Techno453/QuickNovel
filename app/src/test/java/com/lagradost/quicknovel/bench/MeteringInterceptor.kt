package com.lagradost.quicknovel.bench

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One HTTP call to the chat completions endpoint, as seen by the bench. */
data class CallRecord(
    val status: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long,
    val usd: Double,
    val finishReason: String?,
    val latencyMs: Long,
    val effort: String?,
    val error: String? = null,
)

/** Mutable per-(model, purpose) request parameters discovered at run time (e.g. accepted effort). */
class ParamState(model: ModelSpec) {
    @Volatile var effort: String? = model.reasoningEffort
    @Volatile var tokensParam: String = model.tokensParam()
    @Volatile var sendTemperature = !model.extraBody.containsKey("temperature") || model.extraBody["temperature"] != null
    val adjustments = java.util.Collections.synchronizedList(mutableListOf<String>())
}

/**
 * OkHttp interceptor that sits between the app's [com.lagradost.quicknovel.translator.TranslationEngine]
 * (or the bench's own calls) and OpenAI:
 *  - rewrites the request for current-generation models (reasoning_effort, max_completion_tokens,
 *    no temperature when reasoning is on),
 *  - steps down the reasoning effort / drops parameters when the API rejects them (HTTP 400 costs nothing),
 *  - reserves the estimated cost in the [CostLedger] before the call and books the actual cost
 *    from `usage` after it; when the budget would be exceeded it returns a synthetic HTTP 400
 *    so the engine stops without retrying.
 */
class MeteringInterceptor(
    private val ledger: CostLedger,
    private val model: ModelSpec,
    private val state: ParamState,
    private val purpose: String,
    private val sample: String,
) : Interceptor {
    private val calls = java.util.Collections.synchronizedList(mutableListOf<CallRecord>())

    @Volatile var budgetHit: String? = null
        private set

    @Volatile var unavailable: String? = null
        private set

    fun calls(): List<CallRecord> = synchronized(calls) { calls.toList() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method != "POST" || !original.url.encodedPath.endsWith("/chat/completions")) {
            return chain.proceed(original)
        }
        val buffer = Buffer().also { original.body?.writeTo(it) }
        val baseBody = benchMapper.readTree(buffer.readUtf8()) as ObjectNode

        repeat(MAX_ADJUSTMENTS + 1) {
            val body = baseBody.deepCopy()
            applyParams(body)
            val estimate = estimateUsd(body)
            val reservation = try {
                ledger.reserve(estimate, "${model.id}/$purpose/$sample")
            } catch (e: BudgetExceededException) {
                budgetHit = e.message
                return synthetic(original, 400, "bench budget cap reached: ${e.message}")
            }
            val request = original.newBuilder()
                .apply { model.headers.forEach { (k, v) -> header(k, v) } }
                .post(benchMapper.writeValueAsString(body).toRequestBody(JSON))
                .build()
            val t0 = System.nanoTime()
            val response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                ledger.release(reservation)
                calls.add(CallRecord(-1, 0, 0, 0, 0.0, null, ms(t0), state.effort, Secrets.scrub(e.message)))
                throw e
            }
            val code = response.code
            val text = response.use { it.body?.string().orEmpty() }
            val latency = ms(t0)
            if (code in 200..299) {
                val root = runCatching { benchMapper.readTree(text) }.getOrNull()
                val usage = root?.path("usage")
                val prompt = usage?.path("prompt_tokens")?.asLong(0) ?: 0
                val completion = usage?.path("completion_tokens")?.asLong(0) ?: 0
                val reasoning = usage?.path("completion_tokens_details")?.path("reasoning_tokens")?.asLong(0) ?: 0
                // OpenRouter reports the billed amount in usage.cost; otherwise use the price table.
                val usd = usage?.path("cost")?.takeIf { it.isNumber }?.asDouble() ?: model.costUsd(prompt, completion)
                val finish = root?.path("choices")?.path(0)?.path("finish_reason")?.takeIf { !it.isMissingNode && !it.isNull }?.asText()
                ledger.commit(reservation, ledger.entry(purpose, model.id, sample, prompt, completion, reasoning, usd, code))
                calls.add(CallRecord(code, prompt, completion, reasoning, usd, finish, latency, state.effort))
                return rebuild(response, code, text)
            }

            val message = Secrets.scrub(errorMessage(text) ?: "HTTP $code")
            ledger.commit(reservation, ledger.entry(purpose, model.id, sample, 0, 0, 0, 0.0, code))
            calls.add(CallRecord(code, 0, 0, 0, 0.0, null, latency, state.effort, message))
            if (code == 400 && adjustFor(message)) return@repeat
            if (code == 404 || message.contains("model_not_found") || message.contains("does not exist")) {
                unavailable = message
            }
            return rebuild(response, code, text)
        }
        return synthetic(original, 400, "bench: too many parameter adjustments for ${model.id}")
    }

    /** Reacts to a 400 caused by a request parameter; true if the request should be retried. */
    private fun adjustFor(message: String): Boolean {
        val m = message.lowercase()
        if (m.contains("reasoning")) {
            val next = nextEffort(state.effort)
            if (next != state.effort) {
                state.adjustments.add("reasoning_effort ${state.effort} -> ${next ?: "(omitted)"}: $message")
                state.effort = next
                return true
            }
        }
        if (m.contains(state.tokensParam)) {
            val other = if (state.tokensParam == "max_tokens") "max_completion_tokens" else "max_tokens"
            if (state.adjustments.none { it.startsWith("$other ->") }) {
                state.adjustments.add("${state.tokensParam} -> $other: $message")
                state.tokensParam = other
                return true
            }
        }
        if ((m.contains("temperature") || m.contains("top_p")) && state.sendTemperature) {
            state.adjustments.add("temperature dropped: $message")
            state.sendTemperature = false
            return true
        }
        return false
    }

    private fun applyParams(body: ObjectNode) {
        // Model extraBody: the engine already merges it; this covers the bench's own calls.
        for ((k, v) in model.extraBody) {
            if (v == null) body.remove(k) else if (!body.has(k)) body.set<JsonNode>(k, benchMapper.valueToTree(v))
        }
        val effort = state.effort
        if (effort != null) body.put("reasoning_effort", effort) else body.remove("reasoning_effort")
        if ((effort != null && effort != "none") || !state.sendTemperature) {
            body.remove("temperature")
            body.remove("top_p")
        }
        val explicit = body.path("max_completion_tokens").takeIf { it.isNumber }?.asInt()
            ?: body.path("max_tokens").takeIf { it.isNumber }?.asInt()
        body.remove("max_tokens")
        body.remove("max_completion_tokens")
        val limit = explicit ?: max(
            model.minCompletionTokens,
            ceil(TokenEstimate.tokens(promptText(body)) * model.completionFactor).toInt()
        )
        body.put(state.tokensParam, limit)
    }

    private fun promptText(body: JsonNode): String =
        body.path("messages").joinToString("\n") { it.path("content").asText("") }

    private fun estimateUsd(body: JsonNode): Double {
        val text = promptText(body)
        val promptTokens = TokenEstimate.tokens(text)
        val limit = (body.path("max_completion_tokens").takeIf { it.isNumber } ?: body.path("max_tokens")).asLong(4096)
        val userText = body.path("messages").lastOrNull()?.path("content")?.asText("").orEmpty()
        val orEffort = body.path("reasoning").let { r ->
            if (r.path("enabled").asBoolean(true) == false) "none" else r.path("effort").asText(null)
        }
        val expectedOut = (TokenEstimate.cjkChars(userText) * 1.2).toLong() + 200 +
                TokenEstimate.reasoningAllowance(state.effort ?: orEffort)
        return model.costUsd(promptTokens, min(limit, expectedOut))
    }

    private fun rebuild(response: Response, code: Int, text: String): Response =
        response.newBuilder().code(code).body(text.toResponseBody(JSON)).build()

    private fun synthetic(request: Request, code: Int, message: String): Response {
        val json = benchMapper.writeValueAsString(mapOf("error" to mapOf("message" to message, "type" to "bench")))
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
            .message("Bench").body(json.toResponseBody(JSON)).build()
    }

    private fun errorMessage(body: String): String? = runCatching {
        val err = benchMapper.readTree(body).path("error")
        (if (err.isTextual) err.asText() else err.path("message").asText("")).takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_ADJUSTMENTS = 4
        private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

        /** none -> minimal -> low; minimal -> low; low -> (omit, model default). */
        fun nextEffort(effort: String?): String? = when (effort) {
            "none" -> "minimal"
            "minimal" -> "low"
            else -> null
        }
    }
}
