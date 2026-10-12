package com.lagradost.quicknovel.ui.browse

import com.lagradost.quicknovel.ui.browse.TagFilter.Summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagFilterTest {
    private val kakuyomu = listOf("All", "Fantasy (異世界ファンタジー)", "Modern fantasy (現代ファンタジー)", "SF (SF)", "Horror (ホラー)", "Mystery (ミステリー)")
    private val naver = listOf("Fantasy (판타지)", "Modern fantasy (현판)", "Martial arts (무협)")

    // ---- selection ----

    @Test
    fun firstTagIsNoFilterWhenAllOrEmptyValue() {
        assertTrue(TagFilter.firstIsNoFilter("All", "all"))
        assertTrue(TagFilter.firstIsNoFilter(" all ", "x"))
        assertTrue(TagFilter.firstIsNoFilter("Recommended (推荐小说)", ""))
        assertFalse(TagFilter.firstIsNoFilter("Fantasy (판타지)", "101"))
        assertFalse(TagFilter.firstIsNoFilter(null, null))
    }

    @Test
    fun normalizeSortsDropsInvalidAndTheNoFilterEntry() {
        assertEquals(listOf(2, 5), TagFilter.normalize(listOf(5, 2, 2, 0, 9, -1), kakuyomu.size, firstIsNoFilter = true))
        assertEquals(emptyList<Int>(), TagFilter.normalize(listOf(0), kakuyomu.size, firstIsNoFilter = true))
        // A site without an all-genre list keeps its default genre instead of nothing.
        assertEquals(listOf(0), TagFilter.normalize(emptyList(), naver.size, firstIsNoFilter = false))
        assertEquals(listOf(0, 2), TagFilter.normalize(listOf(2, 0), naver.size, firstIsNoFilter = false))
        assertEquals(emptyList<Int>(), TagFilter.normalize(listOf(3), 0, firstIsNoFilter = false))
    }

    @Test
    fun toggleAddsRemovesAndAllClears() {
        assertEquals(listOf(3), TagFilter.toggle(emptyList(), 3, firstIsNoFilter = true))
        assertEquals(listOf(1, 3), TagFilter.toggle(listOf(3), 1, firstIsNoFilter = true))
        assertEquals(listOf(3), TagFilter.toggle(listOf(1, 3), 1, firstIsNoFilter = true))
        assertEquals(emptyList<Int>(), TagFilter.toggle(listOf(1, 3), 0, firstIsNoFilter = true))
        // Without a no-filter entry, index 0 is an ordinary genre.
        assertEquals(listOf(0, 2), TagFilter.toggle(listOf(0), 2, firstIsNoFilter = false))
        assertEquals(listOf(2), TagFilter.toggle(listOf(0, 2), 0, firstIsNoFilter = false))
    }

    @Test
    fun atMostFourGenresAreLoaded() {
        assertEquals(4, TagFilter.MAX_LOADED)
        assertEquals(listOf(0), TagFilter.loaded(emptyList()))
        assertEquals(listOf(2, 5), TagFilter.loaded(listOf(5, 2)))
        assertEquals(listOf(1, 2, 3, 4), TagFilter.loaded(listOf(6, 5, 4, 3, 2, 1)))
        assertFalse(TagFilter.overCap(listOf(1, 2, 3, 4)))
        assertTrue(TagFilter.overCap(listOf(1, 2, 3, 4, 5)))
    }

    // ---- cache key ----

    @Test
    fun cacheKeyHoldsTheSortedTagSet() {
        assertEquals("2+5+7", TagFilter.tagSetKey(listOf(7, 2, 5, 2)))
        assertEquals("0", TagFilter.tagSetKey(emptyList()))
        assertEquals(
            TagFilter.pageKey("Kakuyomu", 1, 0, listOf(5, 2), 3),
            TagFilter.pageKey("Kakuyomu", 1, 0, listOf(2, 5), 3),
        )
        assertEquals("v1|Kakuyomu|1,0,2+5|3", TagFilter.pageKey("Kakuyomu", 1, 0, listOf(5, 2), 3))
        // Different combinations and pages are different entries.
        assertFalse(TagFilter.pageKey("K", 0, 0, listOf(2, 5), 1) == TagFilter.pageKey("K", 0, 0, listOf(2), 1))
        assertFalse(TagFilter.pageKey("K", 0, 0, listOf(2, 5), 1) == TagFilter.pageKey("K", 0, 0, listOf(2, 5), 2))
    }

    @Test
    fun singleTagKeyIsTheKeyBeforeMultiSelect() {
        // Pages cached on disk by earlier versions ("v1|api|category,order,tag|page") stay valid.
        assertEquals("v1|Novelpia|2,1,3|4", TagFilter.pageKey("Novelpia", 2, 1, listOf(3), 4))
    }

    // ---- merge ----

    private data class Item(val url: String)

    private fun items(vararg urls: String) = urls.map { Item(it) }

    @Test
    fun mergeInterleavesByRank() {
        val merged = TagFilter.merge(listOf(items("a1", "a2", "a3"), items("b1", "b2"), items("c1"))) { it.url }
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "a3"), merged.map { it.url })
    }

    @Test
    fun mergeDropsRepeatedUrlsKeepingTheHighestRank() {
        // A novel in two genres appears once, where it ranks first.
        val merged = TagFilter.merge(listOf(items("x", "a2", "s"), items("b1", "x", "s"))) { it.url }
        assertEquals(listOf("x", "b1", "a2", "s"), merged.map { it.url })
    }

    @Test
    fun mergeOfOneOrNoListing() {
        assertEquals(listOf("a", "b"), TagFilter.merge(listOf(items("a", "b", "a"))) { it.url }.map { it.url })
        assertTrue(TagFilter.merge(emptyList<List<Item>>()) { it.url }.isEmpty())
        assertEquals(listOf("b1"), TagFilter.merge(listOf(emptyList(), items("b1"))) { it.url }.map { it.url })
    }

    // ---- summary ----

    @Test
    fun summaryOfNoSelection() {
        assertEquals(Summary.All, TagFilter.summary(kakuyomu, emptyList(), firstIsNoFilter = true))
        assertEquals(
            Summary.Default("Recommended"),
            TagFilter.summary(listOf("Recommended (推荐小说)", "Xianxia (修真)"), emptyList(), firstIsNoFilter = true),
        )
    }

    @Test
    fun summaryNamesUpToTwoGenresWithoutTheOriginalName() {
        assertEquals(Summary.Names(listOf("Fantasy")), TagFilter.summary(kakuyomu, listOf(1), firstIsNoFilter = true))
        assertEquals(
            Summary.Names(listOf("Fantasy", "Horror")),
            TagFilter.summary(kakuyomu, listOf(1, 4), firstIsNoFilter = true),
        )
        // Naver's default genre is a real genre.
        assertEquals(Summary.Names(listOf("Fantasy")), TagFilter.summary(naver, listOf(0), firstIsNoFilter = false))
    }

    @Test
    fun summaryCountsThreeOrMore() {
        assertEquals(Summary.Count(3), TagFilter.summary(kakuyomu, listOf(1, 2, 3), firstIsNoFilter = true))
        assertEquals(Summary.Count(5), TagFilter.summary(kakuyomu, listOf(1, 2, 3, 4, 5), firstIsNoFilter = true))
    }

    @Test
    fun shortLabelDropsTheBracketedOriginal() {
        assertEquals("Martial arts", TagFilter.shortLabel("Martial arts (무협)"))
        assertEquals("SF", TagFilter.shortLabel("SF"))
        assertEquals("(BL)", TagFilter.shortLabel(" (BL)"))
    }

    // ---- remembered listing ----

    @Test
    fun rememberedListingRoundTripsAndReadsTheOldForm() {
        assertEquals("1,0,2+5", TagFilter.encodeListing(1, 0, listOf(5, 2)))
        assertEquals(Triple(1, 0, listOf(2, 5)), TagFilter.decodeListing("1,0,2+5"))
        assertEquals(Triple(0, 2, listOf(3)), TagFilter.decodeListing("0,2,3"))
        assertEquals(Triple(0, 0, listOf(0)), TagFilter.decodeListing(TagFilter.encodeListing(0, 0, emptyList())))
        assertNull(TagFilter.decodeListing(null))
        assertNull(TagFilter.decodeListing("1,2"))
        assertNull(TagFilter.decodeListing("1,2,x"))
        assertNull(TagFilter.decodeListing("1,2,3+"))
    }
}
