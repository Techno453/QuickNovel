package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SyosetuProvider] against pages recorded from yomou/ncode.syosetu.com (2026-10). */
class SyosetuFixtureTest {
    private val api = SyosetuProvider()
    private val novelUrl = "https://ncode.syosetu.com/n9669bk/"

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
    fun lastTableOfContentsPage() {
        assertEquals("https://ncode.syosetu.com/n9669bk/?p=3", api.lastPageUrl(document("syosetu/novel.html")))
        assertNull(api.lastPageUrl(document("syosetu/novel_last_page.html").also {
            it.select(".c-pager__item--last").remove()
        }))
    }

    @Test
    fun novelPageCountsEpisodesFromLastPage() = runBlocking {
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl, document("syosetu/novel_last_page.html"))
        assertEquals("無職転生　- 異世界行ったら本気だす -", load.name)
        assertEquals("理不尽な孫の手", load.author)
        assertEquals(novelUrl, load.url)
        assertTrue(!load.synopsis.isNullOrBlank())

        val chapters = load.data
        assertEquals(286, chapters.size)
        assertEquals("Episode 1", chapters.first().name)
        assertEquals("https://ncode.syosetu.com/n9669bk/1/", chapters.first().url)
        assertEquals("Episode 286", chapters.last().name)
        assertEquals("https://ncode.syosetu.com/n9669bk/286/", chapters.last().url)
    }

    @Test
    fun novelPageWithoutPagerUsesFirstPage() = runBlocking {
        // Only the first 100 episodes are listed on page 1.
        val load = api.parseLoad(document("syosetu/novel.html"), novelUrl)
        assertEquals(100, load.data.size)
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
