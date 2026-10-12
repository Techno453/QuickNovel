package com.lagradost.quicknovel.providers.cjk

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.japanese.AlphapolisParser
import com.lagradost.quicknovel.providers.japanese.AlphapolisProvider
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixture tests for [AlphapolisProvider] (src/test/resources/providers/alphapolis, text replaced). */
class AlphapolisProviderTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("providers/alphapolis/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val api = AlphapolisProvider()

    @Test
    fun declaresJapanese() {
        assertEquals("ja", api.lang)
    }

    @Test
    fun searchAndIndexCards() {
        val results = AlphapolisParser.parseList(api, fixture("search.html"))
        assertEquals(listOf("テスト作品その一", "テスト作品その二", "一覧の作品"), results.map { it.name })
        assertEquals("https://www.alphapolis.co.jp/novel/111111/222222", results[0].url)
        assertEquals("https://cdn-image.alphapolis.co.jp/cover/222222/500x711/test-cover", results[0].posterUrl)
        assertEquals("https://cdn-image.alphapolis.co.jp/cover/666666/500x711/index-cover", results[2].posterUrl)
    }

    @Test
    fun workTocSkipsPaidRentals() {
        val work = AlphapolisParser.parseWork(api, Jsoup.parse(fixture("novel.html"), "https://www.alphapolis.co.jp/novel/111111/222222"))
        assertNotNull(work)
        work!!
        assertEquals("テスト作品その一", work.title)
        assertEquals("テスト作者", work.author)
        assertEquals(ReleaseStatus.Completed, work.status) // from the sidebar, not the recommendations
        assertEquals(listOf("ファンタジー", "魔法"), work.tags)
        assertEquals("あらすじ一行目。\nあらすじ二行目。\n「あらすじの台詞」", work.synopsis)
        assertEquals("https://cdn-image.alphapolis.co.jp/cover/222222/500x711/test-cover", work.cover)
        assertEquals(listOf("1巻 - 1-1", "書き下ろし - 番外編"), work.chapters.map { it.name })
        assertEquals("https://www.alphapolis.co.jp/novel/111111/222222/episode/9001", work.chapters[0].url)
        assertEquals("2026.08.31 16:08", work.chapters[0].dateOfRelease)
        assertEquals(1, work.lockedCount)
    }

    @Test
    fun episodeBodyRequestParameters() {
        val request = AlphapolisParser.bodyRequest(fixture("episode.html"))
        assertNotNull(request)
        assertEquals("9001", request!!.episode)
        assertEquals("ad7fc2dc34faee300cc29946e8ee6007", request.token)
        assertEquals("b0F0Zlsb9US1zzdyA5CeDGJjAsyhw7TBH2rE3Cov", request.csrf)
        assertNull(AlphapolisParser.bodyRequest(fixture("novel.html")))
    }

    @Test
    fun episodeBodyLinesRubyAndImages() {
        val html = AlphapolisParser.parseEpisodeBody(fixture("episode_body.html"), "https://www.alphapolis.co.jp/novel/111111/222222/episode/9001")
        assertNotNull(html)
        val doc = Jsoup.parseBodyFragment(html!!)
        assertEquals(
            listOf("一行目の占位文。", "「台詞の占位文」", "漢字を読む。魔法《マジック》を使う。", "山括弧 <テスト> と記号。", "", "最後の行。"),
            doc.select("p").map { it.text() }
        )
        assertEquals("https://cdn-image.alphapolis.co.jp/illust/test.png", doc.selectFirst("img")!!.attr("src"))
        assertFalse(AlphapolisParser.isCaptcha(fixture("episode_body.html")))
        assertTrue(AlphapolisParser.isCaptcha("<form><div class=\"g-recaptcha\" data-sitekey=\"x\"></div></form>"))
    }

    @Test
    fun ids() {
        assertEquals("111111" to "222222", AlphapolisParser.workIds("https://www.alphapolis.co.jp/novel/111111/222222/episode/9001"))
        assertNull(AlphapolisParser.workIds("https://www.alphapolis.co.jp/search?query=x"))
    }

    /** One search, one novel and one chapter against the live site. Run with -Dlive=1. */
    @Test
    fun live() {
        assumeTrue("live test; run with -Dlive=1", LiveTests.enabled)
        runBlocking { LiveTests.smoke(api, "魔法") }
    }
}
