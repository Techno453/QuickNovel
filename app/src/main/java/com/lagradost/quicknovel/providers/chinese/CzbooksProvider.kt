package com.lagradost.quicknovel.providers.chinese

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LengthFilterSupport
import com.lagradost.quicknovel.LengthUnit
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
 * 小說狂人 (czbooks) — https://czbooks.net, a Taiwanese aggregator in **Traditional** Chinese.
 * UTF-8, server-rendered, no login needed. Cloudflare is in front but only sets `__cf_bm` and
 * injects rocket-loader; plain requests with a browser UA get the real pages (verified 2026-10).
 *
 *   Search:  GET /s/<q>    li.novel-item-wrapper cards (Simplified queries match too; the site
 *            converts them)
 *   Browse:  GET /c/{list}/{sort}/{page}. Site-wide lists: normal (精選排行, the homepage's main
 *            ranking without the adult titles of 人氣榜/工口榜), finish (完本榜); sorts day/week/total.
 *            Per genre: /c/{genre}/{day|week|total|new|finish}/{page}. 70 cards a page. Past the
 *            last page the site serves some other page, so the active page in ul.paginate is checked.
 *   Novel:   GET /n/{id}   .novel-detail (.info .title, .author, .thumbnail img, .state table,
 *            .description), ul.hashtag, and the full ul#chapter-list in reading order.
 *   Chapter: GET /n/{id}/{cid}[?chapterNumber=n]   div.content, lines split by <br>.
 */
class CzbooksProvider : MainAPI() {
    override val name = "czbooks (小說狂人)"
    override val mainUrl = CzbooksParser.MAIN_URL
    override val lang = "zh"
    override val hasMainPage = true

    /** Chapters (the latest chapter number; no 字数 in the lists); filtered in the app. */
    override val lengthFilter = LengthFilterSupport(LengthUnit.CHAPTERS)
    override val rateLimitTime = 800L

    override val orderBys = listOf(
        "Popular this week (週排行)" to "week",
        "Popular today (日排行)" to "day",
        "Popular all time (總排行)" to "total",
        "Latest updates (最新, pick a genre)" to "new",
        "Completed (完本)" to "finish",
    )

    /**
     * Genres. Left out on purpose: the two adult categories (情色工口, 耽美工口) and the romance ones
     * (言情, 耽美, 百合, and 女性向 / 女生同人, which list romance and adult romance only).
     */
    override val tags = listOf(
        "All genres" to "",
        "Xuanhuan / fantasy (玄幻奇幻)" to "xuanhuan",
        "Wuxia / xianxia (武俠仙俠)" to "xianxia",
        "Fan fiction, original (原創同人)" to "erciyuan",
        "History / military (軍事歷史)" to "lishi",
        "Sci-fi / future (科幻未來)" to "wangyou",
        "Supernatural (靈異玄幻)" to "lingyi",
        "Adventure fantasy (奇幻冒險)" to "fanatsy",
        "Mystery (推理)" to "suspense",
        "Japanese (日系)" to "japan",
        "TV drama tie-ins (電視劇)" to "drama",
        "Classic literature (經典文學)" to "classicbook",
        "Short stories (短篇)" to "short",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-TW,zh;q=0.9,en;q=0.6",
    )

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String {
        val response = app.get(url, headers = baseHeaders + ("Referer" to referer))
        val body = response.text
        SourceGuard.requireOk(name, response, body)
        return body
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = CzbooksParser.listUrl(tag, orderBy, page)
            ?: throw ErrorLoadingException("$name only lists latest updates per genre. Pick a genre.")
        return HeadMainPageResponse(url, CzbooksParser.parseList(this, Jsoup.parse(fetch(url), url), page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = CzbooksParser.searchUrl(query)
        return CzbooksParser.parseList(this, Jsoup.parse(fetch(url), url), 1)
    }

    override suspend fun load(url: String): LoadResponse {
        val id = CzbooksParser.novelId(url) ?: throw ErrorLoadingException("Not a czbooks novel url: $url")
        val novelUrl = CzbooksParser.novelUrl(id)
        val novel = CzbooksParser.parseNovel(this, Jsoup.parse(fetch(novelUrl), novelUrl))
            ?: throw ErrorLoadingException("$name: could not read the novel page")
        return newStreamResponse(novel.title, novelUrl, novel.chapters, fix = false) {
            author = novel.author
            posterUrl = novel.cover
            synopsis = novel.synopsis
            tags = novel.tags
            status = novel.status
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val id = CzbooksParser.novelId(url)
        return CzbooksParser.parseChapter(fetch(url, if (id != null) CzbooksParser.novelUrl(id) else "$mainUrl/"))
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [CzbooksProvider]; unit-testable on the JVM. */
object CzbooksParser {
    const val MAIN_URL = "https://czbooks.net"

    private val novelIdRegex = Regex("""czbooks\.net/n/([A-Za-z0-9]+)""")
    private val trailingNumberRegex = Regex("""/(\d+)/?$""")
    private val popularSorts = setOf("day", "week", "total")

    fun novelId(url: String): String? = novelIdRegex.find(url)?.groupValues?.get(1)
    fun novelUrl(id: String) = "$MAIN_URL/n/$id"

    fun searchUrl(query: String) = "$MAIN_URL/s/" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    /**
     * Listing url for a genre slug (blank = all genres) and sort. Returns null for "latest" without
     * a genre: the site has no site-wide latest list (`/c/all/new` silently shows the daily ranking).
     */
    fun listUrl(genre: String?, sort: String?, page: Int): String? {
        val s = sort?.ifBlank { null } ?: "week"
        if (genre.isNullOrBlank()) {
            return when (s) {
                "finish" -> "$MAIN_URL/c/finish/week/$page"
                in popularSorts -> "$MAIN_URL/c/normal/$s/$page"
                else -> null
            }
        }
        return "$MAIN_URL/c/$genre/$s/$page"
    }

    /** Page number of the active link in `ul.paginate`, or null if the page has no pager. */
    fun activePage(doc: Document): Int? =
        doc.selectFirst("ul.paginate li.active a[href]")?.attr("href")
            ?.let { trailingNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * Cards of search results and listings. For [requestedPage] > 1 the active pager link must
     * match, because a page past the end comes back as some earlier page instead of an empty one.
     */
    fun parseList(api: MainAPI, doc: Document, requestedPage: Int): List<SearchResponse> {
        if (requestedPage > 1 && activePage(doc) != requestedPage) return emptyList()
        val seen = HashSet<String>()
        return doc.select("li.novel-item-wrapper").mapNotNull { li ->
            val a = li.selectFirst(".novel-item-cover-wrapper a[href], a[href*=/n/]") ?: return@mapNotNull null
            val id = novelId(a.absUrl("href").ifBlank { "https:" + a.attr("href") }) ?: return@mapNotNull null
            if (!seen.add(id)) return@mapNotNull null
            val title = li.selectFirst(".novel-item-title")?.text()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            api.newSearchResponse(title, novelUrl(id), fix = false) {
                posterUrl = coverOrNull(li.selectFirst(".novel-item-thumbnail img"))
                latestChapter = li.selectFirst(".novel-item-newest-chapter a")?.text()?.trim()?.ifEmpty { null }
            }
        }
    }

    /**
     * The cover URL of an `img`, or null for the site's own "no thumbnail" picture
     * (`/images/default_no_thumbnail.jpg`), which most listed novels carry: a card without a
     * cover shows its title, and the poster memo fills in the novel page's cover once opened.
     */
    fun coverOrNull(img: org.jsoup.nodes.Element?): String? {
        val url = img?.absUrl("src")?.ifBlank { null } ?: return null
        return if (url.contains("default_no_thumbnail")) null else url
    }

    data class Novel(
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
        text.contains("完結") || text.contains("完结") || text.contains("完本") -> ReleaseStatus.Completed
        text.contains("連載") || text.contains("连载") -> ReleaseStatus.Ongoing
        else -> null
    }

    fun parseNovel(api: MainAPI, doc: Document): Novel? {
        val detail = doc.selectFirst("div.novel-detail") ?: return null
        val title = detail.selectFirst(".info .title")?.text()?.trim()
            ?.removePrefix("《")?.removeSuffix("》")?.trim()?.ifEmpty { null } ?: return null
        val stateText = detail.select(".state tr")
            .firstOrNull { it.selectFirst("td")?.text()?.contains("連載狀態") == true }
            ?.select("td")?.getOrNull(1)?.text()
            ?: detail.selectFirst(".thumbnail-state")?.text()

        val seen = HashSet<String>()
        val chapters = doc.select("ul#chapter-list li a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (novelId(href) == null || !seen.add(href)) return@mapNotNull null
            api.newChapterData(a.text().trim().ifEmpty { "Chapter ${seen.size}" }, href, fix = false)
        }

        val tags = (listOfNotNull(doc.selectFirst("#novel-category")?.text()) +
                doc.select("ul.hashtag li.item a").take(12).map { it.text() })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        return Novel(
            title = title,
            author = detail.selectFirst(".info .author a")?.text()?.trim()?.ifEmpty { null },
            cover = coverOrNull(detail.selectFirst(".thumbnail img")),
            synopsis = detail.selectFirst(".description")?.let { ReaderHtml.lines(it) }
                ?.joinToString("\n")?.ifBlank { null },
            tags = tags,
            status = status(stateText),
            chapters = chapters,
        )
    }

    fun parseChapter(html: String): String? {
        val content = Jsoup.parse(html, MAIN_URL).selectFirst("div.content") ?: return null
        content.select("script, style, ins").remove()
        return ReaderHtml.fromElement(content)
    }
}
