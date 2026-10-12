package com.lagradost.quicknovel.providers.chinese

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LengthFilterSupport
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.NovelLength
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.providers.SourceGuard
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.paragraphs
import com.lagradost.quicknovel.ui.browse.LengthFilter
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 爱下电子书 (ixdzs) — https://ixdzs8.com (simplified; mirrors ixdzs.tw / ixdzs.hk serve the
 * same ids in traditional characters). UTF-8, no login, no Cloudflare.
 *
 * Verified against the live site (2026-10):
 *   Search:   GET /bsearch?q=<q>                       li.burl → h3.bname a, img, .bauthor, .lz
 *   Browse:   GET /sort/{cat}/index-0-{status}-{order}-{page}.html   (same li.burl cards)
 *   Novel:    GET /read/{id}/   h1, a.bauthor, .n-img img, span.lz, p#intro, .tags a
 *             The full table of contents is inlined in div#a-list ul.u-chapter, oldest first
 *             (the ul.u-chapter.cfirst above it is a newest-first preview and must be ignored).
 *   Chapter:  GET /read/{id}/p{n}.html   article.page-content section > p
 *
 * Chapter pages sit behind a home-made browser check: the first hit returns a tiny page whose
 * script redirects to `?challenge=<token>`; that request answers 302 + a PHPSESSID cookie and
 * every later request with the cookie gets the real page. We replay that once per session.
 */
class IxdzsProvider : MainAPI() {
    override val name = "Ixdzs (爱下电子书)"
    override val mainUrl = "https://ixdzs8.com"
    override val lang = "zh"
    override val hasMainPage = true

    /** Characters (N万字 on the cards); filtered in the app. */
    override val lengthFilter = LengthFilterSupport(LengthUnit.CHARACTERS)
    override val rateLimitTime = 800L

    /**
     * Listings, most popular first (verified 2026-10). "path:<p>" are the all-genre ranking and
     * completed pages (`/hot/`, `/hot/month/`, `/hot/day/`, `/end/`, paged with `?page=n`);
     * "genre:<status>-<order>" is the per-genre list `/sort/{genre}/index-0-{status}-{order}-{page}.html`
     * (status 0 all / 1 ongoing / 2 completed, order 0 latest update / 1 popular). The genre
     * ([tags]) only applies to the "In genre" listings.
     */
    override val mainCategories = listOf(
        "Popular: all time (总榜)" to "path:/hot/",
        "Popular: this month (月榜)" to "path:/hot/month/",
        "Popular: today (日榜)" to "path:/hot/day/",
        "Completed works (全本)" to "path:/end/",
        "In genre: popular" to "genre:0-1",
        "In genre: completed, popular" to "genre:2-1",
        "In genre: ongoing, popular" to "genre:1-1",
        "In genre: latest updates" to "genre:0-0",
    )

    /** Genres. The romance ones (言情穿越 7, 耽美同人 8, 台言古言 9) are left out on purpose. */
    override val tags = listOf(
        "Xuanhuan (玄幻奇幻)" to "1",
        "Xianxia (修真仙侠)" to "2",
        "Urban (都市青春)" to "3",
        "History / military (军事历史)" to "4",
        "Games (网游竞技)" to "5",
        "Sci-fi / supernatural (科幻灵异)" to "6",
        "Wuxia (武侠小说)" to "10",
        "Other (其他小说)" to "0",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6",
    )

    /** Session cookies (PHPSESSID from the browser check); shared by every caller. */
    private val cookies = ConcurrentHashMap<String, String>()

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String {
        val headers = baseHeaders + ("Referer" to referer)
        var response = app.get(url, headers = headers, cookies = cookies.toMap())
        cookies.putAll(response.cookies)
        var body = response.text

        val token = IxdzsParser.challengeToken(body)
        if (token != null) {
            val path = url.substringBefore('?')
            val verify = app.get(
                "$path?challenge=" + URLEncoder.encode(token, "UTF-8"),
                headers = headers + ("Referer" to url),
                cookies = cookies.toMap(),
                allowRedirects = false,
            )
            cookies.putAll(verify.cookies)
            response = app.get(url, headers = headers, cookies = cookies.toMap())
            cookies.putAll(response.cookies)
            body = response.text
            if (IxdzsParser.challengeToken(body) != null) {
                throw ErrorLoadingException("$name: the site's browser check did not accept us; retry later")
            }
        }
        SourceGuard.requireOk(name, response, body)
        return body
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = listingUrl(mainCategory, tag, page)
        return HeadMainPageResponse(url, IxdzsParser.parseList(this, fetch(url)))
    }

    /** URL of a [mainCategories] listing (null = the first one) for [genre] and [page]. */
    internal fun listingUrl(listing: String?, genre: String?, page: Int): String {
        val value = listing ?: mainCategories.first().second
        if (value.startsWith("path:")) {
            val path = value.removePrefix("path:")
            return "$mainUrl$path" + if (page > 1) "?page=$page" else ""
        }
        val (status, order) = value.removePrefix("genre:").split('-').let {
            (it.getOrNull(0) ?: "0") to (it.getOrNull(1) ?: "1")
        }
        return "$mainUrl/sort/${genre ?: tags.first().second}/index-0-$status-$order-$page.html"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/bsearch?q=" + URLEncoder.encode(query, "UTF-8")
        return IxdzsParser.parseList(this, fetch(url))
    }

    override suspend fun load(url: String): LoadResponse {
        val id = IxdzsParser.bookIdFromUrl(url) ?: throw ErrorLoadingException("Not an ixdzs book url: $url")
        val bookUrl = "$mainUrl/read/$id/"
        val book = IxdzsParser.parseBook(this, Jsoup.parse(fetch(bookUrl), bookUrl))
            ?: throw ErrorLoadingException("$name: could not read the book page")
        return newStreamResponse(book.title, bookUrl, book.chapters, fix = false) {
            author = book.author
            posterUrl = book.cover
            synopsis = book.synopsis
            tags = book.tags
            status = book.status
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val id = IxdzsParser.bookIdFromUrl(url)
        return IxdzsParser.parseChapter(fetch(url, referer = if (id != null) "$mainUrl/read/$id/" else "$mainUrl/"))
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [IxdzsProvider]; unit-testable on the JVM. */
object IxdzsParser {
    private val bookIdRegex = Regex("""/read/(\d+)""")
    private val tokenRegex = Regex("""token\s*=\s*"([^"]+)"[\s\S]{0,200}\?challenge=""")

    fun bookIdFromUrl(url: String): String? = bookIdRegex.find(url)?.groupValues?.get(1)

    /** Token of the "正在验证浏览器" interstitial, or null for a normal page. */
    fun challengeToken(html: String): String? {
        if (html.length > 8_000 || !html.contains("challenge")) return null
        return tokenRegex.find(html)?.groupValues?.get(1)
    }

    fun parseList(api: MainAPI, html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, api.mainUrl)
        val seen = HashSet<String>()
        return doc.select("li.burl").mapNotNull { li ->
            val a = li.selectFirst("h3.bname a[href]") ?: return@mapNotNull null
            val id = bookIdFromUrl(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text() }.trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, "${api.mainUrl}/read/$id/", fix = false) {
                posterUrl = li.selectFirst(".l-img img")?.absUrl("src")?.ifBlank { null }
                latestChapter = li.selectFirst(".size")?.text()?.trim()?.ifBlank { null }
                // ".size" is the word count ("289.4万字", "506.01萬字"), not a chapter.
                length = latestChapter?.let { LengthFilter.parseCount(it) }?.let { NovelLength(characters = it) }
            }
        }
    }

    data class Book(
        val title: String,
        val author: String?,
        val cover: String?,
        val synopsis: String?,
        val tags: List<String>,
        val status: ReleaseStatus?,
        val chapters: List<ChapterData>,
    )

    fun status(text: String?): ReleaseStatus? = when {
        text == null -> null
        text.contains("完结") || text.contains("完本") || text.contains("完結") -> ReleaseStatus.Completed
        text.contains("连载") || text.contains("連載") -> ReleaseStatus.Ongoing
        else -> null
    }

    fun parseBook(api: MainAPI, doc: Document): Book? {
        val title = doc.selectFirst("div.n-text h1")?.text()?.trim()?.ifEmpty { null } ?: return null
        val seen = HashSet<String>()
        // div#a-list holds the complete list in reading order; fall back to any u-chapter list
        // that is not the newest-first preview.
        val links = doc.select("div#a-list ul.u-chapter li a[href]")
            .ifEmpty { doc.select("ul.u-chapter:not(.cfirst) li a[href]") }
        val chapters = links.mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!href.contains("/read/") || !seen.add(href)) return@mapNotNull null
            api.newChapterData(a.text().trim().ifEmpty { "Chapter ${seen.size}" }, href, fix = false)
        }
        return Book(
            title = title,
            author = doc.selectFirst("div.n-text a.bauthor")?.text()?.trim()?.ifEmpty { null },
            cover = doc.selectFirst("div.n-img img")?.absUrl("src")?.ifBlank { null },
            synopsis = doc.selectFirst("p#intro")?.paragraphs()?.joinToString("\n")?.ifBlank { null },
            tags = doc.select("div.tags a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = status(doc.selectFirst("div.n-text span.lz")?.text()),
            chapters = chapters,
        )
    }

    /** Lines the site injects into chapter text (its own name / app promotion). */
    private val noise = Regex("""爱下电子书|ixdzs|请记住本站|最新章节请|本章未完""", RegexOption.IGNORE_CASE)

    fun parseChapter(html: String): String? {
        val doc = Jsoup.parse(html)
        val content = doc.selectFirst("article.page-content section")
            ?: doc.selectFirst("article.page-content")?.also { it.select("h1, h3").remove() }
            ?: return null
        content.select("script, style, ins, .ad, [id^=bg-ssp]").remove()
        return content.paragraphs()
            .filter { it.isNotBlank() && !(it.length < 60 && noise.containsMatchIn(it)) }
            .joinToString("\n\n") { "<p>${Entities.escape(it)}</p>" }
            .ifBlank { null }
    }
}
