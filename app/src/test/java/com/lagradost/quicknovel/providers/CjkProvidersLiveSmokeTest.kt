package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.USER_AGENT
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Live smoke test against the real CJK sites: one search, one novel page and one chapter per
 * provider. Skipped unless run with `-Dlive=1` (or env `LIVE=1`); the nightly GitHub workflow
 * runs it so site changes show up as a failed job. Each provider is its own parameterized test
 * case, so one broken site does not hide the others.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*CjkProvidersLiveSmokeTest*' -Dlive=1
 *   ./gradlew :app:testDebugUnitTest --tests '*CjkProvidersLiveSmokeTest*' -Dlive=1 -Dlive.provider=Syosetu
 */
@RunWith(Parameterized::class)
class CjkProvidersLiveSmokeTest(private val case: Case) {

    /**
     * [query] is searched and the first results are tried until one has chapters. When the
     * search cannot run on the JVM, [knownNovelUrl] is loaded instead. [geoBlocked] providers
     * are skipped (not failed) when the site refuses this machine's IP.
     */
    class Case(
        val api: MainAPI,
        val query: String?,
        val knownNovelUrl: String? = null,
        val geoBlocked: Boolean = false,
    ) {
        override fun toString(): String = api.name
    }

    companion object {
        private val enabled: Boolean
            get() = (System.getProperty("live") ?: System.getenv("LIVE"))
                ?.let { it.isNotBlank() && it != "0" && !it.equals("false", true) } == true

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> {
            val all = listOf(
                // 69shuba: search needs Android's CookieManager / a WebView for the Cloudflare
                // Turnstile, so only a known novel page and a chapter are checked. Cloudflare
                // blocks some networks (curl gets 403 where OkHttp gets through): skipped then.
                Case(BiqugeProvider(), null, "https://www.69shuba.com/book/90435.htm", geoBlocked = true),
                Case(QuanbenProvider(), "斗罗"),
                Case(SyosetuProvider(), "転生"),
                Case(KakuyomuProvider(), "転生"),
                Case(NovelpiaProvider(), "회귀"),
            )
            val only = System.getProperty("live.provider") ?: System.getenv("LIVE_PROVIDER")
            return if (only.isNullOrBlank()) all
            else all.filter { it.api.name.contains(only, ignoreCase = true) }
        }

        @BeforeClass
        @JvmStatic
        fun initHttp() = initHttp(force = false)

        /** Points [MainActivity.app] at a plain JVM OkHttp client, as the app does on start. */
        fun initHttp(force: Boolean) {
            if (!enabled && !force) return
            val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            MainActivity.app = Requests(
                OkHttpClient().newBuilder()
                    .readTimeout(60L, TimeUnit.SECONDS)
                    .connectTimeout(30L, TimeUnit.SECONDS)
                    .build(),
                responseParser = object : ResponseParser {
                    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                        mapper.readValue(text, kClass.java)

                    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                        runCatching { mapper.readValue(text, kClass.java) }.getOrNull()

                    override fun writeValueAsString(obj: Any): String = mapper.writeValueAsString(obj)
                }
            ).apply { defaultHeaders = mapOf("user-agent" to USER_AGENT) }
        }
    }

    private fun log(msg: String) = println("[live ${case.api.name}] $msg")

    /** Retries once after a pause: a single timeout should not page the owner. */
    private suspend fun <T> step(name: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            log("$name failed once (${e.javaClass.simpleName}: ${e.message}); retrying")
            delay(5_000)
            block()
        }
    }

    /** 69shuba serves its home page everywhere but answers 403 for book pages when geo-blocking. */
    private suspend fun isBlocked(): Boolean {
        val probe = case.knownNovelUrl ?: case.api.mainUrl
        val response = runCatching { MainActivity.app.get(probe) }.getOrNull() ?: return true
        val body = response.text.lowercase()
        log("block probe $probe -> HTTP ${response.code}, ${body.length} chars")
        return response.code == 403 || response.code == 503 ||
                body.contains("just a moment") || body.contains("cf-browser-verification")
    }

    @Test
    fun searchLoadAndReadOneChapter() = runBlocking {
        assumeTrue("live smoke test: run with -Dlive=1 (or LIVE=1)", enabled)
        val api = case.api

        if (case.geoBlocked && isBlocked()) {
            log("SKIPPED: site blocks this network (403 / Cloudflare)")
            assumeTrue("${api.name} blocks this network", false)
        }

        val candidates: List<String> = if (case.query != null) {
            val results = step("search") { api.search(case.query) }.orEmpty()
            log("search '${case.query}' -> ${results.size} results")
            assertTrue("${api.name}: search returned nothing", results.isNotEmpty())
            assertTrue("${api.name}: search result without name/url",
                results.all { it.name.isNotBlank() && it.url.startsWith("http") })
            results.take(3).map { it.url }
        } else {
            listOf(case.knownNovelUrl!!)
        }

        // Some results have no readable chapters (e.g. Novelpia PLUS works with no free
        // episodes); use the first that does.
        var novel: StreamResponse? = null
        for (url in candidates) {
            val load = step("load $url") { api.load(url) } as? StreamResponse
            log("load $url -> ${load?.name} (${load?.data?.size ?: 0} chapters)")
            if (load != null && load.data.isNotEmpty()) {
                novel = load
                break
            }
        }
        assertTrue("${api.name}: none of $candidates loaded with chapters", novel != null)
        novel!!
        assertTrue("${api.name}: novel without a name", novel.name.isNotBlank())

        val chapter = novel.data.first()
        val html = step("loadHtml ${chapter.url}") { api.loadHtml(chapter.url) }
        val text = html?.let { Jsoup.parseBodyFragment(it).text() }.orEmpty()
        log("chapter '${chapter.name}' -> ${text.length} chars")
        assertTrue("${api.name}: empty chapter ${chapter.url}", text.length > 200)
        assertTrue("${api.name}: script in chapter", !html!!.contains("<script"))
    }
}
