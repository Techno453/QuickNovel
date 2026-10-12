package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.chinese.JjwxcParser
import com.lagradost.quicknovel.providers.chinese.JjwxcProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fixture tests for [JjwxcProvider]. The fixtures under src/test/resources/providers/jjwxc are
 * stored GB18030-encoded like the site serves them (synopses and chapter text scrubbed).
 */
class JjwxcProviderTest {
    private fun fixture(name: String): String {
        val bytes = javaClass.classLoader!!.getResourceAsStream("providers/jjwxc/$name")!!.readBytes()
        // The site sends "Content-Type: text/html" without a charset.
        return JjwxcParser.decode(bytes, "text/html")
    }

    private fun doc(name: String, url: String = "https://www.jjwxc.net/") = Jsoup.parse(fixture(name), url)

    private val api = JjwxcProvider()

    @Test
    fun declaresChineseAndBrowseLists() {
        assertEquals("zh", api.lang)
        assertTrue(api.hasMainPage)
        assertEquals("Popular (积分)" to "2", api.orderBys.first())
        assertEquals("All" to "0", api.mainCategories.first())
        assertTrue(api.mainCategories.any { it.second == "2" && it.first.startsWith("Completed") })
        assertTrue(api.orderBys.any { it.second == "1" && it.first.startsWith("Latest") })
    }

    @Test
    fun decodesGb18030WhateverTheMetaSays() {
        // The lock notice declares utf-8 in its meta tag but is GBK.
        assertTrue(fixture("locked_chapter.html").contains("非常抱歉"))
        assertEquals("晋江", JjwxcParser.decode("晋江".toByteArray(Charsets.UTF_8), "text/html; charset=UTF-8"))
    }

    @Test
    fun urls() {
        assertEquals("4001734", JjwxcParser.novelId("https://www.jjwxc.net/onebook.php?novelid=4001734&chapterid=2"))
        assertEquals("4001734", JjwxcParser.novelId("https://m.jjwxc.net/book2/4001734"))
        assertEquals(
            "https://www.jjwxc.net/search.php?kw=%C4%D1%BA%E5&t=1",
            JjwxcParser.searchUrl("难哄"),
        )
        assertEquals(
            "https://www.jjwxc.net/bookbase.php?sortType=2&isfinish=0&page=1",
            JjwxcParser.listUrl(1, "0", "2", ""),
        )
        assertEquals(
            "https://www.jjwxc.net/bookbase.php?xx2=2&sortType=1&isfinish=2&page=3",
            JjwxcParser.listUrl(3, "2", "1", "2"),
        )
        assertTrue(JjwxcParser.isVipRedirect("https://my.jjwxc.net/login.php?referer=%2F%2Fmy.jjwxc.net%2Fonebook_vip.php"))
        assertFalse(JjwxcParser.isVipRedirect("https://www.jjwxc.net/onebook.php?novelid=1&chapterid=2"))
    }

    @Test
    fun searchResults() {
        val results = JjwxcParser.parseSearch(api, doc("search.html"))
        assertEquals(listOf("难哄", "被标记的Alpha超难哄", "娇妻难哄", "我很难哄的"), results.map { it.name })
        assertEquals("https://www.jjwxc.net/onebook.php?novelid=4001734", results[0].url)
    }

    @Test
    fun popularList() {
        val list = JjwxcParser.parseList(api, doc("list_popular.html"))
        assertEquals(listOf("天官赐福", "欢迎进入梦魇直播间", "某某", "全球高考", "我行让我上[电竞]"), list.map { it.name })
        assertEquals("https://www.jjwxc.net/onebook.php?novelid=3200611", list[0].url)
    }

    @Test
    fun latestList() {
        val list = JjwxcParser.parseList(api, doc("list_latest.html"))
        assertEquals(5, list.size)
        assertEquals("咒回：我玩的游戏怎么是真的", list[0].name)
        assertEquals("https://www.jjwxc.net/onebook.php?novelid=11109607", list[0].url)
    }

    @Test
    fun completedList() {
        val html = fixture("list_completed.html")
        val list = JjwxcParser.parseList(api, Jsoup.parse(html, "https://www.jjwxc.net/"))
        assertEquals(5, list.size)
        // Every row of the isfinish=2 listing is marked 完结.
        val statuses = Jsoup.parse(html).select("tr:has(> td > a[href*=oneauthor.php]) > td:eq(3)").map { it.text() }
        assertEquals(List(5) { "完结" }, statuses)
    }

    @Test
    fun pageElevenNeedsLoginAndEndsTheList() {
        assertTrue(JjwxcParser.isLoginWall(doc("list_login_wall.html")))
        assertTrue(JjwxcParser.parseList(api, doc("list_login_wall.html")).isEmpty())
        assertFalse(JjwxcParser.isLoginWall(doc("list_popular.html")))
    }

    @Test
    fun bookWithVipChapters() {
        val book = JjwxcParser.parseBook(
            api, doc("book_vip.html", "https://www.jjwxc.net/onebook.php?novelid=4001734"), "4001734"
        )
        assertNotNull(book)
        book!!
        assertEquals("难哄", book.title)
        assertEquals("竹已", book.author)
        assertEquals(ReleaseStatus.Completed, book.status)
        assertEquals(listOf("欢喜冤家", "天之骄子", "甜文", "轻松", "原创-言情-近代现代-爱情"), book.tags)
        assertTrue(book.cover!!.startsWith("https://i7-static.jjwxc.net/"))
        // Free chapters only, in order. Every headline is the book title, so the summary is used.
        assertEquals(
            listOf("第1章 堕落街。", "第2章 正经酒吧。", "第3章 不死心啊？", "第14章 他肯定是基佬。"),
            book.chapters.map { it.name },
        )
        assertEquals("https://www.jjwxc.net/onebook.php?novelid=4001734&chapterid=1", book.chapters[0].url)
        assertNotNull(book.chapters[0].dateOfRelease)
        assertEquals(listOf(15, 16, 77), book.vipChapters)
        assertTrue(book.lockedChapters.isEmpty())
        val synopsis = book.synopsisWithNotes()!!
        assertTrue(synopsis, synopsis.contains("3 VIP chapters (from chapter 15)"))
        assertFalse("scrubbed synopsis, no images", synopsis.contains("<"))
    }

    @Test
    fun bookWithLockedChapters() {
        val book = JjwxcParser.parseBook(
            api, doc("book_locked.html", "https://www.jjwxc.net/onebook.php?novelid=2456"), "2456"
        )!!
        assertEquals("何以笙箫默(全文地址见内)", book.title)
        assertEquals("顾漫", book.author)
        assertEquals(listOf("都市", "正剧", "原创-言情-近代现代-爱情"), book.tags)
        assertEquals(listOf("第一章", "第六章", "第八章", "第10章 番外（一）"), book.chapters.map { it.name })
        assertEquals(listOf(7, 9), book.lockedChapters)
        assertTrue(book.vipChapters.isEmpty())
        assertTrue(book.synopsisWithNotes()!!.contains("2 chapters locked"))
    }

    @Test
    fun chapterText() {
        val html = JjwxcParser.parseChapter(fixture("chapter.html"))
        assertNotNull(html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        assertEquals(148, paragraphs.size)
        assertEquals(listOf("《戊己》", "2020.03.17/丁戊", "丙丁戊"), paragraphs.take(3))
        assertFalse("page chrome removed", html.contains("插入书签") || html.contains("下一章"))
        assertFalse("author's note not included", html.contains("作者有话说"))
    }

    @Test
    fun lockedChapterIsAReadableError() {
        val e = assertThrows(ErrorLoadingException::class.java) {
            JjwxcParser.parseChapter(fixture("locked_chapter.html"))
        }
        assertEquals(JjwxcParser.LOCKED_MESSAGE, e.message)
        assertNull(JjwxcParser.parseChapter("<html><body><p>nothing here</p></body></html>"))
    }

    /** Search, novel, chapter, every main listing with paging, and the VIP lock. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking {
            LiveTests.smoke(api, "难哄")

            // Browse: popular / latest / completed, page 1 vs page 2.
            for ((label, args) in listOf(
                "popular" to Triple("0", "2", ""),
                "latest" to Triple("0", "1", ""),
                "completed" to Triple("2", "2", ""),
                "BL popular" to Triple("0", "2", "2"),
            )) {
                val p1 = api.loadMainPage(1, args.first, args.second, args.third)
                val p2 = api.loadMainPage(2, args.first, args.second, args.third)
                println("[live] ${api.name}: $label ${p1.url} → ${p1.list.size}, page 2 → ${p2.list.size} (first: ${p1.list.firstOrNull()?.name} / ${p2.list.firstOrNull()?.name})")
                assertTrue("$label page 1 empty", p1.list.isNotEmpty())
                assertTrue("$label page 2 empty", p2.list.isNotEmpty())
                assertNotEquals("$label page 2 repeats page 1", p1.list.first().url, p2.list.first().url)
            }
            assertTrue(api.loadMainPage(11, "0", "2", "").list.isEmpty())

            // VIP: the novel lists only its free chapters; a VIP chapter url is a readable error.
            val book = api.load("https://www.jjwxc.net/onebook.php?novelid=4001734") as StreamResponse
            println("[live] ${api.name}: '${book.name}' → ${book.data.size} free chapters; ${book.synopsis?.lines()?.last()}")
            assertTrue(book.data.isNotEmpty())
            assertTrue(book.synopsis!!.contains("VIP chapters"))
            val vip = runCatching { api.loadHtml("https://www.jjwxc.net/onebook.php?novelid=4001734&chapterid=15") }
            println("[live] ${api.name}: VIP chapter → ${vip.exceptionOrNull()?.message}")
            assertEquals(JjwxcParser.VIP_MESSAGE, vip.exceptionOrNull()?.message)
        }
    }
}
