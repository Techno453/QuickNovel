package com.lagradost.quicknovel.providers.korean

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
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 네이버 웹소설 (Naver Webnovel) — https://novel.naver.com, the free amateur leagues only:
 * 베스트리그 (best) and 챌린지리그 (challenge). Every episode there is free and readable without
 * an account; the paid 오늘의 웹소설 / 시리즈 catalogue (series.naver.com, cookie-gated) is out of
 * scope. Server-rendered UTF-8 HTML, no Cloudflare (verified 2026-10).
 *
 *   Search:  GET /search?keyword=<q>&section={best|challenge}&target=all
 *            li.item > a[href=/{league}/list?novelId=N] with .title / .author / img
 *   Browse:  GET /{league}/genre?genre=<g>&order={Update|Read|Like}&page=<n>   (same cards)
 *   Novel:   GET /{league}/list?novelId=N&order=Oldest&page=<n>
 *            .section_area_info (h2.title, author link, #summaryText, thumbnail), .tag_collection,
 *            episode rows ul.list_type2 li a.list_item (.subj, .date), 10 per page; the total is
 *            in .past_number "(19)".
 *   Episode: GET /{league}/detail?novelId=N&volumeNo=V   div.detail_view_content — one <p>
 *            holding newline-separated text (sometimes several <p>s and <img>s).
 */
class NaverWebNovelProvider : MainAPI() {
    override val name = "Naver Webnovel (네이버 웹소설)"
    override val mainUrl = "https://novel.naver.com"
    override val lang = "ko"
    override val hasMainPage = true
    override val rateLimitTime = 800L

    override val mainCategories = listOf(
        "Best league (베스트리그)" to "best",
        "Challenge league (챌린지리그)" to "challenge",
    )

    override val orderBys = listOf(
        "Latest update" to "Update",
        "Views" to "Read",
        "Favourites" to "Like",
    )

    override val tags = listOf(
        "Fantasy (판타지)" to "102",
        "Modern fantasy (현판)" to "110",
        "Romance (로맨스)" to "101",
        "Romance fantasy (로판)" to "109",
        "Martial arts (무협)" to "103",
        "Mystery (미스터리)" to "104",
        "Light novel (라이트노벨)" to "106",
    )

    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.6",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getDoc(url: String): Document {
        val response = app.get(url, headers = headers)
        val body = response.text
        SourceGuard.requireOk(name, response, body)
        if (NaverWebNovelParser.isLoginWall(response.url)) {
            throw ErrorLoadingException("$name: this work needs a logged-in (age-verified) Naver account")
        }
        return Jsoup.parse(body, response.url)
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val league = mainCategory ?: "best"
        val url = "$mainUrl/$league/genre?genre=${tag ?: "102"}&order=${orderBy ?: "Update"}&page=$page"
        return HeadMainPageResponse(url, NaverWebNovelParser.parseCards(this, getDoc(url)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val results = LinkedHashMap<String, SearchResponse>()
        for (section in listOf("best", "challenge")) {
            val doc = getDoc("$mainUrl/search?keyword=$q&section=$section&target=all")
            NaverWebNovelParser.parseCards(this, doc).forEach { results.putIfAbsent(it.url, it) }
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val ref = NaverWebNovelParser.novelRef(url) ?: throw ErrorLoadingException("Not a Naver league novel url: $url")
        val listUrl = NaverWebNovelParser.listUrl(ref)
        val first = getDoc("$listUrl&order=Oldest&page=1")
        val info = NaverWebNovelParser.parseInfo(first)
            ?: throw ErrorLoadingException("$name: could not read the novel page")

        val chapters = ArrayList<ChapterData>()
        val seen = HashSet<String>()
        val firstPage = NaverWebNovelParser.parseEpisodes(this, first)
        firstPage.filterTo(chapters) { seen.add(it.url) }
        val total = NaverWebNovelParser.totalEpisodes(first)
        val pages = NaverWebNovelParser.pageCount(total, firstPage.size)
        for (page in 2..pages) {
            delay(PAGE_DELAY_MS)
            val fresh = NaverWebNovelParser.parseEpisodes(this, getDoc("$listUrl&order=Oldest&page=$page"))
                .filter { seen.add(it.url) }
            if (fresh.isEmpty()) break
            chapters.addAll(fresh)
        }

        return newStreamResponse(info.title, listUrl, chapters, fix = false) {
            author = info.author
            // Best-league works that get picked up for paid serialization lose their free episodes.
            synopsis = if (chapters.isEmpty()) {
                listOfNotNull(NO_FREE_EPISODES, info.synopsis).joinToString("\n\n")
            } else {
                info.synopsis
            }
            tags = info.tags
            posterUrl = info.cover
            status = info.status
        }
    }

    override suspend fun loadHtml(url: String): String? = NaverWebNovelParser.parseEpisode(getDoc(url))

    companion object {
        private const val PAGE_DELAY_MS = 300L
        private const val NO_FREE_EPISODES =
            "[No free episodes left on Naver Webnovel; the work has probably moved to paid serialization.]"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [NaverWebNovelProvider]; unit-testable on the JVM. */
object NaverWebNovelParser {
    private const val BASE = "https://novel.naver.com"
    private val refRegex = Regex("""/(best|challenge)/(?:list|detail)\?(?:.*&)?novelId=(\d+)""")
    private val totalRegex = Regex("""\(([\d,]+)\)""")

    /** (league, novelId) for best/challenge league urls; null for anything else. */
    fun novelRef(url: String): Pair<String, String>? =
        refRegex.find(url)?.let { it.groupValues[1] to it.groupValues[2] }

    fun listUrl(ref: Pair<String, String>) = "$BASE/${ref.first}/list?novelId=${ref.second}"

    fun isLoginWall(url: String): Boolean = url.contains("nid.naver.com") || url.contains("/login")

    /**
     * Novel cards of search results (ul.default_list) and genre pages (ul.card_list), best and
     * challenge leagues only. Carousels and side rankings on the same pages are ignored.
     */
    fun parseCards(api: MainAPI, doc: Document): List<SearchResponse> {
        val seen = HashSet<String>()
        return doc.select(
            "ul.default_list li.item a[href*=list?novelId=], ul.card_list li.item a[href*=list?novelId=]"
        ).mapNotNull { a ->
            val ref = novelRef(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(ref.second)) return@mapNotNull null
            val title = (a.selectFirst(".title")?.text() ?: a.attr("title")).trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, listUrl(ref), fix = false) {
                // Works without a cover have src="?type=…".
                posterUrl = a.selectFirst("img")?.takeIf { !it.attr("src").startsWith("?") }
                    ?.absUrl("src")?.ifBlank { null }
                latestChapter = a.select(".count").firstOrNull { it.text().contains("회") }?.text()
                    ?.replace("총", "")?.trim()?.ifEmpty { null }
            }
        }
    }

    data class Info(
        val title: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val cover: String?,
        val status: ReleaseStatus?,
    )

    fun parseInfo(doc: Document): Info? {
        val box = doc.selectFirst("div.section_area_info") ?: return null
        val title = box.selectFirst("h2.title")?.text()?.trim()?.ifEmpty { null } ?: return null
        val items = box.select(".info_group .item")
        val genre = items.firstOrNull { it.selectFirst("a") == null }?.text()?.trim()?.ifEmpty { null }
        val tags = doc.select(".tag_collection a.tag").map { it.text().trim().removePrefix("#") }
            .filter { it.isNotEmpty() }
        return Info(
            title = title,
            author = box.selectFirst(".info_group .item a")?.text()?.trim()?.ifEmpty { null },
            synopsis = (doc.selectFirst("#summaryText") ?: box.selectFirst("p.summary"))
                ?.let { ReaderHtml.lines(it, newlinesAreBreaks = true).joinToString("\n") }?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()?.ifEmpty { null },
            tags = (listOfNotNull(genre) + tags).distinct(),
            cover = box.selectFirst(".thumbnail img")?.absUrl("src")?.ifBlank { null },
            status = if (box.select(".info_group .item, .bullet_comp").any { it.text().trim() == "완결" }) {
                ReleaseStatus.Completed
            } else {
                ReleaseStatus.Ongoing
            },
        )
    }

    fun totalEpisodes(doc: Document): Int? =
        doc.selectFirst(".past_number")?.text()?.let { totalRegex.find(it) }
            ?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    /** Number of list pages, from the total and the size of the first page (10 on the site). */
    fun pageCount(total: Int?, firstPageSize: Int): Int {
        if (total == null || firstPageSize <= 0) return 1
        return ((total + firstPageSize - 1) / firstPageSize).coerceIn(1, MAX_PAGES)
    }

    fun parseEpisodes(api: MainAPI, doc: Document): List<ChapterData> =
        doc.select("ul.list_type2 li a.list_item[href*=detail?novelId=]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            val title = a.selectFirst(".subj")?.text()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
            api.newChapterData(title, href, fix = false) {
                dateOfRelease = a.selectFirst(".date")?.text()?.trim()?.ifEmpty { null }
            }
        }

    fun parseEpisode(doc: Document): String? {
        val content = doc.selectFirst("div.detail_view_content") ?: return null
        content.select("script, style, .ad, iframe").remove()
        return ReaderHtml.fromElement(content, newlinesAreBreaks = true)
    }

    private const val MAX_PAGES = 1000
}
