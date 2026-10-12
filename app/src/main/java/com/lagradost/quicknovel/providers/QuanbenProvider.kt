package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

open class QuanbenProvider : MainAPI() {
    override val name = "Quanben"
    override val mainUrl = "https://www.quanben.io"
    override val iconId = R.drawable.icon_quanben
    override val hasMainPage = true
    override val lang = "zh"
    // Browse (checked 2026-10-11): quanben.io has no ranking or latest-updates page, only the
    // home page ("推荐小说" plus a few books per genre, one page) and the genre listings
    // /c/{genre}.html, /c/{genre}_{page}.html in the site's own order (best known works first).
    // Every book on the site is finished (全本 = "complete"), so there is no status filter.
    override val tags = listOf(
        "Recommended (推荐小说)" to "",
        "Xuanhuan fantasy (玄幻)" to "xuanhuan",
        "Urban (都市)" to "dushi",
        "Romance (言情)" to "yanqing",
        "Time travel (穿越)" to "chuanyue",
        "Youth (青春)" to "qingchun",
        "Xianxia (仙侠)" to "xianxia",
        "Supernatural (灵异)" to "lingyi",
        "Mystery (悬疑)" to "xuanyi",
        "History (历史)" to "lishi",
        "Military (军事)" to "junshi",
        "Games (游戏)" to "youxi",
        "Sports (竞技)" to "jingji",
        "Sci-fi (科幻)" to "kehuan",
        "Workplace (职场)" to "zhichang",
        "Officialdom (官场)" to "guanchang",
        "Modern romance (现言)" to "xianyan",
        "Danmei (耽美)" to "danmei",
        "Other (其它)" to "qita"
    )

    private fun getStandardNovelPath(url: String?): String? {
        if (url == null) return null
        val absoluteUrl = fixUrl(url)
        val regex = Regex("""^https?://www\.quanben\.io(/(amp)?)?/(n/[^/]+/)$""")
        val match = regex.find(absoluteUrl)
        return match?.groupValues?.get(3)?.trim('/')
    }

    /** Pure: search results and category listings. Covered by a JVM fixture test. */
    internal fun parseNovels(document: Document): List<SearchResponse> {
        val novels = mutableListOf<SearchResponse>()

        // Type 1: list2 blocks
        document.select("div.list2").forEach { el ->
            val titleEl = el.selectFirst("h3 > a") ?: return@forEach
            val name = titleEl.text().trim()
            val href = titleEl.attr("href")
            val path = getStandardNovelPath(href) ?: return@forEach

            val img = el.selectFirst("img")
            val posterUrl = fixUrlNull(img?.attr("src"))

            novels.add(newSearchResponse(name, "$mainUrl/$path/") {
                this.posterUrl = posterUrl
            })
        }

        // Type 2: ul.list items
        document.select("ul.list").forEach { ul ->
            val firstLi = ul.selectFirst("li") ?: return@forEach
            val a = firstLi.selectFirst("a") ?: return@forEach
            val href = a.attr("href")
            val path = getStandardNovelPath(href) ?: return@forEach

            val name = a.text().trim().ifEmpty { firstLi.selectFirst("span.author")?.text()?.trim() ?: "Unknown" }

            if (novels.none { it.url.contains(path) }) {
                novels.add(newSearchResponse(name, "$mainUrl/$path/") {
                    this.posterUrl = null
                })
            }
        }

        return novels
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = mainPageUrl(page, tag)
        // The home page has a single page; repeating it would duplicate the list.
        if (tag.isNullOrBlank() && page > 1) return HeadMainPageResponse(url, emptyList())

        val document = app.get(url).document
        return HeadMainPageResponse(url, parseNovels(document))
    }

    internal fun mainPageUrl(page: Int, tag: String?): String = when {
        tag.isNullOrBlank() -> mainUrl
        page <= 1 -> "$mainUrl/c/$tag.html"
        else -> "$mainUrl/c/${tag}_$page.html"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/index.php?c=book&a=search&keywords=$encoded"
        val document = app.get(url).document
        return parseNovels(document)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        titleOf(document) // fail before fetching the chapter list

        val slug = slugOf(url)
        // The AMP chapter list has every chapter on one page (2451 for the longest works seen).
        // The regular list.html only shows the first and last ~24 and loads the rest through a
        // signed JSONP call, so it is a fallback for when the AMP page fails.
        val listDoc = runCatching { app.get(ampListUrl(slug)).document }.getOrNull()
            ?.takeIf { parseChapterList(it).isNotEmpty() }
            ?: runCatching { app.get("$mainUrl/n/$slug/list.html").document }.getOrNull()
        return parseLoad(url, document, listDoc)
    }

    internal fun ampListUrl(slug: String) = "$mainUrl/amp/n/$slug/list.html"

    private fun titleOf(document: Document): String =
        document.selectFirst("meta[property=og:novel:book_name]")?.attr("content")
            ?: document.selectFirst("div.list2 h3")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

    private fun slugOf(url: String): String =
        Regex("""n/([^/]+)""").find(url)?.groupValues?.get(1) ?: throw ErrorLoadingException("Slug not found")

    /**
     * Chapter names and URLs from one chapter-list page (the AMP list or list.html), taken
     * from the same `<a>` so they cannot drift apart. AMP links (`/amp/n/<slug>/<n>.html`) are
     * mapped to the regular reader pages. A list.html whose middle part is still collapsed
     * (`#detail` with the "展开完整列表" link) is only a preview, so it yields nothing rather
     * than a list with a gap.
     */
    internal fun parseChapterList(listDoc: Document): List<Pair<String, String>> {
        if (listDoc.selectFirst("#detail .more, a[onclick^=load_more]") != null) return emptyList()
        return listDoc.select("ul.list3 li a[href]").mapNotNull { a ->
            val name = a.text().trim()
            val path = Regex("""^(?:https?://[^/]+)?(?:/amp)?(/n/[^/]+/\d+\.html)$""")
                .find(a.attr("href").trim())?.groupValues?.get(1)
            if (name.isEmpty() || path == null) null else name to "$mainUrl$path"
        }.distinctBy { it.second }
    }

    /**
     * Pure half of [load]: [document] is the novel page and [listDoc] its chapter list (the AMP
     * list page; null when that fetch failed). Covered by a JVM fixture test.
     */
    internal suspend fun parseLoad(url: String, document: Document, listDoc: Document?): LoadResponse {
        val name = titleOf(document)

        val chapters = listDoc?.let { doc ->
            runCatching { parseChapterList(doc) }.getOrNull()
        }.orEmpty().map { (chName, chUrl) -> newChapterData(chName, chUrl) }

        return newStreamResponse(name, url, chapters) {
            this.posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: fixUrlNull(document.selectFirst("div.list2 img")?.attr("src"))

            this.author =  document.selectFirst("meta[property=og:novel:author]")?.attr("content")
                ?: document.selectFirst("div.list2 p:contains(作者:) span")?.text()?.trim()

            this.synopsis = document.selectFirst("meta[property=og:description]")?.attr("content")
                ?: document.selectFirst("div.description p")?.text()?.trim()
                        ?: document.selectFirst("div.description")?.text()?.trim()

            this.tags = (document.selectFirst("meta[property=og:novel:category]")
                ?.attr("content")
                ?:document.selectFirst("div.list2 p:contains(类别:) span")
                ?.text()?.trim())
                ?.split(",")?.map { it.trim() }

            // og:novel:status is Chinese (连载 / 完结); setStatus() only knows English words.
            val statusText = document.selectFirst("meta[property=og:novel:status]")?.attr("content")
                ?: document.selectFirst("div.list2 p:contains(状态:) span")?.text()
            this.status = CjkStatus.parse(statusText)
        }
    }

    override suspend fun loadHtml(url: String): String? = parseChapter(app.get(url).document)

    /** Pure half of [loadHtml]. */
    internal fun parseChapter(document: Document): String? {
        val content = document.selectFirst("#contentbody, #content, .content") ?: return null
        content.select("script, style, ins, iframe, [class*=ads], [id*=ads], [class*=google], [id*=google], [class*=recommend], div[align=center]").remove()
        return content.html().trim()
    }
}
