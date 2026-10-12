package com.lagradost.quicknovel.providers.chinese

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.providers.ReaderHtml
import com.lagradost.quicknovel.providers.SourceGuard
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 晋江文学城 (JJWXC) — https://www.jjwxc.net, the main site for female-oriented web novels
 * (romance, danmei, modern drama). Desktop pages, server-rendered, GB18030 (the meta tag says
 * gb18030 or GBK, the Content-Type header carries no charset, and small pages such as the
 * lock notice even declare utf-8 while being GBK), so every page is decoded as GB18030.
 *
 * Verified against the live site (2026-10):
 *   Search:  GET /search.php?kw=<GB18030 %-encoded>&t=1   #search_result h3.title a, div.info
 *   Browse:  GET /bookbase.php?[xx{n}={n}&]sortType=&isfinish=&page=   (作品库, 100 rows a page;
 *            logged out only pages 1-10 are served, page 11+ is "请登入后再访问此页面")
 *   Book:    GET /onebook.php?novelid=N   h1 [itemprop=articleSection], [itemprop=author],
 *            img.noveldefaultimage, #novelintro, [itemprop=genre|updataStatus], and the whole
 *            chapter table (tr[itemprop~=chapter]) in reading order.
 *   Chapter: GET /onebook.php?novelid=N&chapterid=M   #paragraph_comment_content, lines split
 *            by <br>. The author's note (作者有话说, #note_str) is not included.
 *
 * Free chapters have `a[itemprop=url][href*=chapterid]`. VIP chapters have no href, only
 * `rel=//my.jjwxc.net/onebook_vip.php?...` and a red [VIP] marker; requesting one 302s to the
 * login page. Chapters locked by the author or the site ([锁]) have no link at all and their
 * URL answers with a `#lockpage` notice. Both kinds are left out of the chapter list (the
 * synopsis says how many), so downloads never hit a login wall.
 */
class JjwxcProvider : MainAPI() {
    override val name = "JJWXC (晋江文学城)"
    override val mainUrl = JjwxcParser.MAIN_URL
    override val lang = "zh"
    override val hasMainPage = true
    override val rateLimitTime = 1200L

    /** isfinish */
    override val mainCategories = listOf(
        "All" to "0",
        "Completed (完结)" to "2",
        "Ongoing (连载)" to "1",
    )

    /** sortType of the 作品库 */
    override val orderBys = listOf(
        "Popular (积分)" to "2",
        "Most favourited (收藏数)" to "4",
        "Latest updates (最近更新)" to "1",
        "Newest (最新发表)" to "3",
        "Completed, top rated (完结高分)" to "10",
    )

    /** 性向 filter (xx{n}={n}) */
    override val tags = listOf(
        "All" to "",
        "Romance (言情)" to "1",
        "BL / danmei (纯爱)" to "2",
        "GL (百合)" to "3",
        "No romance (无CP)" to "5",
        "Multiple (多元)" to "6",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6",
    )

    private data class Page(val html: String, val finalUrl: String)

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): Page {
        val response = app.get(url, headers = baseHeaders + ("Referer" to referer))
        val body = JjwxcParser.decode(
            response.okhttpResponse.body.bytes(),
            response.okhttpResponse.header("Content-Type"),
        )
        SourceGuard.requireOk(name, response, body)
        return Page(body, response.url)
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = JjwxcParser.listUrl(page, mainCategory, orderBy, tag)
        // Logged out, the 作品库 stops after page 10; past that there is nothing more to show.
        if (page > JjwxcParser.MAX_LIST_PAGE) return HeadMainPageResponse(url, emptyList())
        val html = fetch(url, "$mainUrl/bookbase.php").html
        return HeadMainPageResponse(url, JjwxcParser.parseList(this, Jsoup.parse(html, url)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = JjwxcParser.searchUrl(query)
        return JjwxcParser.parseSearch(this, Jsoup.parse(fetch(url).html, url))
    }

    override suspend fun load(url: String): LoadResponse {
        val id = JjwxcParser.novelId(url) ?: throw ErrorLoadingException("Not a JJWXC book url: $url")
        val bookUrl = JjwxcParser.bookUrl(id)
        val book = JjwxcParser.parseBook(this, Jsoup.parse(fetch(bookUrl).html, bookUrl), id)
            ?: throw ErrorLoadingException("$name: could not read the book page (removed or locked?)")
        return newStreamResponse(book.title, bookUrl, book.chapters, fix = false) {
            author = book.author
            posterUrl = book.cover
            synopsis = book.synopsisWithNotes()
            tags = book.tags
            status = book.status
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val id = JjwxcParser.novelId(url)
        val page = fetch(url, if (id != null) JjwxcParser.bookUrl(id) else "$mainUrl/")
        if (JjwxcParser.isVipRedirect(page.finalUrl)) {
            throw ErrorLoadingException(JjwxcParser.VIP_MESSAGE)
        }
        return JjwxcParser.parseChapter(page.html)
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [JjwxcProvider]; unit-testable on the JVM. */
object JjwxcParser {
    const val MAIN_URL = "https://www.jjwxc.net"
    const val MAX_LIST_PAGE = 10
    const val VIP_MESSAGE =
        "JJWXC: this is a VIP chapter. It is locked behind a JJWXC account and purchase, so it cannot be loaded."
    const val LOCKED_MESSAGE =
        "JJWXC: this chapter is locked by the author or the site (锁章) and cannot be read."

    private val novelIdRegex = Regex("""novelid=(\d+)""")
    private val mobileIdRegex = Regex("""/book2/(\d+)""")
    private val chapterIdRegex = Regex("""chapterid=(\d+)""")
    private val charsetRegex = Regex("""charset=([\w-]+)""", RegexOption.IGNORE_CASE)

    /**
     * JJWXC pages are GB18030 whatever they declare (the lock notice says utf-8 and is GBK), so
     * only an explicit UTF-8 Content-Type charset switches decoding to UTF-8.
     */
    fun decode(bytes: ByteArray, contentType: String?): String {
        val declared = contentType?.let { charsetRegex.find(it)?.groupValues?.get(1) }?.lowercase()
        return if (declared == "utf-8" || declared == "utf8") {
            bytes.toString(Charsets.UTF_8)
        } else {
            bytes.toString(charset("GB18030"))
        }
    }

    fun novelId(url: String): String? =
        (novelIdRegex.find(url) ?: mobileIdRegex.find(url))?.groupValues?.get(1)

    fun bookUrl(id: String) = "$MAIN_URL/onebook.php?novelid=$id"
    fun chapterUrl(id: String, chapterId: String) = "$MAIN_URL/onebook.php?novelid=$id&chapterid=$chapterId"

    fun searchUrl(query: String) =
        "$MAIN_URL/search.php?kw=" + URLEncoder.encode(query, "GB18030") + "&t=1"

    fun listUrl(page: Int, isFinish: String?, sortType: String?, xx: String?): String {
        val filter = if (xx.isNullOrBlank()) "" else "xx$xx=$xx&"
        return "$MAIN_URL/bookbase.php?${filter}sortType=${sortType ?: "2"}&isfinish=${isFinish ?: "0"}&page=$page"
    }

    /** The VIP chapter url redirects to my.jjwxc.net/onebook_vip.php and from there to login. */
    fun isVipRedirect(finalUrl: String) =
        finalUrl.contains("onebook_vip.php") || finalUrl.contains("my.jjwxc.net/login.php")

    /** The 作品库 page past the logged-out limit ("请登入后再访问此页面"). */
    fun isLoginWall(doc: Document) = doc.text().contains("后再访问此页面")

    /** Rows of the 作品库 (bookbase) and ranking (topten) tables: author cell + title cell. */
    fun parseList(api: MainAPI, doc: Document): List<SearchResponse> {
        if (isLoginWall(doc)) return emptyList()
        val seen = HashSet<String>()
        // Only the row's own cells: the list table sits inside layout tables whose rows "contain" it.
        return doc.select("tr").mapNotNull { tr ->
            if (tr.selectFirst("> td > a[href*=oneauthor.php]") == null) return@mapNotNull null
            val a = tr.selectFirst("> td > a[href*=onebook.php?novelid]") ?: return@mapNotNull null
            val id = novelId(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, bookUrl(id), fix = false)
        }
    }

    fun parseSearch(api: MainAPI, doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select("#search_result h3.title a[href*=novelid]").mapNotNull { a ->
            val id = novelId(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, bookUrl(id), fix = false)
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
        /** Chapter numbers of VIP (paid) chapters, left out of [chapters]. */
        val vipChapters: List<Int>,
        /** Chapter numbers locked by the author or the site, left out of [chapters]. */
        val lockedChapters: List<Int>,
    ) {
        fun synopsisWithNotes(): String? {
            val notes = buildList {
                if (vipChapters.isNotEmpty()) {
                    add(
                        "[JJWXC: ${vipChapters.size} VIP chapters (from chapter ${vipChapters.min()}) need a " +
                                "JJWXC account and purchase, and are not listed.]"
                    )
                }
                if (lockedChapters.isNotEmpty()) {
                    add("[JJWXC: ${lockedChapters.size} chapters locked by the author or the site are not listed.]")
                }
            }
            return (listOfNotNull(synopsis) + notes).joinToString("\n\n").ifBlank { null }
        }
    }

    fun status(text: String?): ReleaseStatus? = when {
        text == null -> null
        text.contains("完结") || text.contains("完結") -> ReleaseStatus.Completed
        text.contains("连载") || text.contains("連載") -> ReleaseStatus.Ongoing
        text.contains("暂停") -> ReleaseStatus.Paused
        else -> null
    }

    private val numberedTitleRegex = Regex("""^第[0-9０-９零〇一二三四五六七八九十百千两]+[章节回卷]""")

    /**
     * "第N章 <title>". Many authors give every chapter the book title as its headline (难哄 does);
     * then the one-line summary (内容提要) is the more useful name.
     */
    fun chapterName(number: String, headline: String, summary: String, bookTitle: String): String {
        if (numberedTitleRegex.containsMatchIn(headline)) return headline
        val label = if (headline.isBlank() || headline == bookTitle) summary.ifBlank { headline } else headline
        return if (label.isBlank()) "第${number}章" else "第${number}章 $label"
    }

    fun parseBook(api: MainAPI, doc: Document, id: String): Book? {
        val title = (doc.selectFirst("h1[itemprop=name] span[itemprop=articleSection]")
            ?: doc.selectFirst("h1[itemprop=name]"))?.text()?.trim()?.ifEmpty { null } ?: return null

        val chapters = ArrayList<ChapterData>()
        val vip = ArrayList<Int>()
        val locked = ArrayList<Int>()
        for (tr in doc.select("tr[itemprop~=chapter]")) {
            val cells = tr.select("> td")
            if (cells.size < 2) continue
            val number = cells[0].text().trim()
            val n = number.toIntOrNull() ?: continue
            val link = tr.selectFirst("a[itemprop=url][href*=chapterid]")
            when {
                link != null -> {
                    val chapterId = chapterIdRegex.find(link.attr("href"))?.groupValues?.get(1) ?: continue
                    val summary = cells.getOrNull(2)?.text()?.trim().orEmpty()
                    chapters.add(
                        api.newChapterData(
                            chapterName(number, link.text().trim(), summary, title),
                            chapterUrl(id, chapterId),
                            fix = false,
                        ) {
                            dateOfRelease = cells.last()?.selectFirst("span")?.text()?.trim()?.ifEmpty { null }
                        }
                    )
                }

                tr.selectFirst("a[rel*=onebook_vip], a[id^=vip_]") != null -> vip.add(n)
                else -> locked.add(n)
            }
        }

        val tags = (doc.select("div.smallreadbody a[href*=bookbase.php?bq=]").map { it.text().trim() } +
                listOfNotNull(doc.selectFirst("span[itemprop=genre]")?.text()?.trim()))
            .filter { it.isNotEmpty() }.distinct()

        return Book(
            title = title,
            author = doc.selectFirst("span[itemprop=author]")?.text()?.trim()?.ifEmpty { null },
            cover = doc.selectFirst("img.noveldefaultimage")?.absUrl("src")?.ifBlank { null },
            synopsis = doc.selectFirst("#novelintro")?.let { ReaderHtml.lines(it) }
                ?.joinToString("\n")?.ifBlank { null },
            tags = tags,
            status = status(doc.selectFirst("[itemprop=updataStatus]")?.text()),
            chapters = chapters,
            vipChapters = vip,
            lockedChapters = locked,
        )
    }

    /** Chapter text, or throws [ErrorLoadingException] for a lock notice / VIP login page. */
    fun parseChapter(html: String): String? {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("#lockpage") != null) throw ErrorLoadingException(LOCKED_MESSAGE)
        val content = doc.selectFirst("#paragraph_comment_content")
        if (content == null) {
            if (doc.title().contains("用户登入") || doc.selectFirst("form[action*=login]") != null) {
                throw ErrorLoadingException(VIP_MESSAGE)
            }
            return null
        }
        content.select("script, style, ins").remove()
        return ReaderHtml.fromElement(content)
    }
}
