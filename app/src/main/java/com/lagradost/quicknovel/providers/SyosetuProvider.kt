package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Document
import java.net.URLEncoder

class SyosetuProvider : MainAPI() {
    override val name = "Syosetu"
    override val mainUrl = "https://yomou.syosetu.com"
    override val iconId = R.drawable.icon_ncode
    override val iconBackgroundId = R.color.white
    override val lang = "ja"
    override val hasMainPage = true
    private val ncodeUrl = "https://ncode.syosetu.com"

    override val mainCategories = listOf(
        "All genres (総合)" to ALL_GENRES,
        "Isekai Romance" to "i1",
        "Isekai Fantasy" to "i2",
        "Isekai Lit/SF/Other" to "io",
        "Romance (Fantasy World)" to "101",
        "Romance (Real World)" to "102",
        "High Fantasy" to "201",
        "Low Fantasy" to "202",
        "Literary Fiction" to "301",
        "Human Drama" to "302",
        "Historical" to "303",
        "Mystery" to "304",
        "Horror" to "305",
        "Action" to "306",
        "Comedy" to "307",
        "VR Game" to "401",
        "Space" to "402",
        "Science Fiction" to "403",
        "Panic/Disaster" to "404",
        "Fairy Tale" to "9901",
        "Poetry" to "9902",
        "Essay" to "9903",
        "Other" to "9999"
    )

    // Rankings: /rank/{genrelist|isekailist}/type/{period}_{genre}{status}/?p= (50 per page,
    // 300 entries). Latest updates: /search.php?order=new (20 per page, newest update first).
    override val orderBys = listOf(
        "All-time ranking (累計)" to "total",
        "Daily ranking (日間)" to "daily",
        "Weekly ranking (週間)" to "weekly",
        "Monthly ranking (月間)" to "monthly",
        "Quarterly ranking (四半期)" to "quarter",
        "Yearly ranking (年間)" to "yearly",
        "Latest updates (最新掲載)" to LATEST,
    )

    // Serialization status: the ranking suffix (_r, _er, _t) and the search `type` value.
    override val tags = listOf(
        "All" to "",
        "Ongoing (連載中)" to "r",
        "Completed (完結済)" to "er",
        "Short stories (短編)" to "t",
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = mainPageUrl(page, mainCategory, orderBy, tag)
        val document = app.get(url).document
        val items = if (orderBy == LATEST) parseSearch(document) else parseRanking(document)
        return HeadMainPageResponse(url, items)
    }

    /**
     * A ranking page, e.g. `/rank/genrelist/type/daily_201_er/?p=2` (high fantasy, completed),
     * `/rank/isekailist/type/total_2/?p=1` (isekai fantasy), or for [LATEST] the search sorted by
     * last update, e.g. `/search.php?order=new&genre=201&type=er&p=2`.
     */
    internal fun mainPageUrl(page: Int, mainCategory: String?, orderBy: String?, tag: String?): String {
        val genre = mainCategory?.takeIf { it.isNotBlank() } ?: mainCategories.first().second
        val order = orderBy?.takeIf { it.isNotBlank() } ?: orderBys.first().second
        val status = tag?.takeIf { it.isNotBlank() }
        val p = page.coerceAtLeast(1)
        if (order == LATEST) {
            val filter = when (genre) {
                // The isekai rankings are works tagged 異世界転生/転移 (istt) in a big genre:
                // romance, fantasy, or literature/SF/other/non-genre.
                "i1" -> "istt=1&biggenre=1"
                "i2" -> "istt=1&biggenre=2"
                "io" -> "istt=1&biggenre=3-4-99-98"
                ALL_GENRES -> null
                else -> "genre=$genre"
            }
            return "$mainUrl/search.php?order=new" + (filter?.let { "&$it" } ?: "") +
                    (status?.let { "&type=$it" } ?: "") + "&p=$p"
        }
        val suffix = status?.let { "_$it" } ?: ""
        return "$mainUrl/rank/" + when {
            genre == ALL_GENRES -> "list/type/${order}_total$suffix/?p=$p"
            genre.startsWith("i") -> "isekailist/type/${order}_${genre.substring(1)}$suffix/?p=$p"
            else -> "genrelist/type/${order}_$genre$suffix/?p=$p"
        }
    }

    /** Pure: a ranking page's entries. */
    internal fun parseRanking(document: Document): List<SearchResponse> =
        document.select(".p-ranklist-item").mapNotNull { container ->
            val a = container.selectFirst(".p-ranklist-item__title a") ?: return@mapNotNull null
            newSearchResponse(a.text().trim(), a.attr("href"))
        }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url.replace(mainUrl, ncodeUrl)
        val document = app.get(fixedUrl).document
        titleOf(document) // fail before fetching the other table-of-contents pages

        // The table of contents lists 100 episodes per page (?p=2, ?p=3, ...); every page is
        // needed for the real episode titles.
        val tocPages = mutableListOf<Document?>(document)
        for (page in 2..lastPageNumber(document)) {
            delay(TOC_PAGE_DELAY_MS)
            val pageUrl = tocPageUrl(fixedUrl, page)
            tocPages += runCatching { app.get(pageUrl).document }.getOrElse {
                delay(1_000)
                runCatching { app.get(pageUrl).document }.getOrNull()
            }
        }
        // Status, genre and keywords are only on the work's info page.
        val infoPage = runCatching { app.get(infoUrl(fixedUrl)).document }.getOrNull()
        return parseLoad(document, fixedUrl, tocPages, infoPage)
    }

    private fun titleOf(document: Document): String =
        document.selectFirst(".p-novel__title, .novel_title")?.text()
            ?: throw ErrorLoadingException("Title not found")

    /** `https://ncode.syosetu.com/n9669bk/` -> `n9669bk`. */
    private fun ncodeOf(url: String): String? =
        Regex("""syosetu\.com/(n\d{4}[a-z]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)?.lowercase()

    /** The work's first table-of-contents page, with a trailing slash. */
    private fun workUrl(fixedUrl: String): String =
        ncodeOf(fixedUrl)?.let { "$ncodeUrl/$it/" }
            ?: (fixedUrl.substringBefore('?').removeSuffix("/") + "/")

    internal fun tocPageUrl(fixedUrl: String, page: Int): String = "${workUrl(fixedUrl)}?p=$page"

    internal fun infoUrl(fixedUrl: String): String {
        val ncode = ncodeOf(fixedUrl) ?: workUrl(fixedUrl).removeSuffix("/").substringAfterLast('/')
        return "$ncodeUrl/novelview/infotop/ncode/$ncode/"
    }

    /**
     * Pure half of [load]: [document] is the novel's first table-of-contents page, [tocPages]
     * all of its pages in order (page 1 first; null for a page that failed to load) and
     * [infoPage] the work's info page (`/novelview/infotop/ncode/<ncode>/`; null if it failed).
     */
    internal suspend fun parseLoad(
        document: Document,
        fixedUrl: String,
        tocPages: List<Document?> = listOf(document),
        infoPage: Document? = null,
    ): StreamResponse {
        val title = titleOf(document)

        // A one-shot (短編) has no episode list: its text is on the work page itself.
        val chapters = parseChapters(tocPages, fixedUrl).ifEmpty {
            if (document.selectFirst(".p-novel__body, #novel_honbun") != null) {
                listOf(newChapterData(title, workUrl(fixedUrl)))
            } else emptyList()
        }

        return newStreamResponse(title, fixedUrl, chapters) {

            posterUrl = ""
            this.synopsis = document.selectFirst("#novel_ex, .p-novel__summary")?.text()
            this.author = document.selectFirst(".p-novel__author a, .novel_writername a")?.text()

            // The novel page no longer says whether the work is finished; the info page does.
            this.status = infoPage?.let { parseStatus(it) }
                ?: CjkStatus.parse(document.selectFirst(".p-novel__type, #noveltype_notend")?.text())
            this.tags = infoPage?.let { parseTags(it) }.orEmpty()
                .ifEmpty { document.select(".p-novel__tag a, .novel_key a").map { it.text() } }
                .ifEmpty { null }
        }
    }

    /**
     * Status from the info page: `p-infotop-type__type--completed` (完結済),
     * `--serialized` (連載中) or `--short` (短編: a one-shot, complete by nature).
     */
    internal fun parseStatus(infoPage: Document): ReleaseStatus? {
        val type = infoPage.selectFirst(".p-infotop-type__type") ?: return null
        return if (type.hasClass("p-infotop-type__type--short") || type.text().contains("短編")) {
            ReleaseStatus.Completed
        } else {
            CjkStatus.parse(type.text())
        }
    }

    /** Genre (ジャンル) followed by the keywords (キーワード, separated by spaces). */
    internal fun parseTags(infoPage: Document): List<String> {
        fun value(label: String): String? = infoPage.select("dl.p-infotop-data dt")
            .firstOrNull { it.text().trim() == label }
            ?.nextElementSibling()
            ?.takeIf { it.normalName() == "dd" }
            ?.text()

        val genre = value("ジャンル")?.trim()?.ifEmpty { null }
        val keywords = value("キーワード").orEmpty()
            .split(Regex("""[\s 　]+"""))
            .filter { it.isNotBlank() }
        return (listOfNotNull(genre) + keywords).distinct()
    }

    /** Number of the last table-of-contents page (1 when the list fits on one page). */
    internal fun lastPageNumber(document: Document): Int =
        document.selectFirst("a.c-pager__item--last[href]")?.attr("href")
            ?.let { Regex("""[?&]p=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?.coerceAtLeast(1) ?: 1

    /** URL of the last table-of-contents page, or null when the list fits on one page. */
    internal fun lastPageUrl(document: Document): String? {
        val lastPageBtn = document.selectFirst("a.c-pager__item--last[href]") ?: return null
        val href = lastPageBtn.attr("href")
        return if (href.startsWith("http")) href else "$ncodeUrl$href"
    }

    /**
     * Episodes with their real titles from every table-of-contents page. A page that failed to
     * load but is followed by one that loaded gets numbered placeholders, so the episode numbers
     * and URLs of the later pages stay right.
     */
    private fun parseChapters(tocPages: List<Document?>, fixedUrl: String): List<ChapterData> {
        val work = workUrl(fixedUrl)
        val lastLoaded = tocPages.indexOfLast { it != null }
        val chapters = mutableListOf<ChapterData>()
        tocPages.forEachIndexed { index, page ->
            if (index > lastLoaded) return@forEachIndexed
            if (page == null) {
                for (n in index * EPISODES_PER_PAGE + 1..(index + 1) * EPISODES_PER_PAGE) {
                    chapters += newChapterData("Episode $n", "$work$n/")
                }
                return@forEachIndexed
            }
            page.select(".p-eplist__sublist a.p-eplist__subtitle, .novel_sublist2 a").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isEmpty()) return@forEach
                val chUrl = if (href.startsWith("http")) href else "$ncodeUrl$href"
                val name = a.text().trim()
                    .ifEmpty { "Episode ${href.trimEnd('/').substringAfterLast('/')}" }
                chapters += newChapterData(name, chUrl)
            }
        }
        return chapters
    }

    override suspend fun loadHtml(url: String): String? = parseChapter(app.get(url).document)

    /** Pure half of [loadHtml]. */
    internal fun parseChapter(document: Document): String? {
        val content = document.selectFirst(".p-novel__body, #novel_honbun") ?: return null
        content.select(".p-novel__parent, .p-novel__number, .c-ad, .p-novel__action, .novel_bn").remove()

        return content.html()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // URLEncoder rather than android.net.Uri so the live smoke test can run on the JVM;
        // the server decodes both forms identically.
        val url = "$mainUrl/search.php?search_type=novel&word=${URLEncoder.encode(query, "UTF-8")}&order=hyoka"
        return parseSearch(app.get(url).document)
    }

    /** Pure half of [search]. */
    internal fun parseSearch(document: Document): List<SearchResponse> {
        return document.select("div.searchkekka_box").mapNotNull { result ->
            val titleElement = result.selectFirst(".novel_h a") ?: return@mapNotNull null

            newSearchResponse(titleElement.text().trim(), titleElement.attr("href")) {
                posterUrl = ""
            }
        }
    }

    companion object {
        /** [orderBys] value for the latest-updates listing. */
        internal const val LATEST = "new"

        /** [mainCategories] value for the all-genre (総合) ranking. */
        internal const val ALL_GENRES = "total"

        private const val EPISODES_PER_PAGE = 100

        /** Pause between table-of-contents pages so long works do not hammer the site. */
        private const val TOC_PAGE_DELAY_MS = 250L
    }
}
