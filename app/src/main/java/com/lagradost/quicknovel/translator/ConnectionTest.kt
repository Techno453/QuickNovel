package com.lagradost.quicknovel.translator

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Outcome class of a connection test, see [ConnectionTester]. */
enum class ConnectionStatus {
    /** The provider accepted the key. */
    OK,

    /** The provider answered and rejected the key. */
    INVALID_KEY,

    /** Something between the device and the provider answered instead (region block, firewall, captive portal, TLS interception). */
    BLOCKED,

    /** No answer at all (offline, DNS failure, timeout, refused connection). */
    NETWORK_ERROR,

    /** The provider answered with an error that says nothing about the key (5xx, unexpected status). */
    PROVIDER_ERROR,
}

data class ConnectionResult(
    val status: ConnectionStatus,
    /** HTTP status, or null when no response arrived. */
    val httpCode: Int? = null,
    /** Short technical detail for the UI (provider message or exception), never the key. */
    val detail: String = "",
)

/**
 * A free check of an API key: one GET that lists models (OpenAI, Google Gemini) or describes the
 * key (OpenRouter, whose model list is public and would accept any key). No completion is made,
 * so a test never costs anything.
 *
 * Uses the translator's HTTP stack ([TranslationEngine.defaultClient]) with a short timeout.
 */
object ConnectionTester {
    private const val TIMEOUT_SECONDS = 15L

    private val mapper = jacksonObjectMapper()

    /** Words in an error body that mean the request was refused because of where it came from. */
    private val REGION_HINTS = listOf(
        "unsupported_country", "country", "region", "territory", "location is not supported",
        "not available in your", "geo",
    )

    /** Words in an error body that mean the key itself is wrong, whatever the status code. */
    private val BAD_KEY_HINTS = listOf(
        "api_key_invalid", "api key not valid", "invalid api key", "incorrect api key",
        "invalid_api_key", "no auth credentials", "user not found", "invalid authentication",
    )

    /**
     * The URL tested for [provider] with [baseUrl] (defaults to the provider's own):
     * `{base}/key` for OpenRouter, `{base}/models` for everyone else.
     */
    fun probeUrl(provider: LlmProvider, baseUrl: String = provider.baseUrl): String {
        val base = LlmConfig.normalizeBaseUrl(baseUrl.ifBlank { provider.baseUrl })
        return if (provider.id == LlmProviders.OPENROUTER.id) "$base/key" else "$base/models"
    }

    /** Maps an HTTP answer to a [ConnectionResult]. Pure, for unit tests. */
    fun classify(code: Int, body: String, contentType: String?): ConnectionResult {
        val trimmed = body.trimStart()
        val isJson = contentType?.contains("json", ignoreCase = true) == true ||
            trimmed.startsWith("{") || trimmed.startsWith("[")
        val message = errorMessage(body)
        val lower = body.lowercase(Locale.ROOT)
        fun detail(fallback: String) = (message ?: fallback).take(200)

        return when {
            code in 200..299 ->
                // A login page or a firewall notice served with 200 instead of the API's JSON.
                if (isJson) ConnectionResult(ConnectionStatus.OK, code)
                else ConnectionResult(ConnectionStatus.BLOCKED, code, "Got a web page instead of the API (captive portal or filter?)")

            code == 451 -> ConnectionResult(ConnectionStatus.BLOCKED, code, detail("Unavailable for legal reasons"))

            !isJson && code in 300..499 ->
                // The API always answers in JSON; an HTML 403 is a CDN, proxy or firewall.
                ConnectionResult(ConnectionStatus.BLOCKED, code, "HTTP $code from something other than the API")

            REGION_HINTS.any { it in lower } && code in 400..499 && BAD_KEY_HINTS.none { it in lower } ->
                ConnectionResult(ConnectionStatus.BLOCKED, code, detail("Not available in this region"))

            code == 401 || code == 403 || BAD_KEY_HINTS.any { it in lower } ->
                ConnectionResult(ConnectionStatus.INVALID_KEY, code, detail("HTTP $code"))

            // Rate limited or out of quota: the key itself was accepted.
            code == 429 -> ConnectionResult(ConnectionStatus.OK, code, detail("Rate limited"))

            else -> ConnectionResult(ConnectionStatus.PROVIDER_ERROR, code, detail("HTTP $code"))
        }
    }

    /** Maps a failed call (no HTTP answer) to a [ConnectionResult]. Pure, for unit tests. */
    fun classifyFailure(e: Throwable): ConnectionResult = when (e) {
        // TLS failures mean something intercepted the connection (or the device clock is wrong).
        is SSLException -> ConnectionResult(ConnectionStatus.BLOCKED, null, "Secure connection failed: ${e.message ?: e.javaClass.simpleName}")
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is InterruptedIOException ->
            ConnectionResult(ConnectionStatus.NETWORK_ERROR, null, e.message ?: e.javaClass.simpleName)
        else -> ConnectionResult(ConnectionStatus.NETWORK_ERROR, null, e.message ?: e.javaClass.simpleName)
    }

    private fun errorMessage(body: String): String? = try {
        var node = mapper.readTree(body)
        if (node.isArray) node = node.path(0) // Gemini's compatibility layer wraps errors in a list
        val err = node.path("error")
        (if (err.isTextual) err.asText() else err.path("message").asText(""))
            .takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * Tests [apiKey] against [provider] ([baseUrl] overrides the provider's default, e.g. for a
     * local test server). Never throws except for cancellation.
     */
    suspend fun test(
        provider: LlmProvider,
        apiKey: String,
        baseUrl: String = provider.baseUrl,
        client: OkHttpClient = TranslationEngine.defaultClient,
    ): ConnectionResult = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext ConnectionResult(ConnectionStatus.INVALID_KEY, null, "No key entered")
        val quick = client.newBuilder()
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(probeUrl(provider, baseUrl))
            .header("Authorization", "Bearer $key")
            .apply { provider.headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        try {
            quick.newCall(request).execute().use { response ->
                classify(response.code, response.body?.string().orEmpty(), response.header("Content-Type"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            classifyFailure(e)
        } catch (e: Exception) {
            ConnectionResult(ConnectionStatus.NETWORK_ERROR, null, e.message ?: e.javaClass.simpleName)
        }
    }
}
