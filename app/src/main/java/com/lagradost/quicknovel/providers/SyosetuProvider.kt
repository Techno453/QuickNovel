package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.*
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

    override val orderBys = listOf(
        "All Time" to "total",
        "Daily" to "daily",
        "Weekly" to "weekly",
        "Monthly" to "monthly",
        "Quarterly" to "quarter",
        "Yearly" to "yearly"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val genre = mainCategory ?: ""

        val url = "$mainUrl/rank/" + when {
            genre.startsWith("i") -> "isekailist/type/${orderBy}_${genre.substring(1)}/?p=$page"
            else -> "genrelist/type/${orderBy}_$genre/?p=$page"
        }

        val document = app.get(url).document
        val items = document.select(".p-ranklist-item").mapNotNull { container ->
            val a = container.selectFirst(".p-ranklist-item__title a") ?: return@mapNotNull null
            newSearchResponse(a.text().trim(), a.attr("href"))
        }
        return HeadMainPageResponse(url, items)
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url.replace(mainUrl, ncodeUrl)
        val document = app.get(fixedUrl).document
        titleOf(document) // fail before fetching the last table-of-contents page

        val finalDoc = lastPageUrl(document)?.let { app.get(it).document } ?: document
        return parseLoad(document, fixedUrl, finalDoc)
    }

    private fun titleOf(document: Document): String =
        document.selectFirst(".p-novel__title, .novel_title")?.text()
            ?: throw ErrorLoadingException("Title not found")

    /**
     * Pure half of [load]: [document] is the novel's first table-of-contents page and
     * [lastTocPage] its last one (the same page for works under 100 episodes).
     */
    internal suspend fun parseLoad(
        document: Document,
        fixedUrl: String,
        lastTocPage: Document = document,
    ): StreamResponse {
        val title = titleOf(document)

        val chapters = parseChapters(lastTocPage, fixedUrl)

        return newStreamResponse(title, fixedUrl, chapters) {

            posterUrl = ""
            this.synopsis = document.selectFirst("#novel_ex, .p-novel__summary")?.text()
            this.author = document.selectFirst(".p-novel__author a, .novel_writername a")?.text()

            val statusText = document.selectFirst(".p-novel__type, #noveltype_notend")?.text() ?: ""

            when {
                statusText.contains("完結") -> setStatus("complete")
                else -> setStatus("ongoing")
            }
            this.tags = document.select(".p-novel__tag a, .novel_key a").map { it.text() }
        }
    }

    /** URL of the last table-of-contents page, or null when the list fits on one page. */
    internal fun lastPageUrl(document: Document): String? {
        val lastPageBtn = document.selectFirst(".c-pager__item--last") ?: return null
        val href = lastPageBtn.attr("href")
        return if (href.startsWith("http")) href else "$ncodeUrl$href"
    }

    private fun parseChapters(finalDoc: Document, baseUrl: String): List<ChapterData> {
        val baseNcode = baseUrl.replace(mainUrl, ncodeUrl).removeSuffix("/")

        val totalChapters = finalDoc.select(".p-eplist__sublist a, .novel_sublist2 a")
            .lastOrNull()
            ?.attr("href")
            ?.split("/")
            ?.lastOrNull { it.isNotEmpty() && it.all { char -> char.isDigit() } }
            ?.toIntOrNull() ?: 0

        return (1..totalChapters).map { index ->
            newChapterData("Episode $index", "$baseNcode/$index/")
        }
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
}