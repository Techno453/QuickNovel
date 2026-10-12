package com.lagradost.quicknovel.translator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NovelUpdates title index: parser on synthetic fixtures, lookup with a fake fetcher. */
class NovelUpdatesIndexTest {
    private fun fixture(name: String): String =
        NovelUpdatesIndexTest::class.java.classLoader!!.getResourceAsStream("novelupdates/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private val searchHtml get() = fixture("search_above_the_sky.html")
    private val seriesHtml get() = fixture("series_above_the_sky.html")

    @Test
    fun parsesSearchHitsInOrderWithoutDuplicates() {
        val hits = NovelUpdatesParser.parseSearch(searchHtml)
        assertEquals(listOf("Sword Above the Sky", "Above The Sky"), hits.map { it.title })
        assertEquals("https://www.novelupdates.com/series/above-the-sky/", hits[1].url)
    }

    @Test
    fun exactTitleMatchIsOpenedFirst() {
        val ranked = NovelUpdatesParser.rankHits("above the sky", NovelUpdatesParser.parseSearch(searchHtml))
        assertEquals("Above The Sky", ranked.first().title)
    }

    @Test
    fun parsesAssociatedNames() {
        val page = NovelUpdatesParser.parseSeries(seriesHtml)
        assertNotNull(page)
        assertEquals("Above The Sky", page!!.title)
        assertEquals(
            listOf("高天之上", "Gao Tian Zhi Shang", "Above the Heavens", "높은 하늘 위"),
            page.associatedNames,
        )
    }

    @Test
    fun nonSeriesPageIsNull() {
        assertNull(NovelUpdatesParser.parseSeries(searchHtml))
    }

    @Test
    fun scriptLanguages() {
        assertEquals(setOf("zh", "ja"), NovelUpdatesParser.languagesOf("高天之上"))
        assertEquals(setOf("ja"), NovelUpdatesParser.languagesOf("転生したらスライムだった件"))
        assertEquals(setOf("ko"), NovelUpdatesParser.languagesOf("전지적 독자 시점"))
        assertTrue(NovelUpdatesParser.languagesOf("Gao Tian Zhi Shang").isEmpty())
    }

    @Test
    fun namesPerLanguage() {
        val names = listOf("高天之上", "Gao Tian Zhi Shang", "無職転生", "無職転生 ～異世界行ったら本気だす～", "높은 하늘 위")
        assertEquals(listOf("高天之上", "無職転生"), NovelUpdatesParser.namesForLanguage(names, "zh").take(2))
        // Japanese: names with kana first, then Han-only ones.
        assertEquals("無職転生 ～異世界行ったら本気だす～", NovelUpdatesParser.namesForLanguage(names, "ja").first())
        assertEquals(listOf("높은 하늘 위"), NovelUpdatesParser.namesForLanguage(names, "ko"))
        assertEquals(3, NovelUpdatesParser.namesForLanguage(names, "ja").size)
        assertTrue(NovelUpdatesParser.namesForLanguage(names, "en").isEmpty())
    }

    @Test
    fun onlyLatinQueriesAreLookedUp() {
        assertTrue(NovelUpdatesParser.isLatinQuery("Above the sky"))
        assertFalse(NovelUpdatesParser.isLatinQuery("高天之上"))
        assertFalse(NovelUpdatesParser.isLatinQuery("转生 noble"))
        assertFalse(NovelUpdatesParser.isLatinQuery("1984"))
    }

    // ---- lookup with a fake fetcher ----

    private class FakeFetcher(val pages: (String) -> NovelUpdatesIndex.Page) : NovelUpdatesIndex.Fetcher {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): NovelUpdatesIndex.Page {
            urls += url
            return pages(url)
        }
    }

    private fun ok(body: String) = NovelUpdatesIndex.Page(200, { null }, body)

    @Test
    fun lookupReturnsTheCjkAssociatedNames() = runBlocking {
        val fetcher = FakeFetcher { url -> if ("/series/" in url) ok(seriesHtml) else ok(searchHtml) }
        val result = NovelUpdatesIndex.lookup("Above the sky", fetcher)
        assertNull(result.failure)
        assertEquals(listOf("高天之上", "높은 하늘 위"), result.names)
        // Search page + the exact match's series page; stops once it has names.
        assertEquals(2, fetcher.urls.size)
        assertTrue(fetcher.urls[0].startsWith("https://www.novelupdates.com/?s=Above+the+sky"))
        assertEquals("https://www.novelupdates.com/series/above-the-sky/", fetcher.urls[1])
    }

    @Test
    fun lookupNeverFetchesMoreThanThreePages() = runBlocking {
        // Series pages without CJK names: it keeps looking, but within the fetch budget.
        val noNames = "<div class=\"seriestitlenu\">X</div><div id=\"editassociated\">Only Latin<br></div>"
        val search = (1..6).joinToString("") {
            "<div class=\"search_main_box_nu\"><div class=\"search_title\"><a href=\"https://www.novelupdates.com/series/s$it/\">S$it</a></div></div>"
        }
        val fetcher = FakeFetcher { url -> if ("/series/" in url) ok(noNames) else ok(search) }
        val result = NovelUpdatesIndex.lookup("something", fetcher)
        assertTrue(result.names.isEmpty())
        assertEquals(NovelUpdatesIndex.MAX_FETCHES, fetcher.urls.size)
    }

    @Test
    fun firewallBlockGivesUpQuietly() = runBlocking {
        val blocked = NovelUpdatesIndex.Page(
            403,
            { if (it.equals("server", true)) "cloudflare" else null },
            "<title>Attention Required! | Cloudflare</title><h1>Sorry, you have been blocked</h1>",
        )
        val fetcher = FakeFetcher { blocked }
        val result = NovelUpdatesIndex.lookup("Above the sky", fetcher)
        assertTrue(result.names.isEmpty())
        assertEquals("CLOUDFLARE_BANNED", result.failure)
        assertEquals(1, fetcher.urls.size)
    }

    @Test
    fun challengeAndErrorsGiveUpQuietly() = runBlocking {
        val challenge = NovelUpdatesIndex.Page(403, { if (it.equals("cf-mitigated", true)) "challenge" else null }, "<title>Just a moment...</title>")
        assertEquals("CLOUDFLARE_CHALLENGE", NovelUpdatesIndex.lookup("Above the sky", FakeFetcher { challenge }).failure)
        val throwing = NovelUpdatesIndex.Fetcher { throw java.io.IOException("offline") }
        val result = NovelUpdatesIndex.lookup("Above the sky", throwing)
        assertTrue(result.names.isEmpty())
        assertEquals("error: IOException", result.failure)
    }
}
