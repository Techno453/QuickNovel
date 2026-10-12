package com.lagradost.quicknovel.providers.korean

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

/**
 * 노벨피아 (Novelpia) — https://novelpia.com
 *
 * Works without an account for free content (verified live, 2026-10):
 *   Search:   GET /proc/novel?cmd=novel_search&search_type=all&search_val=<q>&page=1&rows=30
 *             &sort_col=<col>  → JSON {status, list:[{novel_no, novel_name, writer_nick,
 *             cover_url, novel_age, novel_type, is_complete, ...}], total_cnt}
 *   Browse:   GET /freestory/all/{date|view|vote}/{page}/all?main_genre=<n>  (server-rendered
 *             div.novelbox list; free-serial "자유연재" works only)
 *   Novel:    GET /novel/{id}    title .epnew-novel-title, author .epnew-writer .writer-name,
 *             synopsis .info-graybox .synopsis, tags .writer-tag .tag, cover og:image
 *   Episodes: POST /proc/episode_list  novel_no=<id>&sort=DOWN&page=<0-based>  (20 per page,
 *             oldest first). Rows tr[data-episode-no]; free rows carry span.b_free / an onclick
 *             to /viewer/<no>, locked PLUS rows open the join modal instead.
 *   Text:     POST /proc/viewer_data/{episode_no}  with Referer /viewer/{episode_no}
 *             → JSON {"s":[{"text": "<line html>"}, ...]}. No cookie is needed for free
 *             episodes; locked episodes return an empty body.
 *
 * Limitations: PLUS (paid/membership) episodes and 19+ works need a logged-in, age-verified
 * account and are skipped — locked episodes are left out of the chapter list and adult works
 * are filtered from search results.
 */
class NovelpiaProvider : MainAPI() {
    override val name = "Novelpia (노벨피아)"
    override val mainUrl = "https://novelpia.com"
    override val lang = "ko"
    override val hasMainPage = true
    override val rateLimitTime = 1000L

    private val baseHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
    )

    /**
     * Listings, most popular first. "top100:<group>/<period>" are the site's Top 100 pages
     * (one page of 100, verified 2026-10), "free:<order>" the paged free-serial list. PLUS works
     * in the Top 100 only show their free episodes, so the free Top 100 comes first.
     * Genres ([tags], `main_genre`) apply to every listing.
     */
    override val mainCategories = listOf(
        "Free works: top 100 today" to "top100:freestory/today",
        "Free works: top 100 this week" to "top100:freestory/weekly",
        "Top 100 today" to "top100:all/today",
        "Top 100 this week" to "top100:all/weekly",
        "Top 100 this month" to "top100:all/monthly",
        "Completed: top 100 this week" to "top100:complete/weekly",
        "Free works: most viewed" to "free:view",
        "Free works: most recommended" to "free:vote",
        "Free works: latest updates" to "free:date",
    )

    override val tags = listOf(
        "All" to "",
        "Fantasy (판타지)" to "1",
        "Martial arts (무협)" to "2",
        "Modern (현대)" to "3",
        "Romance (로맨스)" to "6",
        "Modern fantasy (현대판타지)" to "12",
        "Light novel (라이트노벨)" to "13",
        "Horror (공포)" to "14",
        "Sci-fi (SF)" to "9",
        "Sports (스포츠)" to "8",
        "Alternate history (대체역사)" to "7",
        "Other (기타)" to "10",
        "Parody (패러디)" to "11",
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = listingUrl(mainCategory, tag, page)
            ?: return HeadMainPageResponse("$mainUrl/top100", emptyList()) // a Top 100 has one page
        val html = app.get(url, headers = baseHeaders + ("Referer" to "$mainUrl/")).text
        return HeadMainPageResponse(url, NovelpiaParser.parseBrowse(this, html))
    }

    /** URL of a [mainCategories] listing (null = the first one); null when [page] is past its end. */
    internal fun listingUrl(listing: String?, genre: String?, page: Int): String? {
        val value = listing ?: mainCategories.first().second
        val genreParam = "main_genre=" + genre.orEmpty()
        return if (value.startsWith("top100:")) {
            if (page > 1) null else "$mainUrl/top100/${value.removePrefix("top100:")}/view/all/all?$genreParam"
        } else {
            "$mainUrl/freestory/all/${value.removePrefix("free:").ifBlank { "date" }}/$page/all?$genreParam"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val params = linkedMapOf(
            "cmd" to "novel_search",
            "search_type" to "all",
            "search_val" to query,
            "page" to "1",
            "rows" to "30",
            "sort_col" to "count_view",
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val json = app.get(
            "$mainUrl/proc/novel?$params",
            headers = baseHeaders + ("Referer" to "$mainUrl/search/all//1/")
        ).text
        return NovelpiaParser.parseSearch(this, json)
    }

    override suspend fun load(url: String): LoadResponse {
        val novelNo = NovelpiaParser.novelNoFromUrl(url)
            ?: throw ErrorLoadingException("Not a novelpia novel url: $url")
        val novelUrl = "$mainUrl/novel/$novelNo"
        val doc = Jsoup.parse(app.get(novelUrl, headers = baseHeaders).text, novelUrl)
        val info = NovelpiaParser.parseNovelPage(doc)
            ?: throw ErrorLoadingException("No title (adult-only or removed work?)")

        val chapters = mutableListOf<ChapterData>()
        val seen = HashSet<String>()
        var page = 0
        var lastPage = 0
        var sawFree = false
        do {
            if (page > 0) delay(EPISODE_PAGE_DELAY_MS)
            val listHtml = app.post(
                "$mainUrl/proc/episode_list",
                headers = baseHeaders + ("Referer" to novelUrl),
                data = mapOf("novel_no" to novelNo, "sort" to "DOWN", "page" to page.toString())
            ).text
            val result = NovelpiaParser.parseEpisodeList(this, listHtml, novelNo)
            lastPage = maxOf(lastPage, result.lastPage)
            val fresh = result.freeEpisodes.filter { seen.add(it.url) }
            chapters.addAll(fresh)
            if (fresh.isNotEmpty()) sawFree = true
            // PLUS works only have their first episodes free: once a whole page is locked after
            // free ones, the rest is locked too, so stop paging.
            if (sawFree && result.freeEpisodes.isEmpty() && result.totalRows > 0) break
            page++
        } while (page <= lastPage && page < MAX_EPISODE_PAGES)

        return newStreamResponse(info.title, novelUrl, chapters, fix = false) {
            author = info.author
            synopsis = info.synopsis
            tags = info.tags
            posterUrl = info.cover
            status = info.status
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val episodeNo = NovelpiaParser.episodeNoFromUrl(url) ?: return null
        val json = app.post(
            "$mainUrl/proc/viewer_data/$episodeNo",
            headers = baseHeaders + mapOf(
                "Referer" to "$mainUrl/viewer/$episodeNo",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            data = mapOf("size" to "14")
        ).text
        return NovelpiaParser.parseViewerData(json)
    }

    companion object {
        private const val EPISODE_PAGE_DELAY_MS = 300L
        private const val MAX_EPISODE_PAGES = 500 // 10 000 episodes
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [NovelpiaProvider]; unit-testable on the JVM. */
object NovelpiaParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val novelNoRegex = Regex("""/novel/(\d+)""")
    private val episodeNoRegex = Regex("""/viewer/(\d+)""")
    private val onclickNovelRegex = Regex("""/novel/(\d+)""")

    fun novelNoFromUrl(url: String): String? = novelNoRegex.find(url)?.groupValues?.get(1)
    fun episodeNoFromUrl(url: String): String? = episodeNoRegex.find(url)?.groupValues?.get(1)

    private fun fixImage(src: String?): String? = when {
        src.isNullOrBlank() -> null
        src.startsWith("//") -> "https:$src"
        src.startsWith("/") -> "https://novelpia.com$src"
        else -> src
    }

    private fun JsonNode.str(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    fun parseSearch(api: MainAPI, json: String): List<SearchResponse> {
        val root = mapper.readTree(json)
        if (root.path("status").asInt(200) != 200) return emptyList()
        return root.path("list").mapNotNull { item ->
            // 19+ works need an age-verified login.
            if (item.path("novel_age").asInt(0) >= 19) return@mapNotNull null
            val no = item.str("novel_no") ?: return@mapNotNull null
            val title = item.str("novel_name") ?: return@mapNotNull null
            api.newSearchResponse(title, "${api.mainUrl}/novel/$no", fix = false) {
                posterUrl = fixImage(item.str("cover_url") ?: item.str("novel_img_all"))
            }
        }
    }

    /**
     * Free-serial lists (`b.name_st` carrying the onclick, `img.cover_style`) and Top 100 pages
     * (`div.novelbox.mobile_hidden`, onclick on an inner div, title `b.cut_line_one`, cover
     * `img.scale`).
     */
    fun parseBrowse(api: MainAPI, html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, api.mainUrl)
        val seen = HashSet<String>()
        // Each work is rendered twice (desktop .novelbox + a mobile box); use desktop.
        return doc.select("div.novelbox").mapNotNull { box ->
            val titleEl = box.selectFirst("b.name_st") ?: box.selectFirst("b.cut_line_one")
                ?: return@mapNotNull null
            val onclick = titleEl.attr("onclick").ifBlank {
                box.selectFirst("[onclick*=/novel/]")?.attr("onclick").orEmpty()
            }
            val no = onclickNovelRegex.find(onclick)?.groupValues?.get(1)
                ?: return@mapNotNull null
            if (!seen.add(no)) return@mapNotNull null
            api.newSearchResponse(titleEl.text().trim(), "${api.mainUrl}/novel/$no", fix = false) {
                posterUrl = fixImage((box.selectFirst("img.cover_style") ?: box.selectFirst("img.scale"))?.attr("src"))
            }
        }
    }

    data class NovelInfo(
        val title: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val cover: String?,
        val status: ReleaseStatus?,
    )

    /** Text where only `<br>` counts as a line break (source newlines are formatting noise). */
    private fun brText(el: Element): String? {
        val sb = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText.replace(Regex("""\s*[\r\n]+\s*"""), ""))
                is Element -> if (node.normalName() == "br") sb.append('\n') else node.childNodes().forEach { walk(it) }
            }
        }
        el.childNodes().forEach { walk(it) }
        return sb.lines().joinToString("\n") { it.trim() }.trim().ifEmpty { null }
    }

    fun parseNovelPage(doc: Document): NovelInfo? {
        val title = doc.selectFirst(".epnew-novel-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val badges = doc.selectFirst(".epnew-writer .in-badge")
        val status = when {
            badges?.selectFirst(".b_comp") != null -> ReleaseStatus.Completed
            badges?.selectFirst(".b_stop") != null -> ReleaseStatus.Dropped
            badges?.selectFirst(".b_delay") != null -> ReleaseStatus.Paused
            else -> ReleaseStatus.Ongoing
        }
        return NovelInfo(
            title = title,
            author = doc.selectFirst(".epnew-writer .writer-name")?.text()?.trim(),
            synopsis = (doc.selectFirst(".info-graybox .synopsis") ?: doc.selectFirst(".synopsis-story"))
                ?.let { brText(it) },
            tags = doc.select(".writer-tag .tag").map { it.text().trim().removePrefix("#") }
                .filter { it.isNotEmpty() }.distinct(),
            cover = fixImage(doc.selectFirst("meta[property=og:image]")?.attr("content")),
            status = status,
        )
    }

    data class EpisodePage(
        val freeEpisodes: List<ChapterData>,
        val totalRows: Int,
        val lastPage: Int,
    )

    fun parseEpisodeList(api: MainAPI, html: String, novelNo: String): EpisodePage {
        val doc = Jsoup.parse(html, api.mainUrl)
        val rows = doc.select("tr[data-episode-no]")
        val free = rows.mapNotNull { row ->
            val no = row.attr("data-episode-no").ifBlank { return@mapNotNull null }
            val cell = row.selectFirst("td.font12") ?: return@mapNotNull null
            val isFree = row.selectFirst(".b_free") != null || cell.attr("onclick").contains("/viewer/")
            if (!isFree) return@mapNotNull null
            val title = cell.selectFirst("b")?.ownText()?.replace(' ', ' ')?.trim()
                ?.takeIf { it.isNotEmpty() }
            val epLabel = row.select(".ep_style2 span").firstOrNull { it.ownText().startsWith("EP.") }
                ?.ownText()?.trim()
            api.newChapterData(title ?: epLabel ?: "Episode $no", "${api.mainUrl}/viewer/$no", fix = false) {
                dateOfRelease = row.selectFirst(".ep_style2 b")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        val pageRegex = Regex("""novel_page_${Regex.escape(novelNo)}'\]\s*=\s*'(\d+)'""")
        val lastPage = pageRegex.findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
        return EpisodePage(free, rows.size, lastPage)
    }

    /** Converts the viewer JSON to reader HTML; null for locked or missing episodes. */
    fun parseViewerData(json: String): String? {
        if (json.isBlank()) return null
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val lines = root.path("s")
        if (!lines.isArray || lines.size() == 0) return null

        val out = lines.mapNotNull { line ->
            val fragment = Jsoup.parseBodyFragment(line.path("text").asText(""))
            fragment.select(".cover-wrapper, script, style").remove()
            // Invisible anti-copy watermark: <p style='height: 0px; width: 0px; opacity: 0 ...'>
            fragment.select("[style]").filter {
                val style = it.attr("style").replace(" ", "").lowercase()
                style.contains("height:0px") || style.contains("opacity:0") || style.contains("display:none")
            }.forEach { it.remove() }

            val images = fragment.select("img[src]").mapNotNull { fixImage(it.attr("src")) }
            val text = fragment.body().text().replace(' ', ' ').trim()
            when {
                images.isNotEmpty() -> images.joinToString("") { "<p><img src=\"${Entities.escape(it)}\"></p>" } +
                        if (text.isNotEmpty()) "<p>${Entities.escape(text)}</p>" else ""

                text.isNotEmpty() -> "<p>${Entities.escape(text)}</p>"
                else -> null
            }
        }
        return out.joinToString("\n\n").ifBlank { null }
    }
}
