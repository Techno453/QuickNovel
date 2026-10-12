package com.lagradost.quicknovel.translator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Novel Buddy title index: parser on synthetic fixtures, lookup with a fake fetcher. */
class NovelBuddyIndexTest {
    private fun fixture(name: String): String =
        NovelBuddyIndexTest::class.java.classLoader!!.getResourceAsStream("novelbuddy/$name")!!
            .readBytes().toString(Charsets.UTF_8)

    private val aboveTheSky get() = fixture("search_above_the_sky.json")
    private val lordOfTheMysteries get() = fixture("search_lord_of_the_mysteries.json")

    @Before
    fun clearCache() = NovelBuddyIndex.clearCache()

    @Test
    fun parsesHitsAndTheirAlternativeNames() {
        val hits = NovelBuddyParser.parseSearch(aboveTheSky)!!
        assertEquals(4, hits.size)
        assertEquals("Above The Sky", hits[0].title)
        assertTrue(hits[0].altNames.isEmpty())
        assertEquals("MTL-above the sky", hits[1].title)
        assertEquals("/mtl-above-the-sky", hits[1].url)
        assertEquals(listOf("高天之上"), hits[1].altNames)
        assertTrue(hits[1].isMtl)
        assertTrue(!hits[0].isMtl)
    }

    @Test
    fun namesComeOnlyFromEntriesNamedLikeTheQuery() {
        val hits = NovelBuddyParser.parseSearch(aboveTheSky)!!
        // The MTL entry of the same novel counts ("MTL-" ignored); the other MTL hit is another novel.
        assertEquals(listOf("高天之上"), NovelBuddyParser.namesFor("Above the sky", hits))
        assertEquals(listOf("高天之上"), NovelBuddyParser.namesFor("above the sky!", hits))
        assertTrue(NovelBuddyParser.namesFor("sky", hits).isEmpty())
        assertTrue(NovelBuddyParser.namesFor("Pivot of the Sky", hits).isEmpty())
    }

    @Test
    fun commaSeparatedAlternativeNamesAreSplitAndLatinOnesMatchTheQuery() {
        val hits = NovelBuddyParser.parseSearch(lordOfTheMysteries)!!
        assertEquals(listOf("Lord Of Enigma", "诡秘之主"), hits[0].altNames)
        assertEquals(listOf("诡秘之主"), NovelBuddyParser.namesFor("Lord of the Mysteries", hits))
        assertEquals(listOf("诡秘之主"), NovelBuddyParser.namesFor("lord of enigma", hits))
        // The sequel's names stay with the sequel; an entry without alternative names gives nothing.
        assertEquals(
            listOf("诡秘之主", "宿命之环", "诡秘之主2宿命之环"),
            NovelBuddyParser.namesFor("Lord of the Mysteries 2: Circle of Inevitability", hits),
        )
        assertTrue(NovelBuddyParser.namesFor("Lord of Mysteries 2: Circle of Inevitability", hits).isEmpty())
    }

    @Test
    fun humanTranslationEntriesComeBeforeMtlEntries() {
        val hits = listOf(
            NovelBuddyParser.Hit("MTL - Some Title", "/mtl", listOf("甲"), isMtl = true),
            NovelBuddyParser.Hit("Some Title", "/human", listOf("乙", "Some Title"), isMtl = false),
        )
        assertEquals(listOf("乙", "甲"), NovelBuddyParser.namesFor("some title", hits))
    }

    @Test
    fun anythingButASearchAnswerIsNull() {
        assertNull(NovelBuddyParser.parseSearch("<html><body>Just a moment...</body></html>"))
        assertNull(NovelBuddyParser.parseSearch("{\"success\":false,\"message\":\"nope\"}"))
        assertNull(NovelBuddyParser.parseSearch(""))
        assertEquals(emptyList<NovelBuddyParser.Hit>(), NovelBuddyParser.parseSearch("{\"data\":{\"items\":[]}}"))
    }

    // ---- lookup with a fake fetcher ----

    private class FakeFetcher(val page: (String) -> NovelUpdatesIndex.Page) : NovelBuddyIndex.Fetcher {
        val urls = ArrayList<String>()
        override suspend fun get(url: String): NovelUpdatesIndex.Page {
            urls += url
            return page(url)
        }
    }

    private fun ok(body: String) = NovelUpdatesIndex.Page(200, { null }, body)

    @Test
    fun lookupReturnsTheNamesFromOneRequest() = runBlocking {
        val fetcher = FakeFetcher { ok(aboveTheSky) }
        val result = NovelBuddyIndex.lookup("Above the sky", fetcher)
        assertNull(result.failure)
        assertEquals(listOf("高天之上"), result.names)
        assertEquals(listOf("https://api.novelbuddy.me/titles/search?page=1&limit=10&q=Above+the+sky"), fetcher.urls)
    }

    @Test
    fun blocksErrorsAndNonAnswersGiveUpQuietly() = runBlocking {
        assertEquals("HTTP 500", NovelBuddyIndex.lookup("x", FakeFetcher { NovelUpdatesIndex.Page(500, { null }, "") }).failure)
        assertEquals("parse", NovelBuddyIndex.lookup("x", FakeFetcher { ok("<html>maintenance</html>") }).failure)
        val banned = NovelUpdatesIndex.Page(
            403,
            { if (it.equals("server", true)) "cloudflare" else null },
            "<title>Attention Required! | Cloudflare</title><h1>Sorry, you have been blocked</h1>",
        )
        assertEquals("CLOUDFLARE_BANNED", NovelBuddyIndex.lookup("x", FakeFetcher { banned }).failure)
        val result = NovelBuddyIndex.lookup("x", NovelBuddyIndex.Fetcher { throw IOException("offline") })
        assertTrue(result.names.isEmpty())
        assertEquals("error: IOException", result.failure)
    }

    @Test
    fun associatedNamesCachesAndSkipsCjkQueries() = runBlocking {
        val fetcher = FakeFetcher { ok(aboveTheSky) }
        assertEquals(listOf("高天之上"), NovelBuddyIndex.associatedNames("Above the sky", fetcher))
        assertEquals(listOf("高天之上"), NovelBuddyIndex.associatedNames("above the sky ", fetcher))
        assertEquals(1, fetcher.urls.size)
        assertTrue(NovelBuddyIndex.associatedNames("高天之上", fetcher).isEmpty())
        assertEquals(1, fetcher.urls.size)
    }
}
