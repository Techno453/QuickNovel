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
import com.lagradost.quicknovel.providers.CjkStatus
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.cleanChapterText
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.extractChineseTitle
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.paragraphs
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.textWithEncoding
import com.lagradost.quicknovel.util.Coroutines.main
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

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
 *   Browse (verified 2026-10): /novels/{order}_{category}_{status}_{page}.htm
 *            Results: div.newbox ul li (same markup as search results)
 *
 * NOTE: GET pages load fine with plain OkHttp + browser User-Agent — no CF JS challenge.
 * The search POST endpoint has Cloudflare Turnstile.  We solve it by loading the search
 * page in a real WebView (useOkhttp=false) which auto-solves the invisible Turnstile,
 * then reading the resulting verify cookie from CookieManager for the OkHttp POST.
 *
 * Cloudflare rate-limiting: CF triggers a JS challenge after ~30 rapid chapter requests.
 * See the "Rate limiting" section below for how we stay under it.
 */
class BiqugeProvider : MainAPI() {
    override val name = "Biquge (Chinese)"
    override val mainUrl = "https://www.69shuba.com"
    override val lang = "zh"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Referer"    to mainUrl,
    )

    // Browse (verified 2026-10-11). The old /paihang/... and /novels/class/{tag}/{order}/{page}/
    // URLs return 404 since the redesign. Rankings are /novels/{order}_{category}_{status}_{page}.htm
    // (50 per page). Not offered: newhot (新书榜) answers with a Cloudflare managed challenge
    // outside a browser; lastupdate/allvisit/goodnum/postdate/dayvisit rankings are empty. The
    // site has no monthly-ticket (月票) or bookmark (收藏) ranking.
    //
    // Listings ("<ranking>|<status>", status 0 = all, 2 = ongoing 连载, 1 = completed 全本), most
    // popular first like the other sources, so the Browse rail is labelled with the site's
    // default ranking (人气榜) instead of a bare status. Genres ([tags]) apply to every ranking.
    override val mainCategories = listOf(
        "Popular, monthly (人气榜)"             to "monthvisit|0",
        "Popular, weekly (周榜)"                to "weekvisit|0",
        "Recommended (推荐榜)"                  to "allvote|0",
        "Ongoing: popular, monthly (连载人气榜)"   to "monthvisit|2",
        "Completed: popular, monthly (全本人气榜)" to "monthvisit|1",
        "Completed: recommended (全本推荐榜)"     to "allvote|1",
        // /last.html: one page of the 30 latest updated books; ignores genre and status.
        "Latest updates (最近更新)"              to LATEST,
    )

    // Category ids as used by the site's own filters (same ids as /novels/class/{id}.htm).
    // Romance (言情, 3) is left out on purpose.
    override val tags = listOf(
        "All genres"                  to "0",
        "Fantasy & magic (玄幻魔法)"   to "1",
        "Xianxia & wuxia (修真武侠)"   to "2",
        "Time travel (穿越时空)"       to "11",
        "Urban (都市)"                to "9",
        "History & military (历史军事)" to "4",
        "Games & sports (游戏竞技)"    to "5",
        "Sci-fi (科幻空间)"            to "6",
        "Suspense & horror (悬疑惊悚)" to "7",
        "Fan fiction (同人)"           to "8",
        "Officialdom & workplace (官场职场)" to "10",
        "Youth & campus (青春校园)"    to "12",
    )

    // ─── Rate limiting ───────────────────────────────────────────────────────────
    //
    // Cloudflare in front of 69shuba serves a JS challenge after ~30 rapid chapter requests.
    // Two layers keep us under that:
    //  * rateLimitTime: the framework holds rateLimitMutex around every downloader
    //    loadHtml() call and waits this long after each chapter, so bulk downloads are serial
    //    and spaced. We must not lock rateLimitMutex ourselves — the downloader already holds
    //    it while calling loadHtml(), and kotlinx Mutex is not re-entrant.
    //  * A burst pause inside loadHtml(): every CF_BURST_SIZE chapters fetched by *any*
    //    caller (reader, downloader, translation service) we sleep CF_BURST_PAUSE_MS so
    //    Cloudflare's sliding window drains. The counter is atomic because those callers run
    //    on different coroutines/threads.

    override val rateLimitTime = 1000L

    private val chapterFetchCount = AtomicInteger(0)

    private suspend fun rateLimitDelay() {
        val n = chapterFetchCount.incrementAndGet()
        if (n % CF_BURST_SIZE == 0) {
            Log.i(TAG, "Rate-limit pause after $n chapters (${CF_BURST_PAUSE_MS / 1000}s)")
            delay(CF_BURST_PAUSE_MS)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private suspend fun get(url: String) = app.get(url, headers = headers)

    /** Parse SearchResponse list from any book-listing page. Pure; covered by a JVM fixture test. */
    internal fun parseBookList(document: org.jsoup.nodes.Document): List<SearchResponse> {
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

        // Latest updates (/last.html): div.recentupdate2 ul li > a[book] a[chapter] span[date].
        document.select("div.recentupdate2 ul li").forEach { el ->
            val linkEl = el.select("a[href]").firstOrNull { it.attr("href").contains("/book/") }
                ?: return@forEach
            val title = linkEl.text().trim().ifBlank { return@forEach }
            val href = linkEl.absUrl("href").ifBlank { "$mainUrl${linkEl.attr("href")}" }
            results.add(newSearchResponse(title, href, fix = false))
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
        val url = mainPageUrl(page, mainCategory, orderBy, tag)
        // The latest-updates page has no further pages; repeating it would duplicate the list.
        if (url.endsWith("/last.html") && page > 1) return HeadMainPageResponse(url, emptyList())
        val doc = Jsoup.parse(get(url).textWithEncoding(), url)
        return HeadMainPageResponse(url, parseBookList(doc))
    }

    /**
     * Browse URL: a ranking page such as `/novels/monthvisit_9_2_1.htm` (urban, ongoing,
     * page 1), or `/last.html` for the latest updates. [mainCategory] is a [mainCategories]
     * value ("ranking|status" or [LATEST]); a bare status with the ranking in [orderBy] (the
     * shape before 2026-10-12) is still accepted.
     */
    internal fun mainPageUrl(page: Int, mainCategory: String?, orderBy: String?, tag: String?): String {
        val listing = mainCategory?.takeIf { it.isNotBlank() } ?: mainCategories.first().second
        val (order, status) = when {
            listing == LATEST || orderBy == LATEST -> return "$mainUrl/last.html"
            '|' in listing -> listing.substringBefore('|') to listing.substringAfter('|')
            else -> (orderBy?.takeIf { it.isNotBlank() } ?: "monthvisit") to listing
        }
        val category = tag?.takeIf { it.isNotBlank() } ?: "0"
        return "$mainUrl/novels/${order}_${category}_${status}_${page.coerceAtLeast(1)}.htm"
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

        // Full chapter list at /book/{id}/
        val listUrl = catalogUrl(infoUrl)
        val listDoc = runCatching { Jsoup.parse(get(listUrl).textWithEncoding(), listUrl) }.getOrNull()

        return parseLoad(infoUrl, doc, listDoc)
    }

    /** `/book/{id}/` chapter-list URL for a `/book/{id}.htm` info URL. */
    internal fun catalogUrl(infoUrl: String): String {
        val bookId = infoUrl.substringAfterLast("/book/").removeSuffix(".htm").trimEnd('/')
        return "$mainUrl/book/$bookId/"
    }

    /**
     * Pure half of [load]: builds the response from the info page and the chapter-list page
     * ([listDoc] is null when that fetch failed). Covered by a JVM fixture test.
     */
    internal suspend fun parseLoad(
        infoUrl: String,
        doc: org.jsoup.nodes.Document,
        listDoc: org.jsoup.nodes.Document?,
    ): LoadResponse {
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

        // Status: og:novel:status (连载 / 全本 / 完本), else the "71.35万字 | 连载" line.
        val status = CjkStatus.parse(doc.selectFirst("meta[property=og:novel:status]")?.attr("content"))
            ?: CjkStatus.parse(doc.select("div.booknav2 p").firstOrNull { it.text().contains("|") }?.text())

        val tags = parseTags(doc)

        val chapters = mutableListOf<com.lagradost.quicknovel.ChapterData>()

        if (listDoc != null) runCatching {
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
            this.status = status
            this.tags = tags.ifEmpty { null }
        }
    }

    /**
     * Category (分类 / og:novel:category) followed by the site's tags. The tags are only in the
     * page's `bookinfo` script (`tags: '穿越|篮球|...|'`); the visible tag list is filled by JS.
     */
    internal fun parseTags(doc: org.jsoup.nodes.Document): List<String> {
        val category = doc.selectFirst("meta[property=og:novel:category]")?.attr("content")
            ?: doc.selectFirst("div.booknav2 p a[href*=/novels/class/]")?.text()
        val scriptTags = doc.select("script").asSequence()
            .mapNotNull { Regex("""\btags\s*:\s*'([^']*)'""").find(it.data())?.groupValues?.get(1) }
            .firstOrNull()
            ?.split('|')
            .orEmpty()
        return (listOfNotNull(category) + scriptTags)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
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
            Log.w(TAG, "CF block detected — waiting ${CF_BLOCK_RETRY_MS / 1000}s before retry: $url")
            chapterFetchCount.set(0) // start a fresh burst window after the cool-down
            delay(CF_BLOCK_RETRY_MS)
            html = get(url).textWithEncoding()
            if (isCfBlocked(html)) {
                Log.e(TAG, "CF block persists after retry: $url")
                return null
            }
        }

        return parseChapterHtml(html, url)
    }

    companion object {
        private const val TAG = "BiqugeProvider"

        /** [mainCategories] value for the latest-updates page. */
        internal const val LATEST = "last"

        /** Pause after this many chapter fetches (any caller). */
        private const val CF_BURST_SIZE = 25

        /** Length of the burst pause — long enough for Cloudflare's window to drain. */
        private const val CF_BURST_PAUSE_MS = 8_000L

        /** Cool-down before retrying a chapter that hit a Cloudflare block page. */
        private const val CF_BLOCK_RETRY_MS = 20_000L

        /**
         * Extracts chapter text as reader HTML: one `<p>` per paragraph (the same shape other
         * providers such as WtrLab return), paragraphs separated by a blank line so plain-text
         * consumers can still split on "\n\n". Pure, so it is covered by a JVM unit test.
         */
        fun parseChapterHtml(html: String, url: String): String? {
            val doc = Jsoup.parse(html, url)
            val contentEl =
                doc.selectFirst("div.txtnav")
                    ?: doc.selectFirst("div#content")
                    ?: doc.selectFirst("div.showtxt")
                    ?: doc.selectFirst("div#chaptercontent")
                    ?: doc.selectFirst("div.read-content")
                    ?: doc.body().children().maxByOrNull { it.text().length }
                    ?: return null

            // 69shuba puts the heading, an info line, ad slots and nav inside div.txtnav.
            contentEl.select("h1, .txtinfo, #txtright, .bottom-ad, .contentadv, .page1, script, style, ins")
                .remove()

            return contentEl.paragraphs()
                .map { it.cleanChapterText() }
                .filter { it.isNotBlank() }
                .joinToString("\n\n") { "<p>${Entities.escape(it)}</p>" }
                .ifBlank { null }
        }
    }
}
