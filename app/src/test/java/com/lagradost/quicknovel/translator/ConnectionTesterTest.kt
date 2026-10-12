package com.lagradost.quicknovel.translator

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

/** The free key check: what it requests per provider and how answers map to a status. */
class ConnectionTesterTest {
    private data class Reply(val code: Int, val body: String, val contentType: String = "application/json")
    private data class Seen(val method: String, val path: String, val auth: String?, val title: String?)

    private lateinit var server: ServerSocket
    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var reply = Reply(200, """{"object":"list","data":[]}""")

    private val base get() = "http://127.0.0.1:${server.localPort}/v1"

    /** A minimal local HTTP/1.1 server: records each request line and headers, answers [reply]. */
    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return@use
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }
                        .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                        .toMap()
                    val (method, target) = requestLine.split(' ').let { it[0] to it[1] }
                    seen += Seen(method, target.substringBefore('?'), headers["authorization"], headers["x-title"])
                    val r = reply
                    val body = r.body.toByteArray()
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.contentType}\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    @After
    fun stop() {
        server.close()
    }

    private fun test(provider: LlmProvider, key: String = "sk-test-key") = runBlocking {
        ConnectionTester.test(provider, key, baseUrl = base)
    }

    // ---- what is requested ----

    @Test
    fun openAiAndGeminiListModelsWithBearerKey() {
        assertEquals(ConnectionStatus.OK, test(LlmProviders.OPENAI).status)
        assertEquals(ConnectionStatus.OK, test(LlmProviders.GOOGLE, "AIza-test").status)
        assertEquals(listOf("GET" to "/v1/models", "GET" to "/v1/models"), seen.map { it.method to it.path })
        assertEquals("Bearer sk-test-key", seen[0].auth)
        assertEquals("Bearer AIza-test", seen[1].auth)
    }

    @Test
    fun openRouterChecksTheKeyEndpointWithAttributionHeaders() {
        reply = Reply(200, """{"data":{"label":"sk-or-v1-abc...","usage":0}}""")
        assertEquals(ConnectionStatus.OK, test(LlmProviders.OPENROUTER).status)
        assertEquals("/v1/key", seen.single().path)
        assertEquals("GET", seen.single().method)
        assertEquals("QuickNovel", seen.single().title)
    }

    @Test
    fun probeUrlsOfTheRealProviders() {
        assertEquals("https://api.openai.com/v1/models", ConnectionTester.probeUrl(LlmProviders.OPENAI))
        assertEquals("https://openrouter.ai/api/v1/key", ConnectionTester.probeUrl(LlmProviders.OPENROUTER))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/models",
            ConnectionTester.probeUrl(LlmProviders.GOOGLE)
        )
    }

    @Test
    fun keyIsTrimmedAndBlankKeyMakesNoRequest() {
        test(LlmProviders.OPENAI, "  sk-padded \n")
        assertEquals("Bearer sk-padded", seen.single().auth)
        val blank = test(LlmProviders.OPENAI, "   ")
        assertEquals(ConnectionStatus.INVALID_KEY, blank.status)
        assertNull(blank.httpCode)
        assertEquals(1, seen.size)
    }

    // ---- answers from the fake server ----

    @Test
    fun openAi401IsInvalidKey() {
        reply = Reply(401, """{"error":{"message":"Incorrect API key provided: sk-inval***test.","type":"invalid_request_error","code":"invalid_api_key"}}""")
        val r = test(LlmProviders.OPENAI, "sk-invalid-test")
        assertEquals(ConnectionStatus.INVALID_KEY, r.status)
        assertEquals(401, r.httpCode)
        assertTrue(r.detail.startsWith("Incorrect API key"))
    }

    @Test
    fun gemini400WithApiKeyInvalidIsInvalidKey() {
        reply = Reply(400, """[{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}]""")
        val r = test(LlmProviders.GOOGLE)
        assertEquals(ConnectionStatus.INVALID_KEY, r.status)
        assertEquals("API key not valid. Please pass a valid API key.", r.detail)
    }

    @Test
    fun openRouter401IsInvalidKey() {
        reply = Reply(401, """{"error":{"message":"No auth credentials found","code":401}}""")
        assertEquals(ConnectionStatus.INVALID_KEY, test(LlmProviders.OPENROUTER).status)
    }

    @Test
    fun regionRefusalsAreBlocked() {
        reply = Reply(403, """{"error":{"code":"unsupported_country_region_territory","message":"Country, region, or territory not supported"}}""")
        assertEquals(ConnectionStatus.BLOCKED, test(LlmProviders.OPENAI).status)
        reply = Reply(400, """{"error":{"code":400,"message":"User location is not supported for the API use.","status":"FAILED_PRECONDITION"}}""")
        assertEquals(ConnectionStatus.BLOCKED, test(LlmProviders.GOOGLE).status)
    }

    @Test
    fun htmlAnswersAreBlocked() {
        reply = Reply(200, "<html><body>Please log in to the Wi-Fi</body></html>", "text/html")
        val portal = test(LlmProviders.OPENAI)
        assertEquals(ConnectionStatus.BLOCKED, portal.status)
        reply = Reply(403, "<!DOCTYPE html><title>Access denied</title>", "text/html; charset=UTF-8")
        assertEquals(ConnectionStatus.BLOCKED, test(LlmProviders.OPENAI).status)
    }

    @Test
    fun rateLimitedMeansTheKeyWorks() {
        reply = Reply(429, """{"error":{"message":"Rate limit reached"}}""")
        val r = test(LlmProviders.OPENAI)
        assertEquals(ConnectionStatus.OK, r.status)
        assertEquals(429, r.httpCode)
    }

    @Test
    fun serverErrorsAreProviderErrors() {
        reply = Reply(503, """{"error":{"message":"The server is overloaded"}}""")
        assertEquals(ConnectionStatus.PROVIDER_ERROR, test(LlmProviders.OPENAI).status)
        reply = Reply(404, """{"error":{"message":"Not found"}}""")
        assertEquals(ConnectionStatus.PROVIDER_ERROR, test(LlmProviders.OPENAI).status)
    }

    @Test
    fun closedPortIsANetworkError() {
        val port = ServerSocket(0).use { it.localPort }
        val r = runBlocking { ConnectionTester.test(LlmProviders.OPENAI, "sk-x", baseUrl = "http://127.0.0.1:$port/v1") }
        assertEquals(ConnectionStatus.NETWORK_ERROR, r.status)
        assertNull(r.httpCode)
    }

    // ---- pure mapping of failures ----

    @Test
    fun failureMapping() {
        assertEquals(ConnectionStatus.NETWORK_ERROR, ConnectionTester.classifyFailure(UnknownHostException("api.openai.com")).status)
        assertEquals(ConnectionStatus.NETWORK_ERROR, ConnectionTester.classifyFailure(SocketTimeoutException("timeout")).status)
        assertEquals(ConnectionStatus.BLOCKED, ConnectionTester.classifyFailure(SSLHandshakeException("bad cert")).status)
    }

    @Test
    fun detailNeverContainsTheWholeBody() {
        val long = "x".repeat(1000)
        val r = ConnectionTester.classify(500, """{"error":{"message":"$long"}}""", "application/json")
        assertEquals(200, r.detail.length)
        assertFalse(ConnectionTester.classify(200, "{}", null).detail.isNotEmpty())
    }
}
