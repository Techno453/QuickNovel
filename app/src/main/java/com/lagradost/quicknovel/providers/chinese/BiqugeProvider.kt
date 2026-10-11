package com.lagradost.quicknovel.providers.chinese

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.cleanChapterText
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.extractChineseTitle
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.textWithEncoding
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.util.Coroutines.main
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Provider for 69shuba.com — a GBK-encoded biquge-family Chinese novel site.
 *
 * Site structure (verified from live HTML, 2026-03):
 *   Search:  POST /modules/article/search.php  body: searchkey=<GBK>&submit=Search
 *            Results: ul.bookList li > h3 a[href]
 *   Novel info:  /book/{id}.htm
 *            Title:    div.booknav2 h1 a
 *            Author:   div.booknav2 p:first-child a
 *            Cover:    div.bookimg2 img
 *            Synopsis: div.navtxt p:first-child
 *   Chapter list: /book/{id}/   (trailing slash)
 *            Chapters: div.catalog#catalog ul li a  (newest-first, reversed on load)
 *   Chapter content: /txt/{id}/{cid}
 *            Content: div.txtnav
 *
 * NOTE: GET pages load fine with plain OkHttp + browser User-Agent — no CF JS challenge.
 * The search POST endpoint has Cloudflare Turnstile.  We solve it by loading the search
 * page in a real WebView (useOkhttp=false) which auto-solves the invisible Turnstile,
 * then reading the resulting verify cookie from CookieManager for the OkHttp POST.
 *
 * Cloudflare rate-limiting: CF triggers a JS challenge after ~30 rapid chapter requests.
 * We avoid it by pausing 20 s every 30 chapters so CF's sliding window resets.
 */
class BiqugeProvider : MainAPI() {
    override val name = "Biquge (Chinese)"
    override val mainUrl = "https://www.69shuba.com"
    override val lang = "zh"
    override val hasMainPage = true
    override val usesCloudFlareKiller = false

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer"    to mainUrl,
    )

    override val orderBys = listOf(
        "Latest Update"   to "lastupdate",
        "Clicks"          to "click",
        "Collected"       to "goodnum"
    )

    override val tags = listOf(
        "All"             to "",
        "Xianxia"         to "14",
        "Xuanhuan"        to "15",
        "Wuxia"           to "5",
        "Urban"           to "1",
        "History"         to "2",
        "Online Game"     to "6",
        "Science Fiction" to "7",
        "Fantasy"         to "11",
        "Military"        to "8",
        "Romance"         to "10"
    )

    // ─── Rate-limiting to avoid Cloudflare JS challenge ───────────────────────────

    /** How many chapter fetches have been made in the current burst. */
    private var chapterFetchCount = 0

    /** Pause every this many chapters to avoid CF's sliding-window bot detection. */
    private val cfPauseEvery = 10

    /** How long to pause (ms) when we hit the rate limit. */
    private val cfPauseMs = 0L//13_000L //5:15

    /**
     * Call before every chapter OkHttp request.
     * Every [cfPauseEvery] calls this inserts a [cfPauseMs] delay so Cloudflare's
     * sliding-window request counter resets before we'd trigger a JS challenge.
     */
    private suspend fun rateLimitDelay() {
        chapterFetchCount++
        if (chapterFetchCount % cfPauseEvery == 0) {
            Log.i("BiqugeProvider", "Rate-limit pause after $chapterFetchCount chapters " +
                "(${cfPauseMs / 1000}s to avoid CF challenge)")
            delay(cfPauseMs)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private suspend fun get(url: String) = app.get(url, headers = headers)

    private suspend fun post(url: String, body: okhttp3.RequestBody, extraHeaders: Map<String, String> = emptyMap()) =
        app.post(url, headers = headers + extraHeaders, requestBody = body)

    /** Parse SearchResponse list from any book-listing page. */
    private fun parseBookList(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // Search results: div.newbox ul li > div.newnav h3
        // The h3 contains two <a> tags: first is a cover image link (empty text),
        // second has the actual book title. Skip links with class="imgbox".
        document.select("div.newbox ul li, ul.bookList li, ul.list li").forEach { el ->
            val linkEl = el.select("div.newnav h3 a[href], h3 a[href], h4 a[href]")
                .firstOrNull { it.text().isNotBlank() } ?: return@forEach
            val title = linkEl.text().trim().ifBlank { return@forEach }
            val href = linkEl.absUrl("href").ifBlank { "$mainUrl${linkEl.attr("href")}" }
            val cover = el.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }
            results.add(newSearchResponse(title, href, fix = false) { posterUrl = cover })
        }

        return results
    }

    // ─── Main page ────────────────────────────────────────────────────────────────

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse {
        val order = orderBy ?: "lastupdate"
        val tagStr = tag ?: ""
        val url = if (tagStr.isBlank()) {
            "$mainUrl/paihang/$order/$page/"
        } else {
            "$mainUrl/novels/class/$tagStr/$order/$page/"
        }
        val doc = Jsoup.parse(get(url).textWithEncoding(), url)
        return HeadMainPageResponse(url, parseBookList(doc))
    }

    // ─── Search ───────────────────────────────────────────────────────────────────

    /**
     * Perform the full search via WebView so Cloudflare Turnstile auto-solves in place.
     *
     * Flow:
     *  1. Load the search form page (GET) in WebView.
     *  2. onPageFinished (first time, search.php URL): inject JS to fill query and submit.
     *  3. WebView navigates via POST; server may return a Turnstile challenge page first.
     *     The invisible Turnstile auto-solves, /verify.php is called, page re-submits.
     *  4. Eventually the search results page loads (same search.php URL but with results).
     *     We detect results by checking for "div.newbox" or "bookList" in the DOM.
     *  5. Extract the page's outer HTML via JS and return it for parsing.
     *
     * Returns the HTML of the results page, or null on timeout/failure.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun searchViaWebView(query: String): String? {
        val searchUrl = "$mainUrl/modules/article/search.php"
        val context = com.lagradost.quicknovel.BaseApplication.Companion.context
            ?: return null

        val resultHtml = java.util.concurrent.atomic.AtomicReference<String?>(null)
        var webView: WebView? = null

        Log.i("BiqugeSearch", "searchViaWebView: starting for query=$query")
        main {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Keep real WebView UA — required for Turnstile

                webViewClient = object : WebViewClient() {

                    private var pageCount = 0

                    override fun onPageFinished(view: WebView, url: String) {
                        pageCount++
                        Log.i("BiqugeSearch", "onPageFinished #$pageCount: $url")

                        when (pageCount) {
                            1 -> {
                                // First page: the search form (GET). Submit the form.
                                val jsQuery = query.replace("\\", "\\\\").replace("'", "\\'")
                                Log.i("BiqugeSearch", "Submitting search form for: $query")
                                view.evaluateJavascript("""
                                    (function() {
                                        var inp = document.querySelector('input[name="searchkey"]') ||
                                                  document.querySelector('input[type="text"]');
                                        if (!inp) return 'no-input';
                                        inp.value = '$jsQuery';
                                        var form = inp.closest('form') || document.querySelector('form');
                                        if (form) { form.submit(); return 'submitted'; }
                                        return 'no-form';
                                    })();
                                """.trimIndent()) { result ->
                                    Log.i("BiqugeSearch", "Form submit JS result: $result")
                                }
                            }
                            2 -> {
                                // Second page: Turnstile challenge page.
                                // Let Turnstile auto-solve — it will call /verify.php and then
                                // the page JS re-submits the search, triggering page 3.
                                Log.i("BiqugeSearch", "Turnstile challenge page loaded — waiting for auto-solve...")
                            }
                            else -> {
                                // Third+ page: Turnstile solved and search re-submitted.
                                // Extract the page HTML.
                                Log.i("BiqugeSearch", "Page $pageCount loaded — extracting HTML")
                                view.evaluateJavascript("document.documentElement.outerHTML;") { value ->
                                    if (value != null && value != "null") {
                                        // evaluateJavascript returns a JSON-encoded string.
                                        // Use JSONArray to properly decode Unicode escapes.
                                        val html = try {
                                            org.json.JSONArray("[$value]").getString(0)
                                        } catch (e: Exception) {
                                            value.trim('"')
                                                .replace("\\n", "\n")
                                                .replace("\\t", "\t")
                                                .replace("\\\"", "\"")
                                                .replace("\\/", "/")
                                                .replace("\\\\", "\\")
                                        }
                                        Log.i("BiqugeSearch", "Got page HTML length=${html.length}")
                                        resultHtml.set(html)
                                        main { webView?.stopLoading(); webView?.destroy(); webView = null }
                                    }
                                }
                            }
                        }
                    }
                }

                loadUrl(searchUrl)
            }
        }

        // Wait up to 30s for results (Turnstile may take time to auto-solve)
        val deadline = System.currentTimeMillis() + 30_000L
        while (resultHtml.get() == null && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        if (resultHtml.get() == null) {
            Log.w("BiqugeSearch", "searchViaWebView: timed out after 30s")
            main { webView?.stopLoading(); webView?.destroy() }
        }
        return resultHtml.get()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/modules/article/search.php"
        val encodedQuery = URLEncoder.encode(query, "GBK")
        val bodyStr = "searchkey=$encodedQuery&searchtype=all&submit=Search"

        fun makeBody() = bodyStr.toByteArray(Charsets.ISO_8859_1)
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        fun currentCookieHeader(): Map<String, String> {
            val c = CookieManager.getInstance()?.getCookie(mainUrl) ?: ""
            return if (c.isNotBlank()) mapOf("Cookie" to c) else emptyMap()
        }

        // Step 1: try a plain OkHttp POST (works if we already have a verify cookie).
        val firstHtml = app.post(
            searchUrl,
            headers = headers + currentCookieHeader(),
            requestBody = makeBody()
        ).textWithEncoding()

        if (!firstHtml.contains("turnstile") && !firstHtml.contains("verify.php")) {
            return parseBookList(Jsoup.parse(firstHtml, searchUrl))
        }

        // Step 2: Turnstile challenge — do the entire search in WebView.
        val webHtml = searchViaWebView(query) ?: return emptyList()
        return parseBookList(Jsoup.parse(webHtml, searchUrl))
    }

    // ─── Load novel ───────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        // Accept both /book/90412.htm and /book/90412/ — normalise to .htm form
        val infoUrl = when {
            url.contains(".htm") -> url
            url.endsWith("/")    -> url.trimEnd('/') + ".htm"
            else                 -> "$url.htm"
        }

        val doc = Jsoup.parse(get(infoUrl).textWithEncoding(), infoUrl)

        // Title: <div class="booknav2"><h1><a>…</a></h1>
        val title = (
            doc.selectFirst("div.booknav2 h1 a")?.text()
            ?: doc.selectFirst("h1 a, h1")?.text()
            ?: doc.title()
        ).extractChineseTitle()

        // Author: first <a> inside <div class="booknav2"><p>作者：<a>
        val author = doc.selectFirst("div.booknav2 p a")?.text()?.trim()

        // Cover: <div class="bookimg2"><img src="…">
        val cover = doc.selectFirst("div.bookimg2 img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.let { if (it.startsWith("http")) it else "$mainUrl$it" }

        // Synopsis: <div class="navtxt"><p>…</p>
        val synopsis = doc.selectFirst("div.navtxt p")?.text()?.trim()

        // Status from booknav2 text (contains "连载" / "完本")
        val statusText = doc.selectFirst("div.booknav2")?.text()

        // Full chapter list at /book/{id}/
        val bookId = infoUrl.substringAfterLast("/book/").removeSuffix(".htm").trimEnd('/')
        val listUrl = "$mainUrl/book/$bookId/"
        val chapters = mutableListOf<com.lagradost.quicknovel.ChapterData>()

        runCatching {
            val listDoc = Jsoup.parse(get(listUrl).textWithEncoding(), listUrl)
            // <div class="catalog" id="catalog"><ul><li data-num="N"><a href="/txt/…">ch title</a>
            // Page shows newest-first; reverse so index 0 = chapter 1
            listDoc.select("div.catalog#catalog ul li a[href], div.catalog ul li a[href]")
                .reversed()
                .forEach { a ->
                    val chTitle = a.text().trim().ifBlank { return@forEach }
                    val chUrl = a.absUrl("href").ifBlank { "$mainUrl${a.attr("href")}" }
                    chapters.add(newChapterData(chTitle, chUrl, fix = false))
                }
        }

        // Fallback: last-5 preview on the info page
        if (chapters.isEmpty()) {
            doc.select("div.qustime ul li a[href]").reversed().forEach { a ->
                val chTitle = (a.selectFirst("span")?.text() ?: a.text()).trim()
                    .ifBlank { return@forEach }
                val chUrl = a.absUrl("href").ifBlank { "$mainUrl${a.attr("href")}" }
                chapters.add(newChapterData(chTitle, chUrl, fix = false))
            }
        }

        return newStreamResponse(title, infoUrl, chapters, fix = false) {
            this.author = author
            this.posterUrl = cover
            this.synopsis = synopsis
            setStatus(statusText)
        }
    }

    // ─── Load chapter ─────────────────────────────────────────────────────────────

    private fun isCfBlocked(html: String): Boolean {
        // CF challenge/block pages are always short and contain these markers
        if (html.length > 50_000) return false
        val lower = html.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("checking your browser") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("cf_captcha_kind") ||
            lower.contains("cloudflare ray id") ||
            (lower.contains("cloudflare") && lower.contains("security check"))
    }

    override suspend fun loadHtml(url: String): String? {
        rateLimitDelay()

        var html = get(url).textWithEncoding()
        if (isCfBlocked(html)) {
            Log.w("BiqugeProvider", "CF block detected — waiting 30s before retry: $url")
            chapterFetchCount = 0  // reset rate-limit counter after a block
            delay(20_000L)
            html = get(url).textWithEncoding()
            if (isCfBlocked(html)) {
                Log.e("BiqugeProvider", "CF block persists after retry: $url")
                return null
            }
        }

        val doc = Jsoup.parse(html, url)

        val contentEl =
            doc.selectFirst("div.txtnav")
            ?: doc.selectFirst("div#content")
            ?: doc.selectFirst("div.showtxt")
            ?: doc.selectFirst("div#chaptercontent")
            ?: doc.selectFirst("div.read-content")
            ?: doc.body().children().maxByOrNull { it.text().length }

        return contentEl?.text()?.cleanChapterText()?.ifBlank { null }
    }
}
