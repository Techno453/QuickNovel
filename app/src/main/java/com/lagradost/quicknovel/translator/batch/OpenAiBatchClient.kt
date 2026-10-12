package com.lagradost.quicknovel.translator.batch

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.translator.LlmConfig
import com.lagradost.quicknovel.translator.TokenUsage
import com.lagradost.quicknovel.translator.TranslationApiException
import com.lagradost.quicknovel.translator.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/**
 * Minimal client for the OpenAI Batch API (`/v1/files`, `/v1/batches`). Batch requests cost
 * [com.lagradost.quicknovel.translator.SpendPricing.BATCH_DISCOUNT] of the list price and finish
 * within 24 hours. Only api.openai.com has this API ([supports]); OpenRouter and the other
 * OpenAI-compatible providers do not.
 */
class OpenAiBatchClient(
    private val apiKey: String,
    baseUrl: String = LlmConfig.DEFAULT_BASE_URL,
    private val client: OkHttpClient = TranslationEngine.defaultClient,
) {
    private val base = LlmConfig.normalizeBaseUrl(baseUrl)
    private val mapper = jacksonObjectMapper()

    data class BatchInfo(
        val id: String,
        /** OpenAI status: validating, failed, in_progress, finalizing, completed, expired, cancelling, cancelled. */
        val status: String,
        val inputFileId: String?,
        val outputFileId: String?,
        val errorFileId: String?,
        val total: Int,
        val completed: Int,
        val failed: Int,
        /** Validation errors (status failed), joined. */
        val errors: String?,
        /** Tokens billed so far for the whole batch (`usage`, batches created after 2025-09-07); null if absent. */
        val usage: TokenUsage? = null,
    )

    companion object {
        private val JSON = "application/json".toMediaType()
        private val JSONL = "application/jsonl".toMediaType()
        const val COMPLETION_WINDOW = "24h"

        /** True only for OpenAI's own endpoint (the Batch API is not part of the compatible API elsewhere). */
        fun supports(baseUrl: String?): Boolean = try {
            URI(LlmConfig.normalizeBaseUrl(baseUrl)).host.equals("api.openai.com", ignoreCase = true)
        } catch (e: Exception) {
            false
        }

        private fun text(node: JsonNode, field: String): String? =
            node.path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }

        fun parseBatch(node: JsonNode): BatchInfo {
            val counts = node.path("request_counts")
            val errors = node.path("errors").path("data").takeIf { it.isArray }
                ?.joinToString("; ") { e ->
                    val line = e.path("line").takeIf { it.isNumber }?.let { "line ${it.asInt()}" }
                    listOfNotNull(line, text(e, "code"), text(e, "message")).joinToString(": ")
                }?.takeIf { it.isNotBlank() }
            return BatchInfo(
                id = node.path("id").asText(""),
                status = node.path("status").asText(""),
                inputFileId = text(node, "input_file_id"),
                outputFileId = text(node, "output_file_id"),
                errorFileId = text(node, "error_file_id"),
                total = counts.path("total").asInt(0),
                completed = counts.path("completed").asInt(0),
                failed = counts.path("failed").asInt(0),
                errors = errors,
                usage = node.path("usage").takeIf { it.isObject }?.let { u ->
                    TokenUsage(u.path("input_tokens").asInt(0), u.path("output_tokens").asInt(0))
                },
            )
        }
    }

    /** Uploads the JSONL input (purpose "batch"); returns the file id. */
    suspend fun uploadJsonl(jsonl: String, fileName: String = "quicknovel-batch.jsonl"): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "batch")
            .addFormDataPart("file", fileName, jsonl.toRequestBody(JSONL))
            .build()
        val node = execute(request("$base/files").post(body).build())
        return node.path("id").asText("").ifEmpty { throw TranslationApiException("File upload returned no id") }
    }

    /** Creates a batch over an uploaded file for `/v1/chat/completions` with a 24 h window. */
    suspend fun createBatch(inputFileId: String, metadata: Map<String, String> = emptyMap()): BatchInfo {
        val payload = buildMap<String, Any> {
            put("input_file_id", inputFileId)
            put("endpoint", BatchRequestBuilder.ENDPOINT)
            put("completion_window", COMPLETION_WINDOW)
            if (metadata.isNotEmpty()) put("metadata", metadata)
        }
        return parseBatch(execute(request("$base/batches").post(mapper.writeValueAsString(payload).toRequestBody(JSON)).build()))
    }

    suspend fun getBatch(batchId: String): BatchInfo = parseBatch(execute(request("$base/batches/$batchId").get().build()))

    suspend fun cancelBatch(batchId: String): BatchInfo =
        parseBatch(execute(request("$base/batches/$batchId/cancel").post(ByteArray(0).toRequestBody(JSON)).build()))

    /** Downloads a file's content (the output or error JSONL). */
    suspend fun downloadFile(fileId: String): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request("$base/files/$fileId/content").get().build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw error(response.code, body)
                body
            }
        } catch (e: IOException) {
            throw TranslationApiException("Network error: ${e.message}", retryable = true)
        }
    }

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $apiKey")

    private suspend fun execute(request: Request): JsonNode = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw error(response.code, body)
                try {
                    mapper.readTree(body)
                } catch (e: Exception) {
                    throw TranslationApiException("Unreadable Batch API response: ${e.message}")
                }
            }
        } catch (e: IOException) {
            throw TranslationApiException("Network error: ${e.message}", retryable = true)
        }
    }

    private fun error(code: Int, body: String): TranslationApiException {
        val message = try {
            mapper.readTree(body).path("error").path("message").asText("")
        } catch (e: Exception) {
            ""
        }.ifBlank { "HTTP $code" }
        return when {
            code == 401 || code == 403 -> TranslationApiException("Invalid API key or access denied (HTTP $code): $message")
            code == 429 || code >= 500 -> TranslationApiException("Batch API error (HTTP $code): $message", retryable = true)
            else -> TranslationApiException("Batch API error (HTTP $code): $message")
        }
    }
}
