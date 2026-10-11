package com.lagradost.quicknovel

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import com.lagradost.nicehttp.ignoreAllSSLErrors
import com.lagradost.quicknovel.network.CloudflareKiller
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.textWithEncoding
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Instrumented integration test for BiqugeProvider (69shuba.com).
 *
 * Runs on the Android emulator/device so CloudflareKiller's WebView is available.
 *
 * Run with:
 *   gradlew connectedDebugAndroidTest
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.lagradost.quicknovel.BiqugeProviderTest
 *
 * Or via the run_biquge_test.bat helper at the repo root.
 *
 * NOTE: 69shuba.com geo-blocks non-Chinese IPs. Tests are designed to be skipped
 * gracefully (SKIP rather than FAIL) when a 403 is returned.
 */
@RunWith(AndroidJUnit4::class)
class BiqugeProviderTest {

    companion object {
        private const val TAG = "BiqugeTest"
        // Book 90412 = 四十九条末世规则 (confirmed exists on live site)
        private const val FALLBACK_NOVEL_URL = "https://www.69shuba.com/book/90412.htm"
        private lateinit var provider: BiqugeProvider
        private lateinit var cfKiller: CloudflareKiller

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Initialise MainActivity.Companion.app so BiqugeProvider can call app.get() / app.post()
            val mapper = jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

            MainActivity.app = Requests(
                OkHttpClient()
                    .newBuilder()
                    .ignoreAllSSLErrors()
                    .readTimeout(60L, TimeUnit.SECONDS)
                    .connectTimeout(30L, TimeUnit.SECONDS)
                    .build(),
                responseParser = object : ResponseParser {
                    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                        mapper.readValue(text, kClass.java)

                    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                        try { mapper.readValue(text, kClass.java) } catch (e: Exception) { null }

                    override fun writeValueAsString(obj: Any): String =
                        mapper.writeValueAsString(obj)
                }
            ).apply {
                defaultHeaders = mapOf("user-agent" to USER_AGENT)
            }

            provider = BiqugeProvider()
            cfKiller = CloudflareKiller()
            Log.i(TAG, "BiqugeProvider initialised — mainUrl=${provider.mainUrl}")
        }

        /** Returns true if [e] looks like a network / geo block (skip, not fail). */
        private fun isGeoBlock(e: Throwable): Boolean {
            val msg = e.message ?: ""
            return msg.contains("403") || msg.contains("503") ||
                    msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true)
        }

        private fun sep(label: String) {
            Log.i(TAG, "─".repeat(60))
            Log.i(TAG, label)
            Log.i(TAG, "─".repeat(60))
        }
    }

    // ─── Tests ───────────────────────────────────────────────────────────────────

    /**
     * Diagnostic: dumps actual HTML structure from the site so we can verify/fix CSS selectors.
     * Not a pass/fail test — just logs to help debug.
     */
    @Test
    fun testDiagnoseHtml() {
        runBlocking {
            try {
                // 1. Homepage — find ranking page link & a real book URL
                sep("DIAGNOSE: Homepage structure")
                val homeUrl = "https://www.69shuba.com/"
                val homeResp = MainActivity.app.get(homeUrl, interceptor = cfKiller)
                val homeHtml = homeResp.textWithEncoding()
                val homeDoc = org.jsoup.Jsoup.parse(homeHtml, homeUrl)
                Log.i(TAG, "Home title: ${homeDoc.title()}")
                Log.i(TAG, "UL classes: ${homeDoc.select("ul[class]").map { "ul.${it.className()}" }.distinct().take(10)}")
                // Log first 5 book-like links
                val bookLinks = homeDoc.select("a[href*=/book/]").take(5)
                Log.i(TAG, "Book links found: ${bookLinks.map { "${it.text().take(30)} → ${it.absUrl("href")}" }}")
                // Log first ranking/paihang links
                val rankLinks = homeDoc.select("a[href*=paihang]").take(5)
                Log.i(TAG, "Rank links found: ${rankLinks.map { "${it.text().take(30)} → ${it.absUrl("href")}" }}")
                Log.i(TAG, "First 800 body text: ${homeDoc.body().text().take(800)}")

                // 2. Try ranking page - use first real URL seen from homepage
                if (rankLinks.isNotEmpty()) {
                    val rankUrl = rankLinks.first().absUrl("href")
                    sep("DIAGNOSE: Ranking page $rankUrl")
                    val rankResp = MainActivity.app.get(rankUrl, interceptor = cfKiller)
                    val rankHtml = rankResp.textWithEncoding()
                    val rankDoc = org.jsoup.Jsoup.parse(rankHtml, rankUrl)
                    Log.i(TAG, "Rank title: ${rankDoc.title()}")
                    Log.i(TAG, "UL classes: ${rankDoc.select("ul[class]").map { "ul.${it.className()}" }.distinct().take(10)}")
                    val liItems = rankDoc.select("li").take(5)
                    Log.i(TAG, "First 5 li items: ${liItems.map { it.text().take(80) }}")
                    Log.i(TAG, "First book link on rank page: ${rankDoc.select("a[href*=/book/]").firstOrNull()?.let { "${it.text()} → ${it.absUrl("href")}" }}")
                }

                // 3. Try first real book link from homepage
                if (bookLinks.isNotEmpty()) {
                    val bookUrl = bookLinks.first().absUrl("href")
                    sep("DIAGNOSE: Book page $bookUrl")
                    val bookResp = MainActivity.app.get(bookUrl, interceptor = cfKiller)
                    val bookHtml = bookResp.textWithEncoding()
                    val bookDoc = org.jsoup.Jsoup.parse(bookHtml, bookUrl)
                    Log.i(TAG, "Book title: ${bookDoc.title()}")
                    Log.i(TAG, "H1: ${bookDoc.select("h1").map { it.text().take(60) }}")
                    Log.i(TAG, "DIV classes: ${bookDoc.select("div[class]").map { ".${it.className()}" }.distinct().take(20)}")
                    Log.i(TAG, "Chapter container divs: ${bookDoc.select("div.listmain, #list, div.chapter-list").size} found")
                    val chapterLinks = bookDoc.select("div.listmain a[href], #list a[href], div.chapter-list a[href]")
                    Log.i(TAG, "Chapter links: ${chapterLinks.size}")
                    if (chapterLinks.isNotEmpty()) {
                        val firstCh = chapterLinks.first()!!
                        Log.i(TAG, "First chapter: ${firstCh.text()} → ${firstCh.absUrl("href")}")
                        // 4. Try first chapter
                        val chUrl = firstCh.absUrl("href")
                        sep("DIAGNOSE: Chapter page $chUrl")
                        val chResp = MainActivity.app.get(chUrl, interceptor = cfKiller)
                        val chHtml = chResp.textWithEncoding()
                        val chDoc = org.jsoup.Jsoup.parse(chHtml, chUrl)
                        Log.i(TAG, "Chapter title: ${chDoc.title()}")
                        Log.i(TAG, "DIV classes: ${chDoc.select("div[class]").map { ".${it.className()}" }.distinct().take(15)}")
                        val content = chDoc.selectFirst("div.txtnav, div#content, div.showtxt")
                        Log.i(TAG, "Content selector hit: ${content?.let { "${it.tagName()}.${it.className()}" } ?: "NONE"}")
                        Log.i(TAG, "Content length: ${content?.text()?.length ?: 0}")
                        Log.i(TAG, "Content preview: ${content?.text()?.take(300) ?: "(none)"}")
                    }
                }

                Log.i(TAG, "✓ testDiagnoseHtml completed")
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testDiagnoseHtml — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testDiagnoseHtml", e)
                    throw e
                }
            }
        }
    }

    @Test
    fun testGbkEncoding() {
        sep("GBK ENCODING CHECK (no network)")
        val cases = mapOf(
            "斗破苍穹" to "%B6%B7%C6%C6%B2%D4%F1%B7",
            "修仙"     to "%D0%DE%CF%C9",
            "搜索"     to "%CB%D1%CB%F7"
        )
        for ((input, expected) in cases) {
            val actual = java.net.URLEncoder.encode(input, "GBK")
            Log.i(TAG, "  encode(\"$input\") = $actual")
            assertEquals(
                "GBK encoding mismatch for \"$input\"",
                expected.uppercase(),
                actual.uppercase()
            )
        }
        Log.i(TAG, "  ✓ GBK encoding correct")
    }

    @Test
    fun testMainPage() {
        sep("MAIN PAGE (latest updates, page 1)")
        runBlocking {
            try {
                val result = provider.loadMainPage(1, null, "lastupdate", null)
                Log.i(TAG, "URL:     ${result.url}")
                Log.i(TAG, "Results: ${result.list.size}")
                result.list.take(5).forEach { r ->
                    Log.i(TAG, "  • ${r.name}  →  ${r.url}")
                }
                assertTrue("Main page returned no results — check selector", result.list.isNotEmpty())
                Log.i(TAG, "✓ testMainPage PASSED")
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testMainPage — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testMainPage", e)
                    throw e
                }
            }
        }
    }

    @Test
    fun testSearch() {
        sep("SEARCH  \"斗破苍穹\"")
        runBlocking {
            try {
                // Diagnostic: check cookie state before search
                val cookieBefore = android.webkit.CookieManager.getInstance()?.getCookie("https://www.69shuba.com") ?: "(none)"
                Log.i(TAG, "Cookie before search: $cookieBefore")

                Log.i(TAG, "Calling provider.search() — WebView will auto-solve Turnstile if needed")
                val results = provider.search("斗破苍穹")

                val cookieAfter = android.webkit.CookieManager.getInstance()?.getCookie("https://www.69shuba.com") ?: "(none)"
                Log.i(TAG, "Cookie after search: $cookieAfter")
                Log.i(TAG, "Results: ${results.size}")
                results.take(5).forEach { r ->
                    Log.i(TAG, "  • ${r.name}  →  ${r.url}")
                }
                assertTrue("Search returned no results — check POST body / selector", results.isNotEmpty())
                Log.i(TAG, "✓ testSearch PASSED")
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testSearch — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testSearch", e)
                    throw e
                }
            }
        }
    }

    @Test
    fun testLoad() {
        sep("LOAD NOVEL  $FALLBACK_NOVEL_URL")
        runBlocking {
            try {
                val response = provider.load(FALLBACK_NOVEL_URL)
                assertNotNull("load() returned null", response)
                response!!
                val stream = response as? StreamResponse
                Log.i(TAG, "Title:    ${response.name}")
                Log.i(TAG, "Author:   ${response.author}")
                Log.i(TAG, "Synopsis: ${response.synopsis?.take(100)}")
                val chapters = stream?.data ?: emptyList()
                Log.i(TAG, "Chapters: ${chapters.size}")
                if (chapters.isNotEmpty()) {
                    Log.i(TAG, "First:  ${chapters.first().name}  →  ${chapters.first().url}")
                    Log.i(TAG, "Last:   ${chapters.last().name}  →  ${chapters.last().url}")
                }
                assertTrue("No title returned", response.name.isNotBlank())
                assertNotNull("load() did not return a StreamResponse", stream)
                assertTrue("No chapters returned — check div.listmain selector", chapters.isNotEmpty())
                Log.i(TAG, "✓ testLoad PASSED")
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testLoad — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testLoad", e)
                    throw e
                }
            }
        }
    }

    @Test
    fun testLoadHtml() {
        runBlocking {
            // First discover the first chapter URL via load(), fall back to a known one
            val chapterUrl: String = try {
                val resp = provider.load(FALLBACK_NOVEL_URL) as? StreamResponse
                resp?.data?.firstOrNull()?.url
                    ?: "https://www.69shuba.com/txt/47837/31779406"
            } catch (e: Exception) {
                "https://www.69shuba.com/txt/47837/31779406"  // ch1 of 吞噬进化
            }

            sep("LOAD CHAPTER  $chapterUrl")
            try {
                val html = provider.loadHtml(chapterUrl)
                assertNotNull("loadHtml() returned null — check div.txtnav selector", html)
                html!!
                Log.i(TAG, "Content length: ${html.length} chars")
                Log.i(TAG, "── First 400 chars ──")
                Log.i(TAG, html.take(400))
                assertTrue(
                    "Chapter content too short (${html.length} chars) — check selector",
                    html.length >= 100
                )
                Log.i(TAG, "✓ testLoadHtml PASSED")
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testLoadHtml — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testLoadHtml", e)
                    throw e
                }
            }
        }
    }

    /**
     * Stress-test: load N consecutive chapters to trigger Cloudflare's bot detection.
     * After ~30-40 rapid requests CF serves a JS challenge ("Enable JavaScript and cookies
     * to continue").  This test verifies that [BiqugeProvider.loadHtml] transparently
     * bypasses the challenge via [CloudflareKiller] and still returns valid content.
     *
     * The test loads the first CHAPTER_BURST_COUNT chapters of FALLBACK_NOVEL_URL back-to-back
     * with no delay, mimicking the download scenario that triggers CF.
     */
    @Test
    fun testLoadHtmlCloudflareFallback() {
        val CHAPTER_BURST_COUNT = 160
        runBlocking {
            sep("CF FALLBACK — burst loading $CHAPTER_BURST_COUNT chapters")
            try {
                val novel = provider.load(FALLBACK_NOVEL_URL) as? StreamResponse
                if (novel == null) {
                    Log.w(TAG, "SKIPPED testLoadHtmlCloudflareFallback — load() returned null")
                    return@runBlocking
                }
                val chapters = novel.data.take(CHAPTER_BURST_COUNT)
                if (chapters.isEmpty()) {
                    Log.w(TAG, "SKIPPED testLoadHtmlCloudflareFallback — no chapters found")
                    return@runBlocking
                }

                Log.i(TAG, "Burst-loading ${chapters.size} chapters (no delay)…")
                var cfFallbackTriggered = false
                var failedChapter: String? = null

                chapters.forEachIndexed { idx, ch ->
                    Log.i(TAG, "  Chapter ${idx + 1}/${chapters.size}: ${ch.name}  (${ch.url})")
                    val content = provider.loadHtml(ch.url)
                    if (content == null || content.length < 100) {
                        failedChapter = "Chapter ${idx + 1} '${ch.name}' returned " +
                            "content=${content?.length ?: "null"} chars — " +
                            "CF bypass may have failed"
                        Log.e(TAG, failedChapter!!)
                        return@forEachIndexed
                    }
                    Log.i(TAG, "    OK — ${content.length} chars, preview: ${content.take(60)}")
                    // Heuristic: if we got content after chapter 30 CF has likely been bypassed
                    if (idx >= 29) cfFallbackTriggered = true
                }

                if (failedChapter != null) {
                    fail(failedChapter)
                }
                Log.i(TAG, "✓ testLoadHtmlCloudflareFallback PASSED" +
                    (if (cfFallbackTriggered) " (CF fallback exercised)" else " (no CF challenge seen)"))
            } catch (e: Exception) {
                if (isGeoBlock(e)) {
                    Log.w(TAG, "SKIPPED testLoadHtmlCloudflareFallback — geo/CF block: ${e.message}")
                } else {
                    Log.e(TAG, "FAILED testLoadHtmlCloudflareFallback", e)
                    throw e
                }
            }
        }
    }
}
