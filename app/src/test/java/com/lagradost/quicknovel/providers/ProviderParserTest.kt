package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.chinese.BiqugeProvider
import com.lagradost.quicknovel.providers.japanese.KakuyomuParser
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import com.lagradost.quicknovel.providers.korean.NovelpiaParser
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure parsing halves of the CJK providers, run against the small hand-written
 * `synthetic_*` fixtures under src/test/resources/providers (structure taken from the live sites,
 * text invented) that pin edge cases. The recorded real pages are covered by *FixtureTest.
 */
class ProviderParserTest {
    private fun fixture(name: String): String = ProviderFixtures.text(name)

    private fun paragraphs(html: String): List<String> = ProviderFixtures.paragraphs(html)

    // ─── Kakuyomu ───────────────────────────────────────────────────────────────

    private val kakuyomu = KakuyomuProvider()

    @Test
    fun kakuyomuSearchKeepsApolloOrder() {
        val results = KakuyomuParser.parseSearch(kakuyomu, fixture("kakuyomu/synthetic_search.html"))
        assertEquals(listOf("最初の作品", "二番目の作品"), results.map { it.name })
        assertEquals("https://kakuyomu.jp/works/1111", results[0].url)
        assertEquals(kakuyomu.name, results[0].apiName)
        assertEquals("Episode 47", results[0].latestChapter)
    }

    @Test
    fun kakuyomuWorkFlattensTableOfContents() {
        val work = KakuyomuParser.parseWork(kakuyomu, fixture("kakuyomu/synthetic_work.html"), "1111")
        assertNotNull(work)
        work!!
        assertEquals("最初の作品", work.title)
        assertEquals("テスト作者", work.author)
        assertEquals(ReleaseStatus.Ongoing, work.status)
        assertEquals(listOf("ダンジョン", "スキル"), work.tags)
        assertEquals(12345, work.views)
        assertTrue(work.synopsis!!.startsWith("キャッチコピー\n\nあらすじの一行目。"))
        assertEquals(
            listOf("第一章 - 第1話 はじまり", "第一章 - 第2話 つづき", "番外編"),
            work.chapters.map { it.name }
        )
        assertEquals("https://kakuyomu.jp/works/1111/episodes/7003", work.chapters[2].url)
        assertEquals("2026-08-29", work.chapters[0].dateOfRelease)
    }

    @Test
    fun kakuyomuWorkIdFromUrl() {
        assertEquals("1111", KakuyomuParser.workIdFromUrl("https://kakuyomu.jp/works/1111"))
        assertEquals("1111", KakuyomuParser.workIdFromUrl("https://kakuyomu.jp/works/1111/episodes/7001"))
        assertNull(KakuyomuParser.workIdFromUrl("https://kakuyomu.jp/search?q=x"))
    }

    @Test
    fun kakuyomuEpisodeParagraphsAndRuby() {
        val html = KakuyomuParser.parseEpisode(fixture("kakuyomu/synthetic_episode.html"), "https://kakuyomu.jp/works/1111/episodes/7001")
        assertNotNull(html)
        assertEquals(
            listOf("一行目の文章。", "漢字を読む。", "魔法《マジック》を使う。", "「ほんとに？」"),
            paragraphs(html!!)
        )
        assertTrue("paragraphs separated by blank lines", html.contains("</p>\n\n<p>"))
    }

    // ─── Novelpia ───────────────────────────────────────────────────────────────

    private val novelpia = NovelpiaProvider()

    @Test
    fun novelpiaSearchSkipsAdultWorks() {
        val results = NovelpiaParser.parseSearch(novelpia, fixture("novelpia/synthetic_search.json"))
        assertEquals(listOf("테스트 소설 하나", "테스트 소설 둘"), results.map { it.name })
        assertEquals("https://novelpia.com/novel/141671", results[0].url)
        assertEquals("https://images.novelpia.com/imagebox/cover/aaaa_ori.wimg", results[0].posterUrl)
        // cover_url null → falls back to the site-relative novel_img_all
        assertEquals("https://novelpia.com/imagebox/cover/bbbb_q_ori.file", results[1].posterUrl)
    }

    @Test
    fun novelpiaSearchErrorIsEmpty() {
        assertTrue(NovelpiaParser.parseSearch(novelpia, """{"status":500,"errmsg":"x"}""").isEmpty())
    }

    @Test
    fun novelpiaBrowseDeduplicatesMobileCopies() {
        val results = NovelpiaParser.parseBrowse(novelpia, fixture("novelpia/synthetic_browse.html"))
        assertEquals(listOf("자유연재 작품", "두 번째 작품"), results.map { it.name })
        assertEquals("https://novelpia.com/novel/460634", results[0].url)
        assertEquals("https://images.novelpia.com/imagebox/cover/f42f_ori.wimg", results[0].posterUrl)
    }

    @Test
    fun novelpiaNovelPage() {
        val doc = Jsoup.parse(fixture("novelpia/synthetic_novel.html"), "https://novelpia.com/novel/441569")
        val info = NovelpiaParser.parseNovelPage(doc)
        assertNotNull(info)
        info!!
        assertEquals("테스트 소설", info.title)
        assertEquals("테스트작가", info.author)
        assertEquals(listOf("판타지", "현대"), info.tags)
        assertEquals("줄거리 첫 줄.\n줄거리 둘째 줄.", info.synopsis)
        assertEquals("https://novelpia.com/imagebox/cover/aaaa_ori.file", info.cover)
        assertEquals(ReleaseStatus.Completed, info.status)
    }

    @Test
    fun novelpiaEpisodeListKeepsOnlyFreeEpisodes() {
        val page = NovelpiaParser.parseEpisodeList(novelpia, fixture("novelpia/synthetic_episode_list.html"), "441569")
        assertEquals(2, page.totalRows)
        assertEquals(4, page.lastPage)
        assertEquals(1, page.freeEpisodes.size)
        val ep = page.freeEpisodes[0]
        assertEquals("001 – 첫 번째 이야기", ep.name)
        assertEquals("https://novelpia.com/viewer/5873789", ep.url)
        assertEquals("26.07.19", ep.dateOfRelease)
    }

    @Test
    fun novelpiaViewerDataStripsCoverAndWatermark() {
        val html = NovelpiaParser.parseViewerData(fixture("novelpia/synthetic_viewer.json"))
        assertNotNull(html)
        html!!
        assertFalse(html.contains("cover"))
        assertFalse(html.contains("V0FURVJNQVJL"))
        val doc = Jsoup.parseBodyFragment(html)
        assertEquals(
            listOf("001 – 첫 번째 이야기", "첫 문장입니다.", "“대사 & 따옴표”", ""),
            doc.select("p").map { it.text() }
        )
        assertEquals("https://images.novelpia.com/imagebox/illust/x.file", doc.selectFirst("img")!!.attr("src"))
    }

    @Test
    fun novelpiaLockedEpisodeIsNull() {
        assertNull(NovelpiaParser.parseViewerData(""))
        assertNull(NovelpiaParser.parseViewerData("""{"s":[]}"""))
    }

    @Test
    fun novelpiaIdsFromUrls() {
        assertEquals("441569", NovelpiaParser.novelNoFromUrl("https://novelpia.com/novel/441569?sid=x"))
        assertEquals("5873789", NovelpiaParser.episodeNoFromUrl("https://novelpia.com/viewer/5873789"))
    }

    // ─── Biquge (69shuba) ─────────────────────────────────────────────────────────

    @Test
    fun biqugeChapterIsParagraphHtml() {
        val html = BiqugeProvider.parseChapterHtml(fixture("69shuba/synthetic_chapter.html"), "https://www.69shuba.com/txt/1/2")
        assertNotNull(html)
        assertEquals(
            listOf("第一段文字。", "第二段文字，", "“第三段对话！”", "第四段内容。"),
            paragraphs(html!!)
        )
        assertFalse("heading removed", html.contains("第1章"))
        assertFalse("ads removed", html.contains("loadAdv"))
    }
}
