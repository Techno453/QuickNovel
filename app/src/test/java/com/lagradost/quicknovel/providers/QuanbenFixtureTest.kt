package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QuanbenProvider] against pages recorded from quanben.io / quanben5.com (2026-10). */
class QuanbenFixtureTest {
    private val api = QuanbenProvider()
    private val novelUrl = "https://www.quanben.io/n/douluodalu/"

    @Test
    fun searchResults() {
        val results = api.parseNovels(document("quanben/search.html"))
        assertEquals(5, results.size)
        val first = results[0]
        assertEquals("斗罗大陆", first.name)
        assertEquals(novelUrl, first.url)
        // Protocol-relative cover made absolute by fixUrlNull.
        assertEquals("https://img.c0m.io/quanben.io/upload/thumbnail/book_0_999/book_12.jpg", first.posterUrl)
        assertTrue(results.all { it.url.matches(Regex("""https://www\.quanben\.io/n/[^/]+/""")) })
    }

    @Test
    fun novelPageWithMirrorChapterList() = runBlocking {
        val load = api.parseLoad(novelUrl, document("quanben/novel.html"), document("quanben/mirror_list.html"))
            as StreamResponse
        assertEquals("斗罗大陆", load.name)
        assertEquals("唐家三少", load.author)
        assertEquals("https://img.c0m.io/quanben.io/upload/thumbnail/book_0_999/book_12.jpg", load.posterUrl)
        assertEquals(listOf("玄幻小说"), load.tags)
        assertTrue(!load.synopsis.isNullOrBlank())

        // Names come from the quanben5.com mirror, URLs are numbered on quanben.io in list order.
        val chapters = load.data
        assertEquals(610, chapters.size)
        assertEquals("作品相关 光之子外篇暗夜精灵（上）", chapters.first().name)
        assertEquals("https://www.quanben.io/n/douluodalu/1.html", chapters.first().url)
        assertEquals("第二百三十六章 大结局，最后一个条件（全书完）", chapters.last().name)
        assertEquals("https://www.quanben.io/n/douluodalu/610.html", chapters.last().url)
    }

    @Test
    fun novelPageWithoutMirrorHasNoChapters() = runBlocking {
        val load = api.parseLoad(novelUrl, document("quanben/novel.html"), null) as StreamResponse
        assertEquals("斗罗大陆", load.name)
        assertTrue(load.data.isEmpty())
    }

    @Test
    fun chapterText() {
        val html = api.parseChapter(document("quanben/chapter.html"))
        assertCleanChapter(html, minParagraphs = 30, extraJunk = listOf("list_page", "全本小说网"))
        val paras = paragraphs(html!!)
        // Content is the site's own <p> per line (the in-content heading line included).
        assertTrue(paras.all { it.isNotBlank() })
    }
}
