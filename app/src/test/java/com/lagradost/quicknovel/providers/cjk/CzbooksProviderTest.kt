package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.chinese.CzbooksParser
import com.lagradost.quicknovel.providers.chinese.CzbooksProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixture tests for [CzbooksProvider] (UTF-8 fixtures, synopsis and chapter text scrubbed). */
class CzbooksProviderTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("providers/czbooks/$name")!!.readBytes().toString(Charsets.UTF_8)

    private fun doc(name: String, url: String = "https://czbooks.net/") = Jsoup.parse(fixture(name), url)

    private val api = CzbooksProvider()

    @Test
    fun declaresChineseAndBrowseLists() {
        assertEquals("zh", api.lang)
        assertTrue(api.hasMainPage)
        assertEquals("week", api.orderBys.first().second)
        assertTrue(api.orderBys.first().first.startsWith("Popular"))
        assertEquals("All genres" to "", api.tags.first())
        assertTrue(api.orderBys.any { it.second == "new" })
        assertTrue(api.orderBys.any { it.second == "finish" })
        assertFalse("adult categories are not offered", api.tags.any { it.second.endsWith("erotic") })
    }

    @Test
    fun urls() {
        assertEquals("shhmoa", CzbooksParser.novelId("https://czbooks.net/n/shhmoa/shbfc5mm?chapterNumber=1"))
        assertEquals("https://czbooks.net/s/%E9%9A%BE%E5%93%84", CzbooksParser.searchUrl("难哄"))
        assertEquals("https://czbooks.net/c/normal/week/1", CzbooksParser.listUrl("", "week", 1))
        assertEquals("https://czbooks.net/c/normal/total/2", CzbooksParser.listUrl(null, "total", 2))
        assertEquals("https://czbooks.net/c/finish/week/3", CzbooksParser.listUrl("", "finish", 3))
        assertEquals("https://czbooks.net/c/xuanhuan/new/2", CzbooksParser.listUrl("xuanhuan", "new", 2))
        assertEquals("https://czbooks.net/c/xianxia/finish/1", CzbooksParser.listUrl("xianxia", "finish", 1))
        assertEquals("https://czbooks.net/c/xianxia/day/4", CzbooksParser.listUrl("xianxia", "day", 4))
        assertNull("no site-wide latest list", CzbooksParser.listUrl("", "new", 1))
    }

    @Test
    fun searchResults() {
        val results = CzbooksParser.parseList(api, doc("search.html"), 1)
        assertEquals(listOf("難哄", "你多哄著我", "你能不能哄哄我", "心魔难受", "老公每天都要哄"), results.map { it.name })
        assertEquals("https://czbooks.net/n/shhmoa", results[0].url)
        assertEquals("第89章 番外", results[0].latestChapter)
        assertTrue(results[0].posterUrl!!.startsWith("https://img.czbooks.net/thumbnail/"))
    }

    @Test
    fun popularList() {
        val list = CzbooksParser.parseList(api, doc("list_popular.html"), 1)
        assertEquals(
            listOf("蘭香緣（蘭香如故）", "早春晴朗_姑娘別哭【完結+番外】", "成名[娛樂圈]_周沅【完結】", "誰讓他修仙的！", "娘娘她總是不上進"),
            list.map { it.name },
        )
        assertEquals("https://czbooks.net/n/u9mbf", list[0].url)
        assertEquals("後記", list[0].latestChapter)
        assertEquals(1, CzbooksParser.activePage(doc("list_popular.html")))
    }

    @Test
    fun latestListIsPageTwo() {
        val d = doc("list_latest.html")
        assertEquals(2, CzbooksParser.activePage(d))
        val list = CzbooksParser.parseList(api, d, 2)
        assertEquals(listOf("當個鹹魚這麼難嗎?", "九九久久", "重生08：裝備系男神", "剛好和你同一組", "古代食肆經營日常_眠微【完結】"), list.map { it.name })
        // The site answers a page past the end with some other page; that must end the list.
        assertTrue(CzbooksParser.parseList(api, d, 3).isEmpty())
    }

    @Test
    fun completedList() {
        val list = CzbooksParser.parseList(api, doc("list_completed.html"), 1)
        assertEquals(4, list.size)
        assertEquals("囚蝶", list[0].name)
        assertEquals("https://czbooks.net/n/cr327h", list[0].url)
    }

    @Test
    fun novelPage() {
        val novel = CzbooksParser.parseNovel(api, doc("novel.html", "https://czbooks.net/n/shhmoa"))
        assertNotNull(novel)
        novel!!
        assertEquals("難哄", novel.title)
        assertEquals("竹已", novel.author)
        assertEquals(ReleaseStatus.Completed, novel.status)
        assertEquals("https://img.czbooks.net/thumbnail/1b694824818b4c3e6457d2ad3f548ed6.jpg", novel.cover)
        assertEquals("言情", novel.tags.first())
        assertTrue(novel.tags.size <= 13)
        assertTrue(novel.synopsis!!.lines().size >= 3)
        assertEquals(
            listOf("作品相關", "第1章", "第2章", "第3章", "第4章", "第5章", "第86章 番外", "第88章 番外", "第89章 番外"),
            novel.chapters.map { it.name },
        )
        assertEquals("https://czbooks.net/n/shhmoa/shbfc5mm?chapterNumber=1", novel.chapters[1].url)
    }

    @Test
    fun chapterText() {
        val html = CzbooksParser.parseChapter(fixture("chapter.html"))
        assertNotNull(html)
        val paragraphs = Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        assertEquals(143, paragraphs.size)
        assertEquals("甲乙丙丁戊己，辛壬癸甲乙丙丁戊己庚辛壬。", paragraphs.first())
        assertFalse("navigation removed", html.contains("下一章"))
    }

    /** Search, novel, chapter, every main listing with paging. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking {
            LiveTests.smoke(api, "难哄")
            // Chapter 0 of that novel is the "作品相關" intro; check a real chapter too.
            val novel = api.load("https://czbooks.net/n/shhmoa") as StreamResponse
            val chapter = novel.data[1]
            val paragraphs = Jsoup.parseBodyFragment(api.loadHtml(chapter.url)!!).select("p")
            println("[live] ${api.name}: chapter '${chapter.name}' → ${paragraphs.size} paragraphs")
            assertTrue(paragraphs.size >= 20)
            for ((label, args) in listOf(
                "popular (all genres)" to ("week" to ""),
                "popular all time" to ("total" to ""),
                "completed (all genres)" to ("finish" to ""),
                "latest (xuanhuan)" to ("new" to "xuanhuan"),
                "completed (xianxia)" to ("finish" to "xianxia"),
            )) {
                val p1 = api.loadMainPage(1, null, args.first, args.second)
                val p2 = api.loadMainPage(2, null, args.first, args.second)
                println("[live] ${api.name}: $label ${p1.url} → ${p1.list.size}, page 2 → ${p2.list.size} (first: ${p1.list.firstOrNull()?.name} / ${p2.list.firstOrNull()?.name})")
                assertTrue("$label page 1 empty", p1.list.isNotEmpty())
                assertTrue("$label page 2 empty", p2.list.isNotEmpty())
                assertNotEquals("$label page 2 repeats page 1", p1.list.first().url, p2.list.first().url)
            }
            val far = api.loadMainPage(5000, null, "new", "xuanhuan")
            println("[live] ${api.name}: latest (xuanhuan) page 5000 → ${far.list.size}")
            assertTrue(far.list.isEmpty())
            val err = runCatching { api.loadMainPage(1, null, "new", "") }.exceptionOrNull()
            assertTrue(err is ErrorLoadingException)
        }
    }
}
