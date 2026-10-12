package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import com.lagradost.quicknovel.providers.ProviderFixtures.text
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.chinese.ChineseProviderUtils
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiqugeProvider] (69shuba.com) against pages recorded live in 2026-10 with [RecordFixture]
 * (Cloudflare refuses curl from this network but serves OkHttp). The search POST answers with a
 * Turnstile challenge outside a WebView, so there is no search-result fixture; the category
 * listing uses the same `div.newbox` book-list markup and the same [BiqugeProvider.parseBookList].
 */
class Shuba69FixtureTest {
    private val api = BiqugeProvider()
    private val infoUrl = "https://www.69shuba.com/book/90435.htm"
    private val listUrl = "https://www.69shuba.com/book/90435/"

    @Test
    fun bookListTitlesUrlsAndLazyCovers() {
        val results = api.parseBookList(document("69shuba/booklist.html", "https://www.69shuba.com/novels/class/10.htm"))
        assertEquals(5, results.size)
        val first = results[0]
        assertEquals("霍格沃茨的学习面板", first.name)
        assertEquals("https://www.69shuba.com/book/90442.htm", first.url)
        // The real cover is in data-src; src is a nocover placeholder until lazy loading runs.
        assertEquals("https://cdn.cdnshu.com/files/article/image/90/90442/90442s.jpg", first.posterUrl)
        assertEquals(api.name, first.apiName)
        assertTrue(results.all { it.url.startsWith("https://www.69shuba.com/book/") && it.name.isNotBlank() })
    }

    @Test
    fun rankingPageIsTheBrowseListing() {
        // Recorded from /novels/monthvisit_0_0_1.htm (2026-10-11), trimmed to five entries.
        val url = "https://www.69shuba.com/novels/monthvisit_0_0_1.htm"
        val results = api.parseBookList(document("69shuba/rank.html", url))
        assertEquals(5, results.size)
        assertEquals("诡秘之主", results[0].name)
        assertEquals("https://www.69shuba.com/book/29590.htm", results[0].url)
        assertEquals("https://cdn.cdnshu.com/files/article/image/29/29590/29590s.jpg", results[0].posterUrl)
        assertEquals("我所有职业，天赋全是神级？", results[4].name)
        assertEquals("https://www.69shuba.com/book/90509.htm", results[4].url)
    }

    @Test
    fun browseUrlsUseTheRankingPages() {
        // Defaults (first order, all categories, any status).
        assertEquals("https://www.69shuba.com/novels/monthvisit_0_0_1.htm", api.mainPageUrl(1, null, null, null))
        assertEquals(
            "https://www.69shuba.com/novels/monthvisit_0_0_1.htm",
            api.mainPageUrl(1, api.mainCategories.first().second, api.orderBys.first().second, api.tags.first().second),
        )
        // Romance (3), ongoing (2), weekly popularity, page 2 (verified live: 50 ongoing romance works).
        assertEquals("https://www.69shuba.com/novels/weekvisit_3_2_2.htm", api.mainPageUrl(2, "2", "weekvisit", "3"))
        assertEquals("https://www.69shuba.com/novels/allvote_0_1_1.htm", api.mainPageUrl(0, "1", "allvote", ""))
        // Latest updates is a single page that ignores category and status.
        assertEquals("https://www.69shuba.com/last.html", api.mainPageUrl(1, "1", BiqugeProvider.LATEST, "3"))
        // No dead URL shapes from before the redesign.
        for (o in api.orderBys) for (c in api.mainCategories) for (t in api.tags) {
            val url = api.mainPageUrl(2, c.second, o.second, t.second)
            assertTrue(url, url.matches(Regex("""https://www\.69shuba\.com/(novels/[a-z]+_\d+_[012]_2\.htm|last\.html)""")))
        }
        assertEquals(api.tags.size, api.tags.map { it.second }.toSet().size)
    }

    @Test
    fun browseListingsCoverPopularLatestAndCompleted() {
        // Default (first) entries are the most-popular listing over everything.
        assertEquals("monthvisit", api.orderBys.first().second)
        assertEquals("0", api.mainCategories.first().second)
        assertEquals("0", api.tags.first().second)
        assertTrue(api.orderBys.any { it.second == BiqugeProvider.LATEST })
        assertTrue(api.mainCategories.any { it.second == "1" && it.first.startsWith("Completed") })
        // English labels (the site's term may follow in parentheses).
        for ((label, _) in api.orderBys + api.mainCategories + api.tags) {
            assertTrue(label, label.first() in 'A'..'Z')
        }
    }

    @Test
    fun latestUpdatesPage() {
        // Recorded from /last.html (2026-10-11): the 30 most recently updated books.
        val results = api.parseBookList(document("69shuba/latest.html", "https://www.69shuba.com/last.html"))
        assertEquals(30, results.size)
        assertEquals("这个影帝只想考证", results.first().name)
        assertEquals("https://www.69shuba.com/book/77771.htm", results.first().url)
        assertEquals("八小时工作制的朝廷鹰犬", results.last().name)
        assertTrue(results.all { it.url.matches(Regex("""https://www\.69shuba\.com/book/\d+\.htm""")) })
        assertEquals(30, results.map { it.url }.toSet().size)
    }

    @Test
    fun catalogUrlFromInfoUrl() {
        assertEquals(listUrl, api.catalogUrl(infoUrl))
    }

    @Test
    fun novelPageWithFullCatalog() = runBlocking {
        val load = api.parseLoad(
            infoUrl,
            document("69shuba/info.html", infoUrl),
            document("69shuba/catalog.html", listUrl),
        ) as StreamResponse
        assertEquals("喧嚣未及之处", load.name)
        assertEquals("沉默的爱", load.author)
        assertEquals(infoUrl, load.url)
        assertEquals("https://cdn.cdnshu.com/files/article/image/90/90435/90435s.jpg", load.posterUrl)
        assertTrue(!load.synopsis.isNullOrBlank())
        // og:novel:status 连载; tags = category + the bookinfo script's tag list.
        assertEquals(ReleaseStatus.Ongoing, load.status)
        assertEquals(listOf("官场职场", "穿越", "篮球", "天才流", "成熟", "热血", "嘴炮", "正能量"), load.tags)

        // The catalog is newest-first on the site; the provider reverses it.
        val chapters = load.data
        assertEquals(162, chapters.size)
        assertEquals("第1章 我略有心得", chapters.first().name)
        assertEquals("https://www.69shuba.com/txt/90435/40755036", chapters.first().url)
        assertEquals("第162章 健忘症这一块", chapters.last().name)
        assertEquals("https://www.69shuba.com/txt/90435/40941129", chapters.last().url)
        // The empty bookmark slot in the first div.catalog must not become a chapter.
        assertTrue(chapters.none { it.name.isBlank() || it.url.endsWith("#") })
    }

    @Test
    fun novelPageFallsBackToLatestChaptersWithoutCatalog() = runBlocking {
        val load = api.parseLoad(infoUrl, document("69shuba/info.html", infoUrl), null) as StreamResponse
        assertEquals(5, load.data.size)
        assertEquals("第158章 NBA的就是不一样啊", load.data.first().name)
        assertEquals("第162章 健忘症这一块", load.data.last().name)
    }

    @Test
    fun chapterTextIsCleanParagraphHtml() {
        val url = "https://www.69shuba.com/txt/90435/40755036"
        val html = BiqugeProvider.parseChapterHtml(text("69shuba/chapter.html"), url)
        // Heading, date/author line, the top/mid/bottom ad slots and the page nav are removed.
        assertCleanChapter(html, minParagraphs = 80, extraJunk = listOf("2025-09-22", "作者：", "contentadv", "bottom-ad"))
        html!!
        val paras = paragraphs(html)
        // The site repeats the heading as the first text line and ends with "(本章完)" ("end of
        // chapter"); the placeholder scrub turned these into "甲1丙 戊己庚辛壬" and "(乙丙丁)".
        assertEquals("甲1丙 戊己庚辛壬", paras.first())
        assertEquals("(乙丙丁)", paras.last())
        // One <p> per source line, separated by a blank line, with &emsp; / ideographic-space
        // indents trimmed and empty lines dropped.
        assertTrue(html.startsWith("<p>") && html.contains("</p>\n\n<p>"))
        assertTrue(paras.none { it.isBlank() || it.startsWith(" ") || it.startsWith("　") })
    }

    @Test
    fun decodesGbkFromMetaCharsetOrHeader() {
        val original = text("69shuba/info.html")
        val gbk = original.toByteArray(charset("GBK"))
        assertEquals(original, ChineseProviderUtils.decodeHtml(gbk, null))
        assertEquals(original, ChineseProviderUtils.decodeHtml(gbk, "text/html; charset=gbk"))
        assertFalse(ChineseProviderUtils.decodeHtml(gbk, "text/html; charset=utf-8").contains("喧嚣未及之处"))
    }
}
