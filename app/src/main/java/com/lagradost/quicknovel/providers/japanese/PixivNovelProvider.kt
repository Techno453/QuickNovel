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
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * pixiv 小説 (Pixiv Novels) — https://www.pixiv.net/novel. All-ages works are readable without
 * an account through the web app's JSON endpoints (verified 2026-10). Mostly Japanese, so the
 * search is restricted to `work_lang=ja`; R-18 and "login only" works are left out.
 *
 *   Search:  GET /ajax/search/novels/<q>?word=<q>&s_mode=s_tc&mode=safe&work_lang=ja&p=<n>
 *            (title/caption; logged out it answers 例外エラー if gs=1 is added) → plain novel rows
 *            with seriesId/seriesTitle. Tag search (s_mode=s_tag / s_tag_full, used for browse)
 *            accepts gs=1 and returns series cards ({id = series id}) and one-shot cards
 *            ({id = work id, novelId = the novel's id}).
 *   Series:  GET /ajax/novel/series/{id}  → title, userName, caption, tags, cover, isConcluded
 *            GET /ajax/novel/series_content/{id}?limit=30&last_order=<n>&order_by=asc
 *            → body.page.seriesContents[] in reading order, 30 per page.
 *   Novel:   GET /ajax/novel/{id}  → body.content: plain text with pixiv markup
 *            ([newpage], [chapter:…], [[rb:漢字 > かんじ]], [[jumpuri:… > url]], [pixivimage:…]).
 *
 * Every request needs `Referer: https://www.pixiv.net/`, and so do cover images on i.pximg.net.
 */
class PixivNovelProvider : MainAPI() {
    override val name = "Pixiv Novels (pixiv小説)"
    override val mainUrl = "https://www.pixiv.net"
    override val lang = "ja"
    override val hasMainPage = true
    override val rateLimitTime = 1000L

    override val orderBys = listOf(
        "Newest" to "date_d",
        "Oldest" to "date",
    )

    override val tags = listOf(
        "Original (オリジナル)" to "オリジナル",
        "Isekai (異世界)" to "異世界",
        "Isekai reincarnation (異世界転生)" to "異世界転生",
        "Fantasy (ファンタジー)" to "ファンタジー",
        "Romance (恋愛)" to "恋愛",
        "Villainess (悪役令嬢)" to "悪役令嬢",
        "Sci-fi (SF)" to "SF",
        "Mystery (ミステリー)" to "ミステリー",
        "Horror (ホラー)" to "ホラー",
        "Boys' love (BL)" to "BL",
        "Girls' love (百合)" to "百合",
    )

    private val headers = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "application/json",
        "Accept-Language" to "ja,en;q=0.8",
        "Referer" to "$mainUrl/",
    )

    private suspend fun getJson(url: String): JsonNode {
        val response = app.get(url, headers = headers)
        val text = response.text
        SourceGuard.check(name, response, text)
        return PixivNovelParser.body(text, name)
    }

    /** [grouped] (`gs=1`, series folded server-side) only works with the tag modes. */
    private fun searchUrl(word: String, mode: String, order: String, page: Int, grouped: Boolean): String {
        val w = URLEncoder.encode(word, "UTF-8").replace("+", "%20")
        return "$mainUrl/ajax/search/novels/$w?word=$w&order=$order&mode=safe&p=$page&s_mode=$mode" +
                (if (grouped) "&gs=1" else "") + "&work_lang=ja&lang=ja"
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = searchUrl(tag ?: "オリジナル", "s_tag_full", orderBy ?: "date_d", page, grouped = true)
        return HeadMainPageResponse(url, PixivNovelParser.parseSearch(this, getJson(url)))
    }

    /** Title/caption search first, then tag search for keywords that only appear as tags. */
    override suspend fun search(query: String): List<SearchResponse> {
        val byTitle = PixivNovelParser.parseSearch(this, getJson(searchUrl(query, "s_tc", "date_d", 1, grouped = false)))
        if (byTitle.size >= MIN_TITLE_RESULTS) return byTitle
        val byTag = PixivNovelParser.parseSearch(this, getJson(searchUrl(query, "s_tag", "date_d", 1, grouped = true)))
        return (byTitle + byTag).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        PixivNovelParser.seriesId(url)?.let { return loadSeries(it) }
        val novelId = PixivNovelParser.novelId(url) ?: throw ErrorLoadingException("Not a pixiv novel url: $url")
        val novel = getJson("$mainUrl/ajax/novel/$novelId")
        // An episode opened directly: show its whole series instead.
        novel.path("seriesNavData").path("seriesId").takeIf { it.isNumber || it.isTextual }
            ?.asText()?.takeIf { it.isNotBlank() && it != "0" }
            ?.let { return loadSeries(it) }

        val info = PixivNovelParser.parseOneShot(this, novel)
            ?: throw ErrorLoadingException("$name: could not read this work")
        return newStreamResponse(info.title, info.url, info.chapters, fix = false) {
            author = info.author
            synopsis = info.synopsis
            tags = info.tags
            posterUrl = info.cover
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            status = info.status
        }
    }

    private suspend fun loadSeries(seriesId: String): LoadResponse {
        val series = getJson("$mainUrl/ajax/novel/series/$seriesId")
        val chapters = mutableListOf<ChapterData>()
        val seen = HashSet<String>()
        var lastOrder = 0
        for (page in 0 until MAX_SERIES_PAGES) {
            if (page > 0) delay(SERIES_PAGE_DELAY_MS)
            val content = getJson(
                "$mainUrl/ajax/novel/series_content/$seriesId?limit=$SERIES_PAGE_SIZE" +
                        "&last_order=$lastOrder&order_by=asc"
            )
            val pageItems = PixivNovelParser.parseSeriesContent(this, content)
            chapters.addAll(pageItems.chapters.filter { seen.add(it.url) })
            if (pageItems.count < SERIES_PAGE_SIZE) break
            lastOrder += pageItems.count
        }
        val info = PixivNovelParser.parseSeries(this, series, seriesId, chapters)
            ?: throw ErrorLoadingException("$name: could not read this series")
        return newStreamResponse(info.title, info.url, info.chapters, fix = false) {
            author = info.author
            synopsis = info.synopsis
            tags = info.tags
            posterUrl = info.cover
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            status = info.status
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val novelId = PixivNovelParser.novelId(url) ?: return null
        return PixivNovelParser.contentToHtml(getJson("$mainUrl/ajax/novel/$novelId"), name)
    }

    companion object {
        private const val MIN_TITLE_RESULTS = 5
        private const val SERIES_PAGE_SIZE = 30
        private const val MAX_SERIES_PAGES = 200 // 6000 episodes
        private const val SERIES_PAGE_DELAY_MS = 300L
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

/** Pure parsing for [PixivNovelProvider]; unit-testable on the JVM. */
object PixivNovelParser {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val seriesRegex = Regex("""/novel/series/(\d+)""")
    private val novelRegex = Regex("""novel/show\.php\?(?:.*&)?id=(\d+)""")

    fun seriesId(url: String): String? = seriesRegex.find(url)?.groupValues?.get(1)
    fun novelId(url: String): String? = novelRegex.find(url)?.groupValues?.get(1)

    fun seriesUrl(id: String) = "https://www.pixiv.net/novel/series/$id"
    fun novelUrl(id: String) = "https://www.pixiv.net/novel/show.php?id=$id"

    private fun JsonNode.str(field: String): String? =
        get(field)?.takeIf { !it.isNull }?.asText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Unwraps `{"error":false,"body":…}`; throws the site's message on `error:true`. */
    fun body(json: String, site: String = "Pixiv"): JsonNode {
        val root = runCatching { mapper.readTree(json) }.getOrNull()
            ?: throw ErrorLoadingException("$site returned an unexpected (non-JSON) response")
        if (root.path("error").asBoolean(false)) {
            val message = root.str("message") ?: "unknown error"
            throw ErrorLoadingException("$site: $message")
        }
        return root.path("body")
    }

    private fun cover(node: JsonNode?): String? = node?.path("urls")?.let { urls ->
        urls.str("480mw") ?: urls.str("240mw") ?: urls.str("original")
    }

    /**
     * Handles both result shapes: grouped (`gs=1`, tag search) cards — series cards carry
     * `isOneshot=false` and the series id, one-shot cards carry `novelId` — and plain novel rows
     * (title/caption search, which rejects `gs=1`), where episodes of a series are folded into
     * one series entry via `seriesId` / `seriesTitle`.
     */
    fun parseSearch(api: MainAPI, body: JsonNode): List<SearchResponse> {
        val seen = HashSet<String>()
        return body.path("novel").path("data").mapNotNull { item ->
            if (item.path("xRestrict").asInt(0) > 0) return@mapNotNull null
            val seriesId = item.str("seriesId")?.takeIf { it != "0" }
            val (title, url) = when {
                item.has("novelId") -> item.str("title") to novelUrl(item.str("novelId") ?: return@mapNotNull null)
                item.has("isOneshot") -> item.str("title") to seriesUrl(item.str("id") ?: return@mapNotNull null)
                seriesId != null -> (item.str("seriesTitle") ?: item.str("title")) to seriesUrl(seriesId)
                else -> item.str("title") to novelUrl(item.str("id") ?: return@mapNotNull null)
            }
            if (title == null || !seen.add(url)) return@mapNotNull null
            api.newSearchResponse(title, url, fix = false) {
                posterUrl = cover(item.get("cover")) ?: item.str("url")
                posterHeaders = mapOf("Referer" to "https://www.pixiv.net/")
                latestChapter = item.get("publishedEpisodeCount")?.takeIf { it.isNumber }
                    ?.let { "${it.asInt()} episodes" }
            }
        }
    }

    data class Info(
        val title: String,
        val url: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val cover: String?,
        val status: ReleaseStatus?,
        val chapters: List<ChapterData>,
    )

    data class SeriesPage(val chapters: List<ChapterData>, val count: Int)

    /** yyyy-MM-dd in Japan time (java.time needs API 26; minSdk is 23). */
    fun formatDate(epochSeconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }.format(Date(epochSeconds * 1000))

    fun parseSeriesContent(api: MainAPI, body: JsonNode): SeriesPage {
        val items = body.path("page").path("seriesContents")
        val chapters = items.mapNotNull { ep ->
            if (ep.path("xRestrict").asInt(0) > 0) return@mapNotNull null
            val id = ep.str("id") ?: return@mapNotNull null
            val order = ep.path("series").path("contentOrder").asInt(0)
            val title = ep.str("title") ?: "#$order"
            api.newChapterData(title, novelUrl(id), fix = false) {
                dateOfRelease = ep.get("uploadTimestamp")?.takeIf { it.isNumber }?.asLong()?.let { formatDate(it) }
            }
        }
        return SeriesPage(chapters, items.size())
    }

    private fun captionText(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return ReaderHtml.lines(Jsoup.parseBodyFragment(html).body(), newlinesAreBreaks = true)
            .joinToString("\n").ifBlank { null }
    }

    fun parseSeries(api: MainAPI, body: JsonNode, seriesId: String, chapters: List<ChapterData>): Info? {
        val title = body.str("title") ?: return null
        return Info(
            title = title,
            url = seriesUrl(seriesId),
            author = body.str("userName"),
            synopsis = captionText(body.str("caption")),
            tags = body.path("tags").mapNotNull { it.asText()?.trim()?.ifEmpty { null } },
            cover = cover(body.path("cover").takeIf { !it.isMissingNode }),
            status = if (body.path("isConcluded").asBoolean(false)) ReleaseStatus.Completed else ReleaseStatus.Ongoing,
            chapters = chapters,
        )
    }

    fun parseOneShot(api: MainAPI, body: JsonNode): Info? {
        val id = body.str("id") ?: return null
        val title = body.str("title") ?: return null
        val url = novelUrl(id)
        return Info(
            title = title,
            url = url,
            author = body.str("userName"),
            synopsis = captionText(body.str("description")),
            tags = body.path("tags").path("tags").mapNotNull { it.str("tag") },
            cover = body.str("coverUrl"),
            status = ReleaseStatus.Completed,
            chapters = listOf(api.newChapterData(title, url, fix = false) {
                dateOfRelease = body.str("uploadDate")?.substringBefore('T')
            }),
        )
    }

    private val rubyRegex = Regex("""\[\[rb:\s*(.+?)\s*>\s*(.+?)\s*]]""")
    private val jumpUriRegex = Regex("""\[\[jumpuri:\s*(.+?)\s*>\s*(.+?)\s*]]""")
    private val chapterRegex = Regex("""\[chapter:\s*(.*?)\s*]""")
    private val dropTagRegex = Regex("""\[(?:newpage|jump:\d+|pixivimage:[\d-]+|uploadedimage:\d+)]""")

    /**
     * Converts pixiv novel markup to reader HTML. Ruby is rendered as real `<ruby>` and then
     * flattened with [JapaneseProviderUtils.flattenRuby] (kana furigana dropped, other readings
     * kept as `base《reading》`), like Kakuyomu. Illustrations are dropped: i.pximg.net refuses
     * image requests without a pixiv Referer, which the reader does not send.
     */
    fun markupToHtml(content: String): String? {
        val html = content.replace("\r\n", "\n").lines().joinToString("") { raw ->
            var line = chapterRegex.replace(raw) { it.groupValues[1] }
            line = jumpUriRegex.replace(line) { it.groupValues[1] }
            line = dropTagRegex.replace(line, "")
            // Mark ruby with private-use characters, escape the text, then turn the marks into tags.
            line = rubyRegex.replace(line) { "${it.groupValues[1]}${it.groupValues[2]}" }
            line = Entities.escape(line)
                .replace("", "<ruby>").replace("", "<rt>").replace("", "</rt></ruby>")
            "<p>$line</p>"
        }
        val body = Jsoup.parseBodyFragment(html).body()
        JapaneseProviderUtils.flattenRuby(body)
        return ReaderHtml.fromElement(body)
    }

    fun contentToHtml(body: JsonNode, site: String = "Pixiv"): String? {
        val content = body.str("content")
        if (content == null) {
            if (body.path("isLoginOnly").asBoolean(false)) {
                throw ErrorLoadingException("$site: this work is visible to logged-in users only")
            }
            return null
        }
        return markupToHtml(content)
    }
}
