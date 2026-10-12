package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.chinese.IxdzsParser
import com.lagradost.quicknovel.providers.chinese.IxdzsProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixture tests for [IxdzsProvider] (src/test/resources/providers/ixdzs, text replaced). */
class IxdzsProviderTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("providers/ixdzs/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val api = IxdzsProvider()

    @Test
    fun declaresChinese() {
        assertEquals("zh", api.lang)
    }

    @Test
    fun searchCardsDeduplicated() {
        val results = IxdzsParser.parseList(api, fixture("search.html"))
        assertEquals(listOf("测试小说一", "测试小说二"), results.map { it.name })
        assertEquals("https://ixdzs8.com/read/100001/", results[0].url)
        assertEquals("https://img22.ixdzs.com/aa/bb/cover1.jpg", results[0].posterUrl)
        assertEquals(api.name, results[0].apiName)
    }

    @Test
    fun bookUsesFullTocInReadingOrder() {
        val book = IxdzsParser.parseBook(api, Jsoup.parse(fixture("book.html"), "https://ixdzs8.com/read/100002/"))
        assertNotNull(book)
        book!!
        assertEquals("测试小说二", book.title)
        assertEquals("作者乙", book.author)
        assertEquals(ReleaseStatus.Ongoing, book.status)
        assertEquals(listOf("修仙", "种田"), book.tags)
        assertEquals("简介第一行。\n简介第二行。", book.synopsis)
        assertEquals("https://img22.ixdzs.com/cc/dd/cover2.jpg", book.cover)
        // The newest-first preview list (ul.cfirst) must not leak in.
        assertEquals(
            listOf("第1章 第一章标题", "第2章 第二章标题", "第3章 第三章标题", "第4章 第四章标题"),
            book.chapters.map { it.name }
        )
        assertEquals("https://ixdzs8.com/read/100002/p1.html", book.chapters[0].url)
    }

    @Test
    fun chapterParagraphsWithoutSiteNoise() {
        val html = IxdzsParser.parseChapter(fixture("chapter.html"))
        assertNotNull(html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        assertEquals(
            listOf("“第一段对话？”占位文字。", "第二段占位文字，带全角缩进。", "第三段<占位>文字。", "第四段占位文字。"),
            paragraphs
        )
        assertFalse("heading dropped", html.contains("第二章标题"))
        assertTrue("paragraphs separated by blank lines", html.contains("</p>\n\n<p>"))
    }

    @Test
    fun challengeTokenDetected() {
        assertEquals("MTc5MTc3MjY1MDpURVNUVE9LRU4=", IxdzsParser.challengeToken(fixture("challenge.html")))
        assertNull(IxdzsParser.challengeToken(fixture("chapter.html")))
    }

    @Test
    fun ids() {
        assertEquals("100002", IxdzsParser.bookIdFromUrl("https://ixdzs8.com/read/100002/p3.html"))
        assertEquals(ReleaseStatus.Completed, IxdzsParser.status("已完结"))
    }

    /** One search, one novel and one chapter against the live site. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking { LiveTests.smoke(api, "凡人修仙") }
    }
}
