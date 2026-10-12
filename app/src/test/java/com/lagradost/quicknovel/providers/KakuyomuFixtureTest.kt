package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import com.lagradost.quicknovel.providers.ProviderFixtures.text
import com.lagradost.quicknovel.providers.japanese.KakuyomuParser
import com.lagradost.quicknovel.providers.japanese.KakuyomuProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KakuyomuProvider] against pages recorded from kakuyomu.jp (2026-10). The Next.js Apollo
 * state is pruned to the entries the parser reads (see the fixture README).
 */
class KakuyomuFixtureTest {
    private val api = KakuyomuProvider()
    private val workId = "16818093087479543721"
    private val workUrl = "https://kakuyomu.jp/works/$workId"

    @Test
    fun searchResultsInRankOrder() {
        val results = KakuyomuParser.parseSearch(api, text("kakuyomu/search.html"))
        assertEquals(20, results.size)
        assertEquals("極悪貴族、謙虚堅実に無双する～原作知識と固有魔法＜虚空＞を駆使して、破滅エンドを回避します～", results[0].name)
        assertEquals(workUrl, results[0].url)
        assertEquals("Episode 174", results[0].latestChapter)
        assertEquals("https://kakuyomu.jp/works/16816700427572694145", results[1].url)
        assertTrue(results.all { it.url.matches(Regex("""https://kakuyomu\.jp/works/\d+""")) })
    }

    @Test
    fun workTableOfContents() {
        val work = KakuyomuParser.parseWork(api, text("kakuyomu/work.html"), workId)
        assertNotNull(work)
        work!!
        assertEquals("極悪貴族、謙虚堅実に無双する～原作知識と固有魔法＜虚空＞を駆使して、破滅エンドを回避します～", work.title)
        assertEquals("月島秀一", work.author)
        assertEquals(ReleaseStatus.Ongoing, work.status)
        assertEquals("https://cdn.kdkw.jp/cover_1000/322601/322601000567.webp", work.posterUrl)
        assertEquals(8, work.tags.size)
        assertEquals("異世界転生", work.tags.first())
        assertEquals(37981146, work.views)
        assertTrue(!work.synopsis.isNullOrBlank())

        assertEquals(174, work.chapters.size)
        assertEquals("第一章 - 第1話：悪役転生", work.chapters.first().name)
        assertEquals("$workUrl/episodes/16818093087479703833", work.chapters.first().url)
        assertEquals("2024-10-27", work.chapters.first().dateOfRelease)
        assertEquals("第六章 - 第8話：誤算", work.chapters.last().name)
        assertEquals("$workUrl/episodes/2912051608032190077", work.chapters.last().url)
    }

    @Test
    fun episodeBodyParagraphsWithRubyFlattened() {
        val url = "$workUrl/episodes/16818093087479703833"
        val html = KakuyomuParser.parseEpisode(text("kakuyomu/episode.html"), url)
        assertCleanChapter(html, minParagraphs = 50, extraJunk = listOf("<ruby", "<rt", "widget-episodeTitle"))
        html!!
        // Blank spacer lines (<p class="blank"><br/></p>) are dropped; paragraphs are separated by
        // a blank line for plain-text consumers.
        assertTrue(paragraphs(html).none { it.isBlank() })
        assertTrue(html.contains("</p>\n\n<p>"))
        // Hiragana furigana and emphasis dots are dropped; katakana glosses are kept as base《reading》.
        // (Prose is placeholder text: kanji -> 甲乙丙…, hiragana -> あいう…, katakana -> アイウ….)
        assertTrue(paragraphs(html).contains("乙丙え戊か甲あいう丁戊か、辛壬こ甲乙う丁お己きく。"))
        assertTrue(html.contains("《アイウ"))
        assertFalse(html.contains("《あ") || html.contains("《・"))
    }
}
