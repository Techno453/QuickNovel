package com.lagradost.quicknovel.providers

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
