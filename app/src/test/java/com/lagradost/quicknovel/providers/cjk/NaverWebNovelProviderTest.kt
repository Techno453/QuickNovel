package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.korean.NaverWebNovelParser
import com.lagradost.quicknovel.providers.korean.NaverWebNovelProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixture tests for [NaverWebNovelProvider] (src/test/resources/providers/naver, text replaced). */
class NaverWebNovelProviderTest {
    private fun fixture(name: String, url: String): Document = Jsoup.parse(
        javaClass.classLoader!!.getResourceAsStream("providers/naver/$name")!!.readBytes().toString(Charsets.UTF_8),
        url
    )

    private val api = NaverWebNovelProvider()

    @Test
    fun declaresKorean() {
        assertEquals("ko", api.lang)
    }

    @Test
    fun searchKeepsFreeLeaguesOnly() {
        val results = NaverWebNovelParser.parseCards(api, fixture("search.html", "https://novel.naver.com/search?keyword=x"))
        assertEquals(listOf("테스트 소설 하나", "테스트 소설 둘"), results.map { it.name })
        assertEquals("https://novel.naver.com/best/list?novelId=1217787", results[0].url)
        assertEquals("https://novel.naver.com/challenge/list?novelId=1238164", results[1].url)
        assertEquals("19회", results[1].latestChapter)
        assertTrue(results[0].posterUrl!!.startsWith("https://novel-phinf.pstatic.net/"))
        assertNull("placeholder cover src=\"?type=…\" dropped", results[1].posterUrl)
    }

    @Test
    fun genrePageSkipsCarousel() {
        val results = NaverWebNovelParser.parseCards(api, fixture("genre.html", "https://novel.naver.com/best/genre?genre=102"))
        assertEquals(listOf("장르 작품 하나"), results.map { it.name })
        assertEquals("76회", results[0].latestChapter)
    }

    @Test
    fun novelInfoAndEpisodes() {
        val doc = fixture("list.html", "https://novel.naver.com/challenge/list?novelId=1238164&order=Oldest&page=1")
        val info = NaverWebNovelParser.parseInfo(doc)
        assertNotNull(info)
        info!!
        assertEquals("테스트 소설 둘", info.title)
        assertEquals("작가B", info.author)
        assertEquals("줄거리 첫 줄.\n줄거리 둘째 줄.", info.synopsis)
        assertEquals(listOf("라이트노벨", "액션물", "학원물"), info.tags)
        assertEquals(ReleaseStatus.Ongoing, info.status)
        assertTrue(info.cover!!.contains("lightnov_8.png"))

        val episodes = NaverWebNovelParser.parseEpisodes(api, doc)
        // The notice link above the list is not an episode.
        assertEquals(listOf("프롤로그", "1화"), episodes.map { it.name })
        assertEquals("https://novel.naver.com/challenge/detail?novelId=1238164&volumeNo=1", episodes[0].url)
        assertEquals("2026.07.12", episodes[0].dateOfRelease)

        assertEquals(23, NaverWebNovelParser.totalEpisodes(doc))
        assertEquals(12, NaverWebNovelParser.pageCount(23, episodes.size))
        assertEquals(1, NaverWebNovelParser.pageCount(null, 10))
        assertEquals(3, NaverWebNovelParser.pageCount(21, 10))
    }

    @Test
    fun episodeSplitsPreformattedText() {
        val html = NaverWebNovelParser.parseEpisode(fixture("detail.html", "https://novel.naver.com/challenge/detail?novelId=1238164&volumeNo=1"))
        assertNotNull(html)
        val doc = Jsoup.parseBodyFragment(html!!)
        assertEquals(
            listOf("첫 문장 占位.", "“대사 占位.”", "‘생각 占位’ <기호>.", "바로 이어지는 줄.", "", "마지막 문단."),
            doc.select("p").map { it.text() }
        )
        assertEquals("https://novel-phinf.pstatic.net/test/illust.jpg", doc.selectFirst("img")!!.attr("src"))
        assertFalse("author's note is outside the body", html.contains("작가의 말"))
    }

    @Test
    fun refs() {
        assertEquals("best" to "1217787", NaverWebNovelParser.novelRef("https://novel.naver.com/best/list?novelId=1217787"))
        assertEquals("challenge" to "1238164", NaverWebNovelParser.novelRef("https://novel.naver.com/challenge/detail?novelId=1238164&volumeNo=3"))
        assertNull(NaverWebNovelParser.novelRef("https://novel.naver.com/webnovel/list?novelId=777"))
        assertTrue(NaverWebNovelParser.isLoginWall("https://nid.naver.com/nidlogin.login?url=x"))
    }

    /** One search, one novel and one chapter against the live site. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking {
            LiveTests.smoke(api, "마법")
            // Episode lists are paginated 10 per page: a 19-episode work needs two pages, in order.
            val paged = api.load("https://novel.naver.com/challenge/list?novelId=1238164") as StreamResponse
            val volumes = paged.data.map { it.url.substringAfter("volumeNo=").toInt() }
            println("[live] ${api.name}: paginated list → ${paged.data.size} episodes")
            assertTrue("expected more than one page of episodes", paged.data.size > 10)
            assertEquals(volumes.sorted(), volumes)
        }
    }
}
