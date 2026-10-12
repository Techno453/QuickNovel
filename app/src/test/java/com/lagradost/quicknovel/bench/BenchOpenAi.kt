package com.lagradost.quicknovel.bench

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Direct (non-engine) OpenAI calls the harness makes for itself: the smoke test, the model
 * list, bible seeding and the LLM judge. All chat calls go through [MeteringInterceptor].
 */
class BenchOpenAi(
    private val keys: (String) -> String?,
    private val baseUrl: String,
    private val ledger: CostLedger,
) {
    data class ChatReply(val content: String, val finishReason: String?, val call: CallRecord?, val error: String?)

    private val base = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .build()

    private val states = java.util.concurrent.ConcurrentHashMap<String, ParamState>()
    fun stateFor(model: ModelSpec): ParamState = states.getOrPut(model.id) { ParamState(model) }

    /** GET /models (free). Returns null if the listing is not available. */
    fun listModels(listBaseUrl: String = baseUrl, keyName: String = ModelSpec.OPENAI_KEY): Set<String>? = runCatching {
        val req = Request.Builder().url(listBaseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer ${keys(keyName).orEmpty()}").get().build()
        base.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            benchMapper.readTree(r.body?.string().orEmpty()).path("data").mapNotNull { it.path("id").asText(null) }.toSet()
        }
    }.getOrNull()

    fun chat(
        model: ModelSpec,
        purpose: String,
        sample: String,
        system: String?,
        user: String,
        maxCompletionTokens: Int,
        jsonObject: Boolean = false,
        temperature: Double? = null,
    ): ChatReply {
        val meter = MeteringInterceptor(ledger, model, stateFor(model), purpose, sample)
        val client = base.newBuilder().addInterceptor(meter).build()
        val body = buildMap<String, Any> {
            put("model", model.apiModel)
            put("messages", buildList {
                if (system != null) add(mapOf("role" to "system", "content" to system))
                add(mapOf("role" to "user", "content" to user))
            })
            put("max_completion_tokens", maxCompletionTokens)
            if (temperature != null) put("temperature", temperature)
            if (jsonObject) put("response_format", mapOf("type" to "json_object"))
        }
        val req = Request.Builder().url(model.baseUrlOr(baseUrl).trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${keys(model.keyName) ?: return ChatReply("", null, null, "no key (${model.keyName})")}")
            .post(benchMapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val root = runCatching { benchMapper.readTree(text) }.getOrNull()
                if (!r.isSuccessful) {
                    val err = root?.path("error")?.let { if (it.isTextual) it.asText() else it.path("message").asText("") }
                    return ChatReply("", null, meter.calls().lastOrNull(), Secrets.scrub("HTTP ${r.code}: ${err ?: text.take(200)}"))
                }
                val choice = root?.path("choices")?.path(0)
                ChatReply(
                    choice?.path("message")?.path("content")?.asText("").orEmpty(),
                    choice?.path("finish_reason")?.asText(null),
                    meter.calls().lastOrNull(),
                    null
                )
            }
        } catch (e: Exception) {
            ChatReply("", null, meter.calls().lastOrNull(), Secrets.scrub("${e.javaClass.simpleName}: ${e.message}"))
        }
    }
}
