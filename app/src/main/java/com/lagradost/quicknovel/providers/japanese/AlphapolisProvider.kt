package com.lagradost.quicknovel.providers.japanese

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
 * アルファポリス (Alphapolis) — https://www.alphapolis.co.jp web novels. UTF-8, no login for
 * free episodes, no Cloudflare (verified 2026-10).
 *
 *   Search:  GET /search?query=<q>&category=novel&page=<n>     div.content-block cards
 *   Browse:  GET /novel/index?sort=<sort>&category_ids=<id>&page=<n>   section.c-content cards
 *   Novel:   GET /novel/{authorId}/{workId}
 *            h1.p-content-info__title, .p-content-info__author, .p-content-info__abstract,
 *            and the whole table of contents as JSON in
 *            <script type="application/json" id="app-cover-data">: chapterEpisodes[].episodes[]
 *            {episodeNo, url, mainTitle, isPublic, chapter, upTime}. Episodes of works turned
 *            into books become paid rentals (isPublic=false) and are left out.
 *   Episode: the episode page /novel/{a}/{w}/episode/{n} has an empty #novelBody; its inline
 *            script loads the text with
 *              POST /novel/episode_body  episode=<n>&token=<per-page token>
 *            which needs the page's session cookies and its Laravel CSRF token (`_token`) as
 *            X-CSRF-TOKEN. The answer is an HTML fragment of <br>-separated lines, or a
 *            reCAPTCHA form when the site thinks we are a bot (reported as an error).
 */
class AlphapolisProvider : MainAPI() {
    override val name = "Alphapolis (アルファポリス)"
    override val mainUrl = "https://www.alphapolis.co.jp"
    override val lang = "ja"
    override val hasMainPage = true
    override val rateLimitTime = 1500L

    override val orderBys = listOf(
        "24h points" to "24hpt",
        "Weekly points" to "weekly",
        "Monthly points" to "monthly",
        "Recently updated" to "episode_recent",
        "Newest" to "recent",
        "Recently completed" to "completed",
        "Most favourited" to "favorite",
    )

    override val tags = listOf(
        "All" to "",
        "Fantasy (ファンタジー)" to "110400",
        "Romance (恋愛)" to "110500",
        "Mystery (ミステリー)" to "110100",
        "Horror (ホラー)" to "110200",
        "Sci-fi (SF)" to "110300",
        "Character fiction (キャラ文芸)" to "111400",
        "Light literature (ライト文芸)" to "111500",
        "Youth (青春)" to "110600",
        "Contemporary (現代文学)" to "110700",
        "Popular fiction (大衆娯楽)" to "110800",
        "Historical (歴史・時代)" to "111100",
        "Children (児童書・童話)" to "111200",
        "BL" to "119000",
        "Essay (エッセイ・ノンフィクション)" to "111600",
    )

    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ja,en;q=0.8",
    )

    private suspend fun getText(url: String, referer: String = "$mainUrl/"): String {
        val response = app.get(url, headers = headers + ("Referer" to referer))
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
        val params = buildList {
            add("sort=${orderBy ?: "24hpt"}")
            if (!tag.isNullOrBlank()) add("category_ids=$tag")
            if (page > 1) add("page=$page")
        }.joinToString("&")
        val url = "$mainUrl/novel/index?$params"
        return HeadMainPageResponse(url, AlphapolisParser.parseList(this, getText(url)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=${URLEncoder.encode(query, "UTF-8")}&category=novel"
        return AlphapolisParser.parseList(this, getText(url))
    }

    override suspend fun load(url: String): LoadResponse {
        val ids = AlphapolisParser.workIds(url) ?: throw ErrorLoadingException("Not an alphapolis novel url: $url")
        val workUrl = "$mainUrl/novel/${ids.first}/${ids.second}"
        val work = AlphapolisParser.parseWork(this, Jsoup.parse(getText(workUrl), workUrl))
            ?: throw ErrorLoadingException("$name: could not read the table of contents")
        return newStreamResponse(work.title, workUrl, work.chapters, fix = false) {
            author = work.author
            synopsis = work.synopsis
            tags = work.tags
            status = work.status
            posterUrl = work.cover
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val page = app.get(url, headers = headers + ("Referer" to url.substringBefore("/episode/")))
        val pageHtml = page.text
        SourceGuard.requireOk(name, page, pageHtml)
        val request = AlphapolisParser.bodyRequest(pageHtml)
            ?: throw ErrorLoadingException("$name: this episode is not readable without an account (rental or removed)")

        val body = app.post(
            "$mainUrl/novel/episode_body",
            headers = headers + mapOf(
                "Referer" to url,
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "X-CSRF-TOKEN" to request.csrf,
                "Accept" to "text/html, */*; q=0.01",
            ),
            cookies = page.cookies,
            data = mapOf("episode" to request.episode, "token" to request.token),
        )
        val fragment = body.text
        SourceGuard.requireOk(name, body, fragment)
        if (AlphapolisParser.isCaptcha(fragment)) {
            throw ErrorLoadingException(SourceGuard.message(name, SourceGuard.Block.CAPTCHA))
        }
        return AlphapolisParser.parseEpisodeBody(fragment, url)
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [AlphapolisProvider]; unit-testable on the JVM. */
object AlphapolisParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val workRegex = Regex("""/novel/(\d+)/(\d+)""")
    private val tokenRegex =
        Regex("""episode_body['"]\s*,\s*\{\s*['"]episode['"]\s*:\s*['"]?(\d+)['"]?\s*,\s*['"]token['"]\s*:\s*['"]([0-9a-zA-Z]+)['"]""")

    /** (authorId, workId) from a work or episode url. */
    fun workIds(url: String): Pair<String, String>? =
        workRegex.find(url)?.let { it.groupValues[1] to it.groupValues[2] }

    fun parseList(api: MainAPI, html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, api.mainUrl)
        val seen = HashSet<String>()
        // Search uses div.content-block cards, /novel/index uses section.c-content cards.
        return doc.select("div.content-block, section.c-content").mapNotNull { card ->
            val a = card.selectFirst("h2.title a[href], .c-content__title a[href]") ?: return@mapNotNull null
            val ids = workIds(a.attr("href")) ?: return@mapNotNull null
            if (!seen.add(ids.second)) return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            api.newSearchResponse(title, "${api.mainUrl}/novel/${ids.first}/${ids.second}", fix = false) {
                posterUrl = card.selectFirst("a.cover img, .c-content__cover img")?.absUrl("src")?.ifBlank { null }
            }
        }
    }

    data class Work(
        val title: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val status: ReleaseStatus?,
        val cover: String?,
        val chapters: List<ChapterData>,
        /** Episodes that exist but need a paid rental. */
        val lockedCount: Int,
    )

    private fun JsonNode.str(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    fun coverData(doc: Document): JsonNode? =
        doc.selectFirst("script#app-cover-data")?.data()?.trim()?.takeIf { it.startsWith("{") }
            ?.let { runCatching { mapper.readTree(it) }.getOrNull() }

    fun parseWork(api: MainAPI, doc: Document): Work? {
        val data = coverData(doc) ?: return null
        val title = doc.selectFirst("h1.p-content-info__title")?.text()?.trim()?.ifEmpty { null }
            ?: data.path("content").str("title") ?: return null

        val chapters = mutableListOf<ChapterData>()
        var locked = 0
        for (section in data.path("chapterEpisodes")) {
            for (ep in section.path("episodes")) {
                val path = ep.str("url") ?: continue
                if (!ep.path("isPublic").asBoolean(false)) {
                    locked++
                    continue
                }
                val epTitle = ep.str("mainTitle") ?: "Episode ${chapters.size + 1}"
                val chapter = ep.str("chapter") ?: section.str("title")
                chapters.add(
                    api.newChapterData(
                        if (chapter != null) "$chapter - $epTitle" else epTitle,
                        if (path.startsWith("http")) path else api.mainUrl + path,
                        fix = false
                    ) {
                        dateOfRelease = ep.str("upTime")
                    }
                )
            }
        }

        val attributes = doc.select(".p-sidebar-content-info__summary .c-attribute-tag").map { it.text().trim() }
        return Work(
            title = title,
            author = doc.selectFirst(".p-content-info__author")?.text()?.trim()?.ifEmpty { null }
                ?: data.path("content").path("user").str("name"),
            synopsis = doc.selectFirst(".p-content-info__abstract")
                ?.let { ReaderHtml.lines(it, newlinesAreBreaks = true).joinToString("\n") }?.ifEmpty { null },
            tags = doc.select(".p-content-info__tags .c-tag, .p-content-info__tags a").map { it.text().trim() }
                .filter { it.isNotEmpty() }.distinct(),
            status = when {
                attributes.any { it == "完結" } -> ReleaseStatus.Completed
                attributes.any { it == "連載中" } -> ReleaseStatus.Ongoing
                else -> null
            },
            cover = data.path("content").str("coverImageUrl"),
            chapters = chapters,
            lockedCount = locked,
        )
    }

    data class BodyRequest(val episode: String, val token: String, val csrf: String)

    /** Parameters of the inline `$('#novelBody').load('…/episode_body', {...})` call. */
    fun bodyRequest(episodePageHtml: String): BodyRequest? {
        val m = tokenRegex.find(episodePageHtml) ?: return null
        val doc = Jsoup.parse(episodePageHtml)
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("input[name=_token]")?.attr("value")?.ifBlank { null }
            ?: return null
        return BodyRequest(m.groupValues[1], m.groupValues[2], csrf)
    }

    fun isCaptcha(fragment: String): Boolean = fragment.contains("g-recaptcha")

    fun parseEpisodeBody(fragment: String, url: String): String? {
        val body = Jsoup.parseBodyFragment(fragment, url).body()
        JapaneseProviderUtils.flattenRuby(body)
        return ReaderHtml.fromElement(body)
    }
}
