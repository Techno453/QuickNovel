package com.lagradost.quicknovel.providers.japanese

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
import com.lagradost.quicknovel.providers.CjkStatus
import com.lagradost.quicknovel.providers.ReaderHtml
import com.lagradost.quicknovel.providers.SourceGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * ハーメルン (Hameln) — https://syosetu.org, a Japanese user-generated novel site (mostly fan
 * fiction 二次創作 plus original works). All-ages only: the R-18 section is a separate host
 * (h.syosetu.org) that this provider never requests, and works tagged R-18 are dropped.
 *
 * Cloudflare answers every request without a `cf_clearance` cookie with a managed challenge
 * (HTTP 403, `cf-mitigated: challenge`), so the provider opts into [usesCloudFlareKiller]: the
 * interceptor clears the challenge in a hidden WebView and replays requests with the WebView's
 * user agent, which gets the smartphone layout parsed here (verified 2026-10 in a mobile
 * browser; the PC layout is not parsed).
 *
 *   Search:  /search/?mode=search&word=<q>&type=28   (総合評価 order, 10 per page)
 *   Ranking: /?mode=rank_{scope_}{period}           (one page: 300 / 100 / 50 entries)
 *   Latest:  /search/?mode=search&type=45[&gensaku=原作：…]&page=n  (最終更新, 10 per page)
 *   Novel:   /novel/{id}/                           title, author, tags, synopsis, episode list
 *   Info:    /?mode=ss_detail&nid={id}              話数 row → status, あらすじ, タグ
 *   Episode: /novel/{id}/{n}.html                   text in #honbun > p
 *   One-shots (短編 with one episode) have no list; the text is on the novel page itself.
 *
 * Politeness: browser user agent, a Referer, and at least [MIN_INTERVAL_MS] between requests.
 */
open class HamelnProvider : MainAPI() {
    override val name = "Hameln (ハーメルン)"
    override val mainUrl = "https://syosetu.org"
    override val lang = "ja"
    override val hasMainPage = true

    /** Characters (episodes x average per episode on each card); filtered in the app. */
    override val lengthFilter = LengthFilterSupport(LengthUnit.CHARACTERS)
    override val usesCloudFlareKiller = true
    override val rateLimitTime = MIN_INTERVAL_MS

    /**
     * Listings, most popular first. Value = the ranking period (`rank_{scope_}{period}`) or
     * [LATEST] for the latest-updated works (a search ordered by 最終更新).
     */
    override val mainCategories = listOf(
        "Daily ranking (日間)" to "day",
        "Weekly ranking (週間)" to "week",
        "Monthly ranking (月間)" to "month",
        "Quarterly ranking (四半期)" to "3month",
        "Yearly ranking (年間)" to "year",
        "All-time ranking (累計)" to "total",
        "New works, daily ranking (新作日間)" to "new",
        "Completed, all-time ranking (完結累計)" to "complete_total",
        "Latest updates (最終更新)" to LATEST,
    )

    /** Scope: everything, original works only, or fan fiction only (the site's own split). */
    override val tags = listOf(
        "All (総合)" to "",
        "Original works (オリジナル)" to "ori",
        "Fan fiction (二次創作)" to "2",
    )

    private val baseHeaders = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "ja,en;q=0.8",
    )

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): Pair<String, String> {
        throttle()
        val response = app.get(url, headers = baseHeaders + ("Referer" to referer))
        val body = response.text
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
        if (HamelnParser.isRanking(url)) {
            // A ranking is one long page; it is cached and served PAGE_SIZE entries at a time.
            val all = rankingCache[url]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second
                ?: HamelnParser.parseList(this, fetch(url).first).also {
                    rankingCache[url] = System.currentTimeMillis() to it
                }
            return HeadMainPageResponse(url, HamelnParser.page(all, page, PAGE_SIZE))
        }
        val (html, _) = fetch(url)
        return HeadMainPageResponse(url, HamelnParser.parseList(this, html))
    }

    /** URL of a [mainCategories] listing (null = the first one) for a [tags] scope and [page]. */
    internal fun listingUrl(listing: String?, scope: String?, page: Int): String {
        val value = listing?.takeIf { it.isNotBlank() } ?: mainCategories.first().second
        val gensaku = when (scope) {
            "ori" -> "原作：オリジナル"
            "2" -> "原作：二次創作"
            else -> null
        }
        if (value == LATEST) {
            return "$mainUrl/search/?mode=search&type=45" +
                    (gensaku?.let { "&gensaku=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
                    if (page > 1) "&page=$page" else ""
        }
        val prefix = scope?.takeIf { it == "ori" || it == "2" }?.let { "${it}_" } ?: ""
        return "$mainUrl/?mode=rank_$prefix$value"
    }

    internal fun searchUrl(query: String, page: Int = 1): String =
        "$mainUrl/search/?mode=search&word=" + URLEncoder.encode(query, "UTF-8") + "&type=28" +
                if (page > 1) "&page=$page" else ""

    override suspend fun search(query: String): List<SearchResponse> =
        HamelnParser.parseList(this, fetch(searchUrl(query)).first)

    override suspend fun load(url: String): LoadResponse {
        val id = HamelnParser.novelId(url) ?: throw ErrorLoadingException("Not a Hameln novel url: $url")
        val novelUrl = "$mainUrl/novel/$id/"
        val (html, finalUrl) = fetch(novelUrl)
        if (!finalUrl.startsWith(mainUrl)) throw ErrorLoadingException(ADULT_MESSAGE)
        val toc = HamelnParser.parseToc(this, html, novelUrl)
            ?: throw ErrorLoadingException("$name: could not read the novel page")
        // Status, rating and views are only on the info page; the work still loads without it.
        val detail = runCatching {
            HamelnParser.parseDetail(fetch(HamelnParser.detailUrl(mainUrl, id), novelUrl).first)
        }.getOrNull()
        val tags = (toc.tags + detail?.tags.orEmpty()).distinct()
        if (HamelnParser.isAdult(tags)) throw ErrorLoadingException(ADULT_MESSAGE)

        return newStreamResponse(toc.title, novelUrl, toc.chapters, fix = false) {
            author = toc.author
            synopsis = detail?.synopsis ?: toc.synopsis
            this.tags = tags
            status = detail?.status ?: if (toc.oneShot) ReleaseStatus.Completed else null
            rating = detail?.rating
            views = detail?.views
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val referer = HamelnParser.novelId(url)?.let { "$mainUrl/novel/$it/" } ?: "$mainUrl/"
        return HamelnParser.parseChapter(fetch(url, referer).first, url)
    }

    companion object {
        /** [mainCategories] value of the latest-updates listing. */
        const val LATEST = "latest"
        const val MIN_INTERVAL_MS = 1000L
        private const val PAGE_SIZE = 50
        private const val CACHE_MS = 10 * 60 * 1000L

        // The interceptor replays requests with the WebView's own user agent once a challenge
        // is cleared; this one only matters for the first request.
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        private const val ADULT_MESSAGE = "Hameln: this work is in the R-18 section, which is not supported."

        private val rankingCache = ConcurrentHashMap<String, Pair<Long, List<SearchResponse>>>()
        private val gate = Mutex()
        private var lastRequestAt = 0L

        /** Keeps at least [MIN_INTERVAL_MS] between any two requests to the site. */
        private suspend fun throttle() = gate.withLock {
            val wait = lastRequestAt + MIN_INTERVAL_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }
}

/** Pure parsing for [HamelnProvider] (smartphone layout); unit-testable on the JVM. */
object HamelnParser {
    private val novelIdRegex = Regex("""syosetu\.org/novel/(\d+)""")
    private val countRegex = Regex("""^(連載|完結|短編)：\s*([\d,]+)話""")
    private val ratingRegex = Regex("""(\d+\.\d+)""")
    private val adultTag = Regex("""(?i)\bR-?18""")

    fun novelId(url: String): String? = novelIdRegex.find(url)?.groupValues?.get(1)

    fun detailUrl(mainUrl: String, id: String) = "$mainUrl/?mode=ss_detail&nid=$id"

    fun isRanking(url: String) = url.contains("?mode=rank_")

    fun isAdult(tags: List<String>) = tags.any { adultTag.containsMatchIn(it) }

    fun <T> page(all: List<T>, page: Int, size: Int): List<T> =
        all.drop((page.coerceAtLeast(1) - 1) * size).take(size)

    private val averageRegex = Regex("""平均：\s*([\d,]+)字""")

    /**
     * "連載：37話　平均：1,942字": 37 episodes and 37 x 1,942 characters (the card shows the
     * average per episode; the product is within a few characters of the total).
     */
    fun lengthOf(count: MatchResult?, lines: List<String>): NovelLength? {
        val episodes = count?.groupValues?.get(2)?.replace(",", "")?.toLongOrNull() ?: return null
        val average = lines.firstNotNullOfOrNull { averageRegex.find(it) }
            ?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        return NovelLength.of(chapters = episodes, characters = average?.let { it * episodes })
    }

    /** Cards of a ranking, search or latest-updates page (`div.search_box`), R-18 works removed. */
    fun parseList(api: MainAPI, html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, "${api.mainUrl}/")
        return doc.select("div.search_box").mapNotNull { box ->
            val link = box.selectFirst("h3 a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href")
            // Only all-ages novels on the main host (h.syosetu.org is the R-18 section).
            if (!url.startsWith("${api.mainUrl}/novel/") || novelId(url) == null) return@mapNotNull null
            val title = link.clone().apply { select("span.search_novel_order").remove() }.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            val lines = box.select("div.trigger > p").map { it.text().trim() }
            val tagLine = lines.firstOrNull { it.startsWith("タグ：") }.orEmpty()
            if (isAdult(tagLine.removePrefix("タグ：").split(' ', '　'))) return@mapNotNull null
            val count = lines.firstNotNullOfOrNull { countRegex.find(it) }
            api.newSearchResponse(title, "${api.mainUrl}/novel/${novelId(url)}/", fix = false) {
                length = lengthOf(count, lines)
                latestChapter = count?.let {
                    val n = it.groupValues[2].replace(",", "")
                    if (it.groupValues[1] == "短編" && n == "1") "One-shot" else "Episode $n"
                }
                rating = box.selectFirst("img.bar_base2[alt]")?.attr("alt")?.toRatingOrNull()
            }
        }
    }

    data class Toc(
        val title: String,
        val author: String?,
        val synopsis: String?,
        val tags: List<String>,
        val chapters: List<ChapterData>,
        val oneShot: Boolean,
    )

    /** The novel page `/novel/{id}/`: title, author, tags, synopsis and the episode list. */
    fun parseToc(api: MainAPI, html: String, novelUrl: String): Toc? {
        val doc = Jsoup.parse(html, novelUrl)
        val title = doc.selectFirst("h1#pagetitle")?.text()?.trim()?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBeforeLast(" - ハーメルン")
            ?: return null
        val author = doc.selectFirst("[itemprop=author]")?.text()?.trim()?.ifEmpty { null }
            // One-shot pages: "作：<a href=/user/..>name</a><br>" inside the intro.
            ?: doc.selectFirst("p.intro")?.html()?.substringAfter("作：", "")?.substringBefore("<br")
                ?.let { Jsoup.parse(it).text().trim() }?.ifEmpty { null }
        val infoBox = doc.select("div#entry_box").firstOrNull { it.selectFirst("#honbun") == null }
        val tags = infoBox?.let { box ->
            box.select("span[itemprop=genre] a, a.alert_color, span[itemprop=keywords] a").map { it.text().trim() }
        }.orEmpty().filter { it.isNotEmpty() }.distinct()
        val synopsis = infoBox?.let { synopsisOf(it) }

        val list = doc.select("ul.entry").firstOrNull { it.selectFirst("li.episode-list__item") != null }
        val chapters = ArrayList<ChapterData>()
        var section: String? = null
        list?.children()?.forEach { li ->
            val link = li.selectFirst("a[href]")
            if (link == null || !li.hasClass("episode-list__item")) {
                li.selectFirst("strong")?.text()?.trim()?.ifEmpty { null }?.let { section = it }
                return@forEach
            }
            val name = (link.selectFirst("span[id^=sio]") ?: link).ownTextOrText()
            val date = link.selectFirst("time[datetime]")?.attr("datetime")?.substringBefore('T')
                ?: link.selectFirst("span.date")?.text()?.substringBefore(' ')?.replace('/', '-')
            chapters.add(api.newChapterData(section?.let { "$it - $name" } ?: name, link.absUrl("href"), fix = false) {
                dateOfRelease = date
            })
        }
        val oneShot = chapters.isEmpty() && doc.selectFirst("#honbun") != null
        if (oneShot) chapters.add(api.newChapterData(title, novelUrl, fix = false))
        return Toc(title, author, synopsis, tags, chapters, oneShot)
    }

    data class Detail(
        val title: String?,
        val synopsis: String?,
        val tags: List<String>,
        val status: ReleaseStatus?,
        val rating: Int?,
        val views: Int?,
    )

    /** The info page `?mode=ss_detail&nid=`: a label/value table. */
    fun parseDetail(html: String): Detail? {
        val doc = Jsoup.parse(html, "https://syosetu.org/")
        val rows = doc.select("table.table1 > tbody > tr, table.table1 > tr")
        if (rows.isEmpty()) return null
        fun value(label: String): Element? {
            val i = rows.indexOfFirst { it.selectFirst("td.label")?.text()?.trim() == label }
            if (i < 0) return null
            val labelCell = rows[i].selectFirst("td.label")!!
            // "あらすじ" / "タグ" span the whole row; their value is the next row.
            return if (labelCell.attr("colspan") == "2") rows.getOrNull(i + 1)?.selectFirst("td")
            else rows[i].select("td").getOrNull(1)
        }

        val episodes = value("話数")?.text()?.trim()
        val status = when {
            episodes == null -> null
            episodes.startsWith("短編") -> ReleaseStatus.Completed
            else -> CjkStatus.parse(episodes)
        }
        val tags = buildList {
            value("原作")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
            value("タグ")?.select("a")?.forEach { add(it.text().trim()) }
        }.filter { it.isNotEmpty() }.distinct()
        return Detail(
            title = value("作品名")?.text()?.trim()?.ifEmpty { null },
            synopsis = value("あらすじ")?.let { multiline(it) },
            tags = tags,
            status = status,
            rating = value("評価(黒→赤)")?.selectFirst("img[alt]")?.attr("alt")?.toRatingOrNull(),
            views = value("UA")?.text()?.replace(",", "")?.trim()?.toLongOrNull()
                ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
        )
    }

    /** Episode text (`#honbun`, one `<p>` per line) as reader HTML; ruby flattened like Kakuyomu. */
    fun parseChapter(html: String, url: String): String? {
        val doc = Jsoup.parse(html, url)
        val body = doc.selectFirst("#honbun") ?: return null
        JapaneseProviderUtils.flattenRuby(body)
        return ReaderHtml.fromElement(body)
    }

    /** The synopsis in the novel page's info box: everything after the first `<br><br>`. */
    private fun synopsisOf(box: Element): String? {
        val html = box.html()
        val start = Regex("""<br\s*/?>\s*<br\s*/?>""").find(html) ?: return null
        val fragment = Jsoup.parseBodyFragment(html.substring(start.range.last + 1)).body()
        return multiline(fragment)
    }

    private fun multiline(element: Element): String? {
        val copy = element.clone()
        copy.select("br").forEach { it.after("\n") }
        return copy.wholeText().lines().map { ReaderHtml.trimLine(it) }.filter { it.isNotEmpty() }
            .joinToString("\n").ifEmpty { null }
    }

    private fun Element.ownTextOrText(): String =
        ownText().trim().ifEmpty { text().trim() }

    /** "8.91" (0–10) → 891, the app's 0–1000 scale; "-.--" (no votes) → null. */
    private fun String.toRatingOrNull(): Int? =
        ratingRegex.find(this)?.value?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 100).toInt() }
}
