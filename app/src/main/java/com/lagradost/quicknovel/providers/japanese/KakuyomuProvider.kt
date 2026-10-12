package com.lagradost.quicknovel.providers.japanese

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LengthFilterSupport
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.NovelLength
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * カクヨム (Kakuyomu) — https://kakuyomu.jp
 *
 * Verified against the live site (2026-10). Pages are Next.js; all data we need is in the
 * Apollo cache inside `<script id="__NEXT_DATA__">` → props.pageProps.__APOLLO_STATE__:
 *   Search:  /search?q=<q>&order=<order>&genre_name=<genre>&page=<n>   (20 works per page)
 *            ROOT_QUERY["searchWorks({...})"].nodes → Work:<id>
 *   Work:    /works/{id}
 *            Work.tableOfContentsV2 → TableOfContentsChapter.{chapter → Chapter.title,
 *            episodeUnions → Episode.{id,title,publishedAt}}. All episodes are embedded
 *            (checked with a 1361-episode work), so no pagination is needed.
 *   Episode: /works/{id}/episodes/{episodeId}  body in .widget-episodeBody > p
 */
class KakuyomuProvider : MainAPI() {
    override val name = "Kakuyomu (カクヨム)"
    override val mainUrl = "https://kakuyomu.jp"
    override val lang = "ja"
    override val hasMainPage = true
    override val rateLimitTime = 500L

    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept-Language" to "ja,en;q=0.8",
    )

    /**
     * Listings, most popular first. Value = "<search order>" or "<search order>|completed"
     * (`serial_status=completed`, checked live 2026-10). Genres ([tags]) apply to every listing.
     */
    override val mainCategories = listOf(
        "Weekly ranking" to "weekly_ranking",
        "All-time popular" to "popular",
        "Completed: weekly ranking" to "weekly_ranking|completed",
        "Completed: all-time popular" to "popular|completed",
        "Latest updates" to "last_episode_published_at",
        "New works" to "published_at",
    )

    /** Genres. Romance (恋愛) and rom-com (ラブコメ) are left out on purpose. */
    override val tags = listOf(
        "All" to "",
        "Fantasy (異世界ファンタジー)" to "fantasy",
        "Modern fantasy (現代ファンタジー)" to "action",
        "Sci-fi (SF)" to "sf",
        "Modern drama (現代ドラマ)" to "drama",
        "Horror (ホラー)" to "horror",
        "Mystery (ミステリー)" to "mystery",
        "Essay / nonfiction (エッセイ・ノンフィクション)" to "nonfiction",
        "Historical (歴史・時代・伝奇)" to "history",
        "Criticism (創作論・評論)" to "criticism",
        "Poetry / other (詩・童話・その他)" to "others",
        "Fan fiction (二次創作)" to "fan_fiction",
    )

    private suspend fun getText(url: String): String = app.get(url, headers = headers).text

    internal fun searchUrl(
        query: String?,
        order: String?,
        genre: String?,
        page: Int,
        completedOnly: Boolean = false,
    ): String {
        val params = buildList {
            if (!query.isNullOrBlank()) add("q=" + URLEncoder.encode(query, "UTF-8"))
            if (!genre.isNullOrBlank()) add("genre_name=$genre")
            if (!order.isNullOrBlank()) add("order=$order")
            if (completedOnly) add("serial_status=completed")
            if (page > 1) add("page=$page")
        }
        return "$mainUrl/search" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    /** Characters (totalCharacterCount); the search has no length parameter, so the app filters. */
    override val lengthFilter = LengthFilterSupport(LengthUnit.CHARACTERS)

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = listingUrl(mainCategory, tag, page)
        return HeadMainPageResponse(url, KakuyomuParser.parseSearch(this, getText(url)))
    }

    /** URL of a [mainCategories] listing ("order" or "order|completed"); null = the first one. */
    internal fun listingUrl(listing: String?, genre: String?, page: Int): String {
        val parts = (listing ?: mainCategories.first().second).split('|')
        return searchUrl(null, parts[0].ifBlank { "weekly_ranking" }, genre, page, completedOnly = "completed" in parts)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = searchUrl(query, "popular", null, 1)
        return KakuyomuParser.parseSearch(this, getText(url))
    }

    override suspend fun load(url: String): LoadResponse {
        val workId = KakuyomuParser.workIdFromUrl(url)
            ?: throw ErrorLoadingException("Not a kakuyomu work url: $url")
        val workUrl = "$mainUrl/works/$workId"
        val html = getText(workUrl)
        val work = KakuyomuParser.parseWork(this, html, workId)
            ?: throw ErrorLoadingException("Could not read work data")

        return newStreamResponse(work.title, workUrl, work.chapters, fix = false) {
            author = work.author
            synopsis = work.synopsis
            tags = work.tags
            status = work.status
            posterUrl = work.posterUrl
            views = work.views
        }
    }

    override suspend fun loadHtml(url: String): String? =
        KakuyomuParser.parseEpisode(getText(url), url)

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [KakuyomuProvider]; unit-testable on the JVM. */
object KakuyomuParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val workIdRegex = Regex("""/works/(\d+)""")

    fun workIdFromUrl(url: String): String? = workIdRegex.find(url)?.groupValues?.get(1)

    data class Work(
        val title: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val status: ReleaseStatus?,
        val posterUrl: String?,
        val views: Int?,
        val chapters: List<ChapterData>,
    )

    /** Returns `props.pageProps.__APOLLO_STATE__` from a Next.js page, or null. */
    fun apolloState(html: String): JsonNode? {
        val json = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return mapper.readTree(json)?.path("props")?.path("pageProps")?.path("__APOLLO_STATE__")
            ?.takeIf { it.isObject }
    }

    private fun JsonNode.deref(state: JsonNode): JsonNode? {
        val ref = get("__ref")?.asText() ?: return this
        return state.get(ref)
    }

    private fun JsonNode.str(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    private fun authorName(work: JsonNode, state: JsonNode): String? =
        work.str("alternateAuthorName")
            ?: work.get("author")?.deref(state)?.let { it.str("activityName") ?: it.str("name") }

    private fun status(work: JsonNode): ReleaseStatus? = when (work.str("serialStatus")) {
        "RUNNING" -> ReleaseStatus.Ongoing
        "COMPLETED" -> ReleaseStatus.Completed
        else -> null
    }

    fun parseSearch(api: MainAPI, html: String): List<SearchResponse> {
        val state = apolloState(html) ?: return emptyList()
        val root = state.get("ROOT_QUERY") ?: return emptyList()
        val connection = root.fieldNames().asSequence()
            .firstOrNull { it.startsWith("searchWorks(") }
            ?.let { root.get(it) } ?: return emptyList()
        return connection.path("nodes").mapNotNull { ref ->
            val work = ref.deref(state) ?: return@mapNotNull null
            val id = work.str("id") ?: return@mapNotNull null
            val title = work.str("title") ?: return@mapNotNull null
            api.newSearchResponse(title, "${api.mainUrl}/works/$id", fix = false) {
                val episodes = work.get("publicEpisodeCount")?.takeIf { it.isNumber }?.asLong()
                latestChapter = episodes?.let { "Episode $it" }
                length = NovelLength.of(
                    chapters = episodes,
                    characters = work.get("totalCharacterCount")?.takeIf { it.isNumber }?.asLong(),
                )
            }
        }
    }

    fun parseWork(api: MainAPI, html: String, workId: String): Work? {
        val state = apolloState(html) ?: return null
        val work = state.get("Work:$workId") ?: return null
        val title = work.str("title") ?: return null

        val chapters = mutableListOf<ChapterData>()
        for (tocRef in work.path("tableOfContentsV2")) {
            val toc = tocRef.deref(state) ?: continue
            val section = toc.get("chapter")?.takeIf { !it.isNull }?.deref(state)?.str("title")
            for (epRef in toc.path("episodeUnions")) {
                val ep = epRef.deref(state) ?: continue
                val epId = ep.str("id") ?: continue
                val epTitle = ep.str("title") ?: continue
                chapters.add(
                    api.newChapterData(
                        if (section != null) "$section - $epTitle" else epTitle,
                        "${api.mainUrl}/works/$workId/episodes/$epId",
                        fix = false
                    ) {
                        dateOfRelease = ep.str("publishedAt")?.substringBefore('T')
                    }
                )
            }
        }

        val catchphrase = work.str("catchphrase")
        val introduction = work.str("introduction")
        return Work(
            title = title,
            author = authorName(work, state),
            synopsis = listOfNotNull(catchphrase, introduction).joinToString("\n\n").ifBlank { null },
            tags = work.path("tagLabels").mapNotNull { it.asText()?.takeIf { t -> t.isNotBlank() } },
            status = status(work),
            posterUrl = work.str("adminCoverImageUrl") ?: work.str("ogImageUrl"),
            views = work.get("totalReadCount")?.asLong()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            chapters = chapters,
        )
    }

    fun parseEpisode(html: String, url: String): String? {
        val doc = Jsoup.parse(html, url)
        val body = doc.selectFirst(".widget-episodeBody") ?: return null
        return JapaneseProviderUtils.paragraphsToHtml(body).ifBlank { null }
    }
}
