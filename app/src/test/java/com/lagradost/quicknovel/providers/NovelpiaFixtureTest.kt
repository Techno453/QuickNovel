package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import com.lagradost.quicknovel.providers.ProviderFixtures.text
import com.lagradost.quicknovel.providers.korean.NovelpiaParser
import com.lagradost.quicknovel.providers.korean.NovelpiaProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [NovelpiaProvider] against responses recorded from novelpia.com (2026-10, logged out). */
class NovelpiaFixtureTest {
    private val api = NovelpiaProvider()
    private val novelNo = "26536"

    @Test
    fun searchJson() {
        val results = NovelpiaParser.parseSearch(api, text("novelpia/search.json"))
        assertEquals(8, results.size)
        assertEquals("천하제일인의 소꿉친구", results[0].name)
        assertEquals("https://novelpia.com/novel/45208", results[0].url)
        assertEquals(
            "https://images.novelpia.com/imagebox/cover/ac37ba996ed3d1939760ba9bbb6af342_108658_ori.wimg",
            results[0].posterUrl
        )
        assertTrue(results.all { it.posterUrl!!.startsWith("https://") })
    }

    @Test
    fun novelPage() {
        val info = NovelpiaParser.parseNovelPage(document("novelpia/novel.html", "https://novelpia.com/novel/$novelNo"))
        assertNotNull(info)
        info!!
        assertEquals("주술사는 초월을 원한다", info.title)
        assertEquals("노백곰", info.author)
        assertEquals(ReleaseStatus.Ongoing, info.status)
        assertEquals("https://novelpia.com/imagebox/cover/5c0ba9b54c65e4fecde72dd049c975a1_489600_ori.file", info.cover)
        assertEquals(listOf("현대판타지", "판타지", "현대", "아포칼립스", "회귀"), info.tags.take(5))
        // The synopsis keeps its <br> line break.
        assertEquals(2, info.synopsis!!.lines().size)
    }

    @Test
    fun episodeListKeepsFreeEpisodesInOrder() {
        val page = NovelpiaParser.parseEpisodeList(api, text("novelpia/episode_list.html"), novelNo)
        assertEquals(20, page.totalRows)
        assertEquals(56, page.lastPage)
        // A PLUS work: EP.1-15 are free, EP.16-20 on this page are locked and left out.
        assertEquals(15, page.freeEpisodes.size)
        val first = page.freeEpisodes.first()
        assertEquals("소신공양", first.name)
        assertEquals("https://novelpia.com/viewer/308854", first.url)
        assertEquals("21.06.25", first.dateOfRelease)
        assertTrue(page.freeEpisodes.all { it.url.matches(Regex("""https://novelpia\.com/viewer/\d+""")) })
    }

    @Test
    fun viewerDataToParagraphs() {
        val html = NovelpiaParser.parseViewerData(text("novelpia/viewer.json"))
        assertCleanChapter(html, minParagraphs = 50, extraJunk = listOf("cover-wrapper", "cover-img", "&nbsp;"))
        html!!
        // The cover image block at the top of every episode is stripped; empty spacer lines too.
        assertTrue(paragraphs(html).none { it.isBlank() })
        assertTrue(html.contains("</p>\n\n<p>"))
    }
}
