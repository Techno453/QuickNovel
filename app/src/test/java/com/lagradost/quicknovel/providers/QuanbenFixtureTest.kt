package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ReleaseStatus
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.providers.ProviderFixtures.assertCleanChapter
import com.lagradost.quicknovel.providers.ProviderFixtures.document
import com.lagradost.quicknovel.providers.ProviderFixtures.paragraphs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [QuanbenProvider] against pages recorded from quanben.io (2026-10). */
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
    fun browseHomeAndPagedGenreListings() {
        // Default listing: the home page's recommendations (one page only).
        assertEquals("", api.tags.first().second)
        assertEquals("https://www.quanben.io", api.mainPageUrl(1, ""))
        val home = api.parseNovels(document("quanben/home.html"))
        assertEquals(18, home.size) // the 18 推荐小说 entries with covers
        assertTrue(home.all { it.url.matches(Regex("""https://www\.quanben\.io/n/[^/]+/""")) })

        // Genre listings page: /c/xuanhuan.html, then /c/xuanhuan_2.html.
        assertEquals("https://www.quanben.io/c/xuanhuan.html", api.mainPageUrl(1, "xuanhuan"))
        assertEquals("https://www.quanben.io/c/xuanhuan_2.html", api.mainPageUrl(2, "xuanhuan"))
        val page1 = api.parseNovels(document("quanben/category.html"))
        val page2 = api.parseNovels(document("quanben/category_page2.html"))
        assertEquals(12, page1.size)
        assertEquals(12, page2.size)
        assertEquals("傲世丹神", page1.first().name)
        assertEquals("https://www.quanben.io/n/aoshidanshen/", page1.first().url)
        assertEquals("https://img.c0m.io/quanben.io/upload/thumbnail/book_0_999/book_1.jpg", page1.first().posterUrl)
        assertTrue(page1.map { it.url }.intersect(page2.map { it.url }.toSet()).isEmpty())
        for ((label, _) in api.tags) assertTrue(label, label.first() in 'A'..'Z')
    }

    @Test
    fun novelPageWithFullAmpChapterList() = runBlocking {
        val load = api.parseLoad(novelUrl, document("quanben/novel.html"), document("quanben/amp_list.html"))
            as StreamResponse
        assertEquals("斗罗大陆", load.name)
        assertEquals("唐家三少", load.author)
        assertEquals("https://img.c0m.io/quanben.io/upload/thumbnail/book_0_999/book_12.jpg", load.posterUrl)
        assertEquals(listOf("玄幻小说"), load.tags)
        assertEquals(ReleaseStatus.Completed, load.status) // og:novel:status 完结
        assertTrue(!load.synopsis.isNullOrBlank())

        // Names and URLs come from the same <a> of the AMP list, which has all 688 chapters.
        // The old quanben5.com mirror had 610 names that were paired with numbered quanben.io
        // URLs, so every name after the first was attached to the wrong chapter.
        val chapters = load.data
        assertEquals(688, chapters.size)
        assertEquals("引子穿越的唐家三少", chapters.first().name)
        assertEquals("https://www.quanben.io/n/douluodalu/1.html", chapters.first().url)
        assertEquals("第1章 斗罗大陆，异界唐三(一)", chapters[1].name)
        assertEquals("https://www.quanben.io/n/douluodalu/2.html", chapters[1].url)
        assertEquals("第236章 大结局，最后一个条件", chapters.last().name)
        assertEquals("https://www.quanben.io/n/douluodalu/688.html", chapters.last().url)
        // Every chapter n sits at index n-1: names and URLs cannot drift apart.
        chapters.forEachIndexed { i, ch ->
            assertEquals("https://www.quanben.io/n/douluodalu/${i + 1}.html", ch.url)
        }
    }

    @Test
    fun ampListLinksMatchTheRegularListPreview() {
        // list.html shows the first 24 and last 23 chapters (the middle loads through a signed
        // JSONP call). The same chapters must have the same name and URL in the AMP list.
        val amp = api.parseChapterList(document("quanben/amp_list.html")).toMap()
        val preview = document("quanben/list.html").select("ul.list3 li a[href]").map {
            it.text().trim() to "https://www.quanben.io" + it.attr("href")
        }
        assertEquals(47, preview.size)
        val byUrl = amp.entries.associate { (name, url) -> url to name }
        for ((name, url) in preview) assertEquals(url, name, byUrl[url])
    }

    @Test
    fun collapsedRegularListIsNotUsedAsAChapterList() {
        // A partial list would silently skip chapters 25..665.
        assertTrue(api.parseChapterList(document("quanben/list.html")).isEmpty())
        assertEquals("https://www.quanben.io/amp/n/douluodalu/list.html", api.ampListUrl("douluodalu"))
    }

    @Test
    fun novelPageWithoutChapterListHasNoChapters() = runBlocking {
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
