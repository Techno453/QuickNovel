package com.lagradost.quicknovel.providers.cjk

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.providers.japanese.AlphapolisProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KClass

/**
 * Alphapolis answers HTTP 404 (a full "no results" page) for a search with no matches, which must
 * read as an empty result, not a failed source. Served by an OkHttp interceptor, no network.
 */
class AlphapolisSearchNoResultsTest {
    private lateinit var previous: Requests

    @Before
    fun saveApp() {
        previous = MainActivity.app
    }

    @After
    fun restoreApp() {
        MainActivity.app = previous
    }

    private fun serve(code: Int, body: String) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("served by test")
                .body(body.toResponseBody("text/html; charset=utf-8".toMediaType()))
                .build()
        }.build()
        val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        MainActivity.app = Requests(
            client,
            responseParser = object : ResponseParser {
                override fun <T : Any> parse(text: String, kClass: KClass<T>): T = mapper.readValue(text, kClass.java)
                override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                    runCatching { mapper.readValue(text, kClass.java) }.getOrNull()
                override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
            }
        )
    }

    private val noResultsPage = "<!doctype html><html><head><title>検索結果</title></head><body>" +
            "<div class=\"search-result\">" + "x".repeat(20_000) + "</div>" +
            "<p>該当する作品はありませんでした。</p></body></html>"

    @Test
    fun aSearchWithNoMatchesIsEmptyNotAnError() = runBlocking {
        serve(404, noResultsPage)
        assertTrue(AlphapolisProvider().search("zzzzqqqqxxxx").isEmpty())
    }

    @Test
    fun otherHttpErrorsStillFailTheSource() = runBlocking {
        serve(500, noResultsPage)
        try {
            AlphapolisProvider().search("異修羅")
            fail("expected ErrorLoadingException")
        } catch (e: ErrorLoadingException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("HTTP 500"))
        }
        assertEquals(Unit, Unit)
    }
}
