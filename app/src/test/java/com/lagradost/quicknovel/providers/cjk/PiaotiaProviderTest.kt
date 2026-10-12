package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.SourceGuard
import com.lagradost.quicknovel.providers.chinese.PiaotiaParser
import com.lagradost.quicknovel.providers.chinese.PiaotiaProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fixture tests for [PiaotiaProvider]. The fixtures under src/test/resources/providers/piaotia
 * are stored GBK-encoded like the site serves them (text replaced with placeholders).
 */
class PiaotiaProviderTest {
    /** Decodes like ChineseProviderUtils.textWithEncoding does for a gbk/gb2312 meta tag. */
    private fun fixture(name: String): String {
        val bytes = javaClass.classLoader!!.getResourceAsStream("providers/piaotia/$name")!!.readBytes()
        val head = bytes.copyOf(minOf(bytes.size, 600)).toString(Charsets.ISO_8859_1).lowercase()
        assertTrue("$name declares a GB charset", head.contains("charset=gbk") || head.contains("charset=gb2312"))
        return bytes.toString(charset("GBK"))
    }

    private val api = PiaotiaProvider()

    @Test
    fun declaresChinese() {
        assertEquals("zh", api.lang)
    }

    @Test
    fun gridRowsBecomeBookInfoUrls() {
        val results = PiaotiaParser.parseGrid(api, Jsoup.parse(fixture("search.html"), "https://www.piaotia.com/"))
        assertEquals(listOf("测试小说甲", "测试小说乙"), results.map { it.name })
        assertEquals("https://www.piaotia.com/bookinfo/0/760.html", results[0].url)
        assertEquals("第3章 占位标题", results[1].latestChapter)
    }

    @Test
    fun infoPageFields() {
        val info = PiaotiaParser.parseInfo(Jsoup.parse(fixture("info.html"), "https://www.piaotia.com/bookinfo/15/15726.html"))
        assertNotNull(info)
        info!!
        assertEquals("测试小说乙", info.title)
        assertEquals("作者乙", info.author)
        assertEquals(listOf("玄幻魔法"), info.tags)
        assertEquals(ReleaseStatus.Ongoing, info.status)
        assertEquals("简介第一行。\n简介第二行。", info.synopsis)
        assertEquals("https://www.piaotia.com/files/article/image/15/15726/15726s.jpg", info.cover)
    }

    @Test
    fun tocInOrderWithoutDuplicatesOrNavLinks() {
        val chapters = PiaotiaParser.parseToc(api, Jsoup.parse(fixture("toc.html"), "https://www.piaotia.com/html/15/15726/index.html"))
        assertEquals(listOf("第1章 第一章标题", "第2章 第二章标题", "第3章 第三章标题"), chapters.map { it.name })
        assertEquals("https://www.piaotia.com/html/15/15726/11439369.html", chapters[0].url)
    }

    @Test
    fun looseBodyTextBecomesParagraphs() {
        val html = PiaotiaParser.parseChapter(fixture("chapter.html"))
        assertNotNull(html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        assertEquals(listOf("「第一段对话。」", "第二段占位文字，含<符号>。", "第三段占位文字。"), paragraphs)
        assertFalse("navigation removed", html.contains("上一章"))
        assertFalse("scripts removed", html.contains("GetMode"))
    }

    @Test
    fun passiveCloudflareScriptIsNotABlock() {
        // Healthy piaotia pages end with Cloudflare's jsd script; that must not look like a challenge.
        assertNull(SourceGuard.classify(200, { null }, fixture("chapter.html")))
    }

    @Test
    fun ids() {
        assertEquals("15" to "15726", PiaotiaParser.ids("https://www.piaotia.com/bookinfo/15/15726.html"))
        assertEquals("15" to "15726", PiaotiaParser.ids("https://www.piaotia.com/html/15/15726/13128346.html"))
        assertEquals("0" to "760", PiaotiaParser.ids("https://www.piaotia.com/html/0/760/"))
    }

    /** One search, one novel and one chapter against the live site. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking { LiveTests.smoke(api, "修仙") }
    }
}
