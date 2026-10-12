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
import com.lagradost.quicknovel.providers.SourceGuard
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.paragraphs
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils.textWithEncoding
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.net.URLEncoder

/**
 * 飘天文学 (piaotia, "PT文学") — https://www.piaotia.com, a long-running jieqi-CMS site.
 * GBK encoded, server-rendered, no login. Cloudflare sits in front of it but only injects its
 * passive bot script; plain requests with a browser UA get normal pages (verified 2026-10).
 *
 *   Search:  GET /modules/article/search.php?searchtype=articlename&searchkey=<GBK %-encoded>
 *            → table.grid rows; an exact title match 302-redirects straight to the book page.
 *   Browse:  GET /booksort{cat}/0/{page}.html (latest update) or /booktop{type}/0/{page}.html
 *   Book:    GET /bookinfo/{shard}/{id}.html   h1, "作    者：", "文章状态：", cover, 内容简介
 *   TOC:     GET /html/{shard}/{id}/index.html  div.centent li a  (oldest first, relative hrefs)
 *   Chapter: GET /html/{shard}/{id}/{cid}.html  text is loose in <body> between div.toplink
 *            and div.bottomlink, lines separated by <br>.
 */
class PiaotiaProvider : MainAPI() {
    override val name = "Piaotia (飘天文学)"
    override val mainUrl = "https://www.piaotia.com"
    override val lang = "zh"
    override val hasMainPage = true
    override val rateLimitTime = 1000L

    /**
     * Listings, most popular first (verified 2026-10): "top:<type>" = the all-genre toplists
     * `/booktop{type}/0/{page}.html`, "full" = completed works
     * (`/modules/article/articlelist.php?fullflag=1&page=n`), "sort" = latest updates of one genre
     * `/booksort{genre}/0/{page}.html`. The genre ([tags]) only applies to "sort".
     */
    override val mainCategories = listOf(
        "Most viewed this week (周排行)" to "top:weekvisit",
        "Most viewed this month (月排行)" to "top:monthvisit",
        "Most viewed of all time (总排行)" to "top:allvisit",
        "Most recommended this week (周推荐)" to "top:weekvote",
        "Most recommended of all time" to "top:allvote",
        "Most favourited" to "top:goodnum",
        "Completed works (全本)" to "full",
        "New works" to "top:postdate",
        "Latest updates in genre" to "sort",
    )

    override val tags = listOf(
        "All" to "",
        "Xuanhuan / magic (玄幻魔法)" to "1",
        "Wuxia / xianxia (武侠修真)" to "2",
        "Urban / romance (都市言情)" to "3",
        "History / military (历史军事)" to "4",
        "Games (网游竞技)" to "5",
        "Sci-fi (科幻小说)" to "6",
        "Horror (恐怖灵异)" to "7",
        "Fan fiction (同人漫画)" to "8",
        "Other (其他类型)" to "9",
    )

    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.6",
        "Referer" to "$mainUrl/",
    )

    /** Returns the decoded page and the final URL after redirects. */
    private suspend fun fetch(url: String): Pair<String, String> {
        val response = app.get(url, headers = headers)
        val body = response.textWithEncoding()
        SourceGuard.requireOk(name, response, body)
        return body to response.url
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = listingUrl(mainCategory, tag, page)
        val (html, finalUrl) = fetch(url)
        return HeadMainPageResponse(url, PiaotiaParser.parseGrid(this, Jsoup.parse(html, finalUrl)))
    }

    /** URL of a [mainCategories] listing (null = the first one) for [genre] and [page]. */
    internal fun listingUrl(listing: String?, genre: String?, page: Int): String {
        val value = listing ?: mainCategories.first().second
        return when {
            value.startsWith("top:") -> "$mainUrl/booktop${value.removePrefix("top:")}/0/$page.html"
            value == "full" -> "$mainUrl/modules/article/articlelist.php?fullflag=1" + if (page > 1) "&page=$page" else ""
            else -> "$mainUrl/booksort${genre.orEmpty()}/0/$page.html"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/modules/article/search.php?searchtype=articlename&searchkey=" +
                URLEncoder.encode(query, "GBK")
        val (html, finalUrl) = fetch(url)
        val doc = Jsoup.parse(html, finalUrl)
        if (finalUrl.contains("/bookinfo/")) {
            // Exact title match: the site skips the result list.
            val info = PiaotiaParser.parseInfo(doc) ?: return emptyList()
            val ids = PiaotiaParser.ids(finalUrl) ?: return emptyList()
            return listOf(newSearchResponse(info.title, PiaotiaParser.infoUrl(mainUrl, ids), fix = false) {
                posterUrl = info.cover
            })
        }
        return PiaotiaParser.parseGrid(this, doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val ids = PiaotiaParser.ids(url) ?: throw ErrorLoadingException("Not a piaotia book url: $url")
        val infoUrl = PiaotiaParser.infoUrl(mainUrl, ids)
        val (infoHtml, _) = fetch(infoUrl)
        val info = PiaotiaParser.parseInfo(Jsoup.parse(infoHtml, infoUrl))
            ?: throw ErrorLoadingException("$name: could not read the book page")
        val tocUrl = "$mainUrl/html/${ids.first}/${ids.second}/index.html"
        val (tocHtml, _) = fetch(tocUrl)
        val chapters = PiaotiaParser.parseToc(this, Jsoup.parse(tocHtml, tocUrl))

        return newStreamResponse(info.title, infoUrl, chapters, fix = false) {
            author = info.author
            posterUrl = info.cover
            synopsis = info.synopsis
            tags = info.tags
            status = info.status
        }
    }

    override suspend fun loadHtml(url: String): String? = PiaotiaParser.parseChapter(fetch(url).first)

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [PiaotiaProvider]; unit-testable on the JVM. */
object PiaotiaParser {
    private val infoRegex = Regex("""/bookinfo/(\d+)/(\d+)\.html""")
    private val htmlRegex = Regex("""/html/(\d+)/(\d+)(?:/|$)""")

    /** (shard, bookId) from a bookinfo, index or chapter url. */
    fun ids(url: String): Pair<String, String>? {
        val m = infoRegex.find(url) ?: htmlRegex.find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    fun infoUrl(mainUrl: String, ids: Pair<String, String>) = "$mainUrl/bookinfo/${ids.first}/${ids.second}.html"

    /** Rows of the `table.grid` used by search results, category lists and rankings. */
    fun parseGrid(api: MainAPI, doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select("table.grid tr").mapNotNull { tr ->
            val a = tr.selectFirst("a[href*=/bookinfo/]") ?: return@mapNotNull null
            val ids = ids(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(ids.second)) return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, infoUrl(api.mainUrl, ids), fix = false) {
                latestChapter = tr.selectFirst("a[href*=/html/]")?.text()?.trim()?.ifEmpty { null }
            }
        }
    }

    data class Info(
        val title: String,
        val author: String?,
        val cover: String?,
        val synopsis: String?,
        val tags: List<String>,
        val status: ReleaseStatus?,
    )

    private fun field(doc: Document, label: String): String? =
        doc.select("td").map { it.text().replace(' ', ' ') }
            .firstOrNull { it.replace(" ", "").replace("　", "").startsWith(label) }
            ?.substringAfter('：')?.trim()?.ifEmpty { null }

    fun parseInfo(doc: Document): Info? {
        val title = doc.selectFirst("#content h1, h1")?.text()?.trim()?.ifEmpty { null } ?: return null
        // <div><span class="hottext">最新章节：</span>…<br/><span class="hottext">内容简介：</span><br/>text<br/>…
        val synopsisLines = doc.select("span.hottext").firstOrNull { it.text().contains("内容简介") }
            ?.parent()
            ?.paragraphs()
            ?.dropWhile { !it.startsWith("内容简介") }
            ?.mapIndexed { i, line -> if (i == 0) line.substringAfter('：').trim() else line }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val statusText = field(doc, "文章状态")
        return Info(
            title = title,
            author = field(doc, "作者"),
            cover = doc.selectFirst("img[src*=/files/article/image/]")?.absUrl("src")?.ifBlank { null },
            synopsis = synopsisLines.joinToString("\n").ifBlank { null },
            tags = listOfNotNull(field(doc, "类别")),
            status = when {
                statusText == null -> null
                statusText.contains("完成") || statusText.contains("完结") || statusText.contains("完本") -> ReleaseStatus.Completed
                statusText.contains("连载") -> ReleaseStatus.Ongoing
                else -> null
            },
        )
    }

    fun parseToc(api: MainAPI, doc: Document): List<ChapterData> {
        val seen = HashSet<String>()
        return doc.select("div.centent li a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!href.endsWith(".html") || href.endsWith("/index.html") || !seen.add(href)) return@mapNotNull null
            api.newChapterData(a.text().trim().ifEmpty { "Chapter ${seen.size}" }, href, fix = false)
        }
    }

    private val chapterTitleRegex = Regex("""^第[0-9０-９零〇一二三四五六七八九十百千两]+[章节回]""")
    private val endMarkerRegex = Regex("""[（(]本章完[)）]""")

    fun parseChapter(html: String): String? {
        val doc = Jsoup.parse(html)
        val heading = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        doc.select(
            "head, script, style, h1, #guild, #shop, .toplink, .bottomlink, table, center, " +
                    "#Commenddiv, #feit2, .top, iframe, ins"
        ).remove()
        val lines = doc.body().paragraphs().toMutableList()
        // The chapter title is repeated as the first text line, sometimes with slightly different
        // punctuation than the <h1>.
        if (lines.isNotEmpty() && (heading.isNotEmpty() && heading.endsWith(lines.first()) ||
                    lines.first().length < 50 && chapterTitleRegex.containsMatchIn(lines.first()))
        ) {
            lines.removeAt(0)
        }
        return lines.filter { it.isNotBlank() && !endMarkerRegex.matches(it) }
            .joinToString("\n\n") { "<p>${Entities.escape(it)}</p>" }
            .ifBlank { null }
    }
}
