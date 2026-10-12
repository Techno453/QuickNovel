package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.japanese.PixivNovelParser
import com.lagradost.quicknovel.providers.japanese.PixivNovelProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixture tests for [PixivNovelProvider] (src/test/resources/providers/pixiv, text replaced). */
class PixivNovelProviderTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("providers/pixiv/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val api = PixivNovelProvider()

    @Test
    fun declaresJapanese() {
        assertEquals("ja", api.lang)
    }

    @Test
    fun searchMapsOneShotsAndSeriesAndSkipsR18() {
        val results = PixivNovelParser.parseSearch(api, PixivNovelParser.body(fixture("search.json")))
        assertEquals(listOf("テスト短編", "テスト連載"), results.map { it.name })
        // One-shot cards link the novel id, not the card id.
        assertEquals("https://www.pixiv.net/novel/show.php?id=29361060", results[0].url)
        assertEquals("https://www.pixiv.net/novel/series/15840364", results[1].url)
        assertEquals("32 episodes", results[1].latestChapter)
        assertTrue(results[0].posterUrl!!.startsWith("https://i.pximg.net/c/480x960/"))
        assertEquals("https://www.pixiv.net/", results[0].posterHeaders?.get("Referer"))
    }

    @Test
    fun titleSearchFoldsEpisodesIntoTheirSeries() {
        val results = PixivNovelParser.parseSearch(api, PixivNovelParser.body(fixture("search_title.json")))
        assertEquals(listOf("占位の連載タイトル", "占位の短編"), results.map { it.name })
        assertEquals("https://www.pixiv.net/novel/series/16633979", results[0].url)
        assertEquals("https://www.pixiv.net/novel/show.php?id=29361224", results[1].url)
        assertTrue(results[1].posterUrl!!.endsWith("b_master1200.jpg"))
    }

    @Test
    fun seriesWithContentPage() {
        val page = PixivNovelParser.parseSeriesContent(api, PixivNovelParser.body(fixture("series_content.json")))
        assertEquals(3, page.count) // raw page size drives paging
        assertEquals(listOf("【第1話】はじまり", "【第2話】つづき"), page.chapters.map { it.name })
        assertEquals("https://www.pixiv.net/novel/show.php?id=27971337", page.chapters[0].url)
        assertEquals("2026-05-03", page.chapters[0].dateOfRelease)

        val info = PixivNovelParser.parseSeries(api, PixivNovelParser.body(fixture("series.json")), "15840364", page.chapters)
        assertNotNull(info)
        info!!
        assertEquals("テスト連載", info.title)
        assertEquals("連載作者", info.author)
        assertEquals("連載の説明一行目。\n連載の説明二行目。", info.synopsis)
        assertEquals(listOf("異世界転生", "ファンタジー"), info.tags)
        assertEquals(ReleaseStatus.Ongoing, info.status)
        assertEquals("https://www.pixiv.net/novel/series/15840364", info.url)
        assertTrue(info.cover!!.contains("480x960"))
    }

    @Test
    fun oneShotIsASingleChapter() {
        val info = PixivNovelParser.parseOneShot(api, PixivNovelParser.body(fixture("novel.json")))
        assertNotNull(info)
        info!!
        assertEquals("テスト短編", info.title)
        assertEquals("説明一行目。\n説明二行目。", info.synopsis)
        assertEquals(listOf("オリジナル", "ファンタジー"), info.tags)
        assertEquals(listOf("https://www.pixiv.net/novel/show.php?id=29361060"), info.chapters.map { it.url })
    }

    @Test
    fun markupBecomesParagraphs() {
        val html = PixivNovelParser.contentToHtml(PixivNovelParser.body(fixture("novel.json")))
        assertNotNull(html)
        assertEquals(
            listOf(
                "第一章　はじまり",
                "一行目の占位文。",
                "「台詞の占位文」",
                "漢字を読む。魔法《マジック》を使う。",
                "リンクの文字と &lt;記号&gt; と <b>タグ風</b>。",
                "二ページ目の文。",
            ),
            Jsoup.parseBodyFragment(html!!).select("p").map { it.text() }
        )
        assertTrue("paragraphs separated by blank lines", html.contains("</p>\n\n<p>"))
    }

    @Test
    fun apiErrorsSurfaceTheSiteMessage() {
        try {
            PixivNovelParser.body(fixture("error.json"), "Pixiv")
            fail("expected ErrorLoadingException")
        } catch (e: ErrorLoadingException) {
            assertTrue(e.message!!.contains("該当作品は削除されたか"))
        }
    }

    @Test
    fun ids() {
        assertEquals("15840364", PixivNovelParser.seriesId("https://www.pixiv.net/novel/series/15840364"))
        assertEquals("29361060", PixivNovelParser.novelId("https://www.pixiv.net/novel/show.php?id=29361060"))
        assertEquals("29361060", PixivNovelParser.novelId("https://www.pixiv.net/novel/show.php?mode=x&id=29361060"))
        assertNull(PixivNovelParser.seriesId("https://www.pixiv.net/novel/show.php?id=1"))
    }

    /** One search, one novel and one chapter against the live site. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking {
            LiveTests.smoke(api, "異世界")
            // series_content is paged 30 at a time; this series had 38 public episodes (2026-10).
            val series = api.load("https://www.pixiv.net/novel/series/15840364") as StreamResponse
            println("[live] ${api.name}: paginated series → ${series.data.size} episodes")
            assertTrue("expected more than one page of episodes", series.data.size > 30)
            assertEquals(series.data.size, series.data.map { it.url }.distinct().size)
        }
    }
}
