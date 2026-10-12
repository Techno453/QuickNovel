package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SyosetuProvider] against pages recorded from yomou/ncode.syosetu.com (2026-10): the three
 * table-of-contents pages of n9669bk (`novel.html` = ?p=1, `novel_page2.html`,
 * `novel_last_page.html` = ?p=3), its info page (`infotop.html`, 完結済) and the info page of a
 * serialized work (`infotop_serialized.html`, n3159fs, 連載中).
 */
class SyosetuFixtureTest {
    private val api = SyosetuProvider()
    private val novelUrl = "https://ncode.syosetu.com/n9669bk/"

    private fun allTocPages() = listOf(
        document("syosetu/novel.html"),
        document("syosetu/novel_page2.html"),
        document("syosetu/novel_last_page.html"),
    )

    @Test
    fun searchResults() {
        val results = api.parseSearch(document("syosetu/search.html"))
        assertEquals(5, results.size)
        assertEquals("無職転生　- 異世界行ったら本気だす -", results[0].name)
        assertEquals(novelUrl, results[0].url)
        assertEquals("転生したらスライムだった件", results[1].name)
        assertEquals("https://ncode.syosetu.com/n6316bn/", results[1].url)
        // Syosetu has no covers.
        assertTrue(results.all { it.posterUrl.isNullOrEmpty() })
    }

    @Test
    fun browseUrls() {
        // Default: the all-genre (総合) all-time ranking.
        assertEquals("https://yomou.syosetu.com/rank/list/type/total_total/?p=1", api.mainPageUrl(1, null, null, null))
        assertEquals(
            "https://yomou.syosetu.com/rank/list/type/total_total/?p=1",
            api.mainPageUrl(1, api.mainCategories.first().second, api.orderBys.first().second, api.tags.first().second),
        )
        assertEquals("https://yomou.syosetu.com/rank/genrelist/type/daily_201_er/?p=2", api.mainPageUrl(2, "201", "daily", "er"))
        assertEquals("https://yomou.syosetu.com/rank/isekailist/type/weekly_2/?p=1", api.mainPageUrl(1, "i2", "weekly", ""))
        assertEquals("https://yomou.syosetu.com/rank/isekailist/type/yearly_o_r/?p=3", api.mainPageUrl(3, "io", "yearly", "r"))
        assertEquals("https://yomou.syosetu.com/rank/list/type/monthly_total_t/?p=1", api.mainPageUrl(1, "total", "monthly", "t"))
        // Latest updates: the search sorted by last update.
        val latest = SyosetuProvider.LATEST
        assertEquals("https://yomou.syosetu.com/search.php?order=new&p=2", api.mainPageUrl(2, "total", latest, ""))
        assertEquals("https://yomou.syosetu.com/search.php?order=new&genre=201&type=er&p=1", api.mainPageUrl(1, "201", latest, "er"))
        assertEquals("https://yomou.syosetu.com/search.php?order=new&istt=1&biggenre=2&p=1", api.mainPageUrl(1, "i2", latest, null))
        for ((label, _) in api.orderBys + api.mainCategories + api.tags) assertTrue(label, label.first() in 'A'..'Z')
        assertTrue(api.tags.any { it.second == "er" && it.first.startsWith("Completed") })
    }

    @Test
    fun rankingPagesParseAndPage2Differs() {
        // Recorded from /rank/list/type/total_total/?p=1 and ?p=2 (2026-10-11), five entries each.
        val page1 = api.parseRanking(document("syosetu/rank.html"))
        val page2 = api.parseRanking(document("syosetu/rank_page2.html"))
        assertEquals(5, page1.size)
        assertEquals(5, page2.size)
        assertEquals("とんでもスキルで異世界放浪メシ", page1.first().name)
        assertEquals("https://ncode.syosetu.com/n2710db/", page1.first().url)
        assertEquals("https://ncode.syosetu.com/n3507ho/", page2.first().url)
        assertTrue(page1.map { it.url }.intersect(page2.map { it.url }.toSet()).isEmpty())
    }

    @Test
    fun oneShotIsASingleChapterOnTheWorkPage() = runBlocking {
        val url = "https://ncode.syosetu.com/n7399mv/"
        val doc = document("syosetu/oneshot.html")
        val load = api.parseLoad(doc, url, listOf(doc), null)
        assertEquals("男友達のようなものには女友達のようなものをぶつけるんだよ！", load.name)
        assertEquals(1, load.data.size)
        assertEquals(load.name, load.data.single().name)
        assertEquals(url, load.data.single().url)
        // The chapter text comes from the same page.
        assertTrue(paragraphs(api.parseChapter(doc)!!).size > 20)
    }

    @Test
    fun tableOfContentsPagination() {
        assertEquals(3, api.lastPageNumber(document("syosetu/novel.html")))
        assertEquals(3, api.lastPageNumber(document("syosetu/novel_page2.html")))
        // On the last page "最後へ" is a <span> without a link.
        assertEquals(1, api.lastPageNumber(document("syosetu/novel_last_page.html")))
        assertEquals("https://ncode.syosetu.com/n9669bk/?p=3", api.lastPageUrl(document("syosetu/novel.html")))
        assertNull(api.lastPageUrl(document("syosetu/novel_last_page.html")))
        assertEquals("https://ncode.syosetu.com/n9669bk/?p=2", api.tocPageUrl(novelUrl, 2))
        assertEquals("https://ncode.syosetu.com/n9669bk/?p=2", api.tocPageUrl("https://ncode.syosetu.com/n9669bk", 2))
        assertEquals("https://ncode.syosetu.com/novelview/infotop/ncode/n9669bk/", api.infoUrl(novelUrl))
    }

    @Test
    fun novelPageHasRealEpisodeTitlesFromEveryPage() = runBlocking {
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl, allTocPages(), document("syosetu/infotop.html"))
        assertEquals("無職転生　- 異世界行ったら本気だす -", load.name)
        assertEquals("理不尽な孫の手", load.author)
        assertEquals(novelUrl, load.url)
        assertTrue(!load.synopsis.isNullOrBlank())

        val chapters = load.data
        assertEquals(286, chapters.size)
        assertEquals("プロローグ", chapters.first().name)
        assertEquals("https://ncode.syosetu.com/n9669bk/1/", chapters.first().url)
        assertEquals("第九十話「最後の一押し」", chapters[99].name)
        assertEquals("https://ncode.syosetu.com/n9669bk/100/", chapters[99].url)
        assertEquals("第九十一話「後ろ盾」", chapters[100].name)
        assertEquals("https://ncode.syosetu.com/n9669bk/101/", chapters[100].url)
        assertEquals("エピローグ「プロローグ・ゼロ」", chapters.last().name)
        assertEquals("https://ncode.syosetu.com/n9669bk/286/", chapters.last().url)
        chapters.forEachIndexed { i, ch ->
            assertEquals("https://ncode.syosetu.com/n9669bk/${i + 1}/", ch.url)
            assertTrue(ch.name, !ch.name.startsWith("Episode "))
        }
    }

    @Test
    fun completedWorkStatusAndTagsFromInfoPage() = runBlocking {
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl, allTocPages(), document("syosetu/infotop.html"))
        // Was Ongoing for every work: the novel page has no type label any more.
        assertEquals(ReleaseStatus.Completed, load.status)
        assertEquals(listOf("ハイファンタジー〔ファンタジー〕", "R15", "残酷な描写あり", "異世界転生"), load.tags)
    }

    @Test
    fun serializedWorkStatusAndTags() {
        val info = document("syosetu/infotop_serialized.html")
        assertEquals(ReleaseStatus.Ongoing, api.parseStatus(info))
        val tags = api.parseTags(info)
        assertEquals("ハイファンタジー〔ファンタジー〕", tags.first())
        // &nbsp; and plain spaces both separate keywords.
        assertEquals(listOf("残酷な描写あり", "シリアス", "女主人公"), tags.subList(1, 4))
        assertEquals("アニメ化決定", tags.last())
        assertTrue(tags.none { it.isBlank() || it.contains(' ') || it.contains(' ') })
    }

    @Test
    fun oneShotCountsAsCompleted() {
        val info = Jsoup.parse(
            """<div class="p-infotop-type"><div class="p-infotop-type__left">
              |<span class="p-infotop-type__type p-infotop-type__type--short">短編</span></div></div>""".trimMargin()
        )
        assertEquals(ReleaseStatus.Completed, api.parseStatus(info))
    }

    @Test
    fun missingMiddlePageKeepsNumberingWithPlaceholders() = runBlocking {
        val pages = listOf(document("syosetu/novel.html"), null, document("syosetu/novel_last_page.html"))
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl, pages, null)
        val chapters = load.data
        assertEquals(286, chapters.size)
        assertEquals("Episode 101", chapters[100].name)
        assertEquals("https://ncode.syosetu.com/n9669bk/101/", chapters[100].url)
        assertEquals("第百八十四話「アリエルの戦場」", chapters[200].name)
        assertEquals("https://ncode.syosetu.com/n9669bk/201/", chapters[200].url)
        // Without the info page the status is unknown rather than a wrong "Ongoing".
        assertNull(load.status)
    }

    @Test
    fun novelPageWithoutOtherPagesUsesFirstPage() = runBlocking {
        // Only the first 100 episodes are listed on page 1.
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl)
        assertEquals(100, load.data.size)
        assertEquals("プロローグ", load.data.first().name)
    }

    @Test
    fun chapterKeepsOneParagraphPerLine() {
        val html = api.parseChapter(document("syosetu/chapter.html"))
        assertCleanChapter(html, minParagraphs = 150, extraJunk = listOf("p-novel__number", "c-ad", "2/286"))
        // Every source line stays a <p>, blank spacer lines included, so the reader keeps the
        // author's line breaks.
        val paras = paragraphs(html!!)
        assertEquals(296, paras.size)
        assertTrue(Jsoup.parseBodyFragment(html).selectFirst("p#L5 > br") != null)
    }
}
