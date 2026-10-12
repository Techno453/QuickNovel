package com.lagradost.quicknovel.ui.browse

import com.lagradost.quicknovel.ui.browse.BrowseCatalog.ListingKind
import com.lagradost.quicknovel.ui.browse.BrowseCatalog.Listing
import com.lagradost.quicknovel.ui.browse.BrowseCatalog.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseLogicTest {
    // ---- BrowseCatalog ----

    @Test
    fun cjkGroupsComeFirstThenOtherSources() {
        val sources = listOf(
            Source("Royal", "en"), Source("Kakuyomu", "ja"), Source("Novelpia", "ko"),
            Source("Ixdzs", "zh"), Source("Syosetu", "ja"), Source("Ranobes", "ru"),
        )
        val groups = BrowseCatalog.group(sources)
        assertEquals(listOf("zh", "ja", "ko", null), groups.map { it.lang })
        assertEquals(listOf("Kakuyomu", "Syosetu"), groups[1].sources.map { it.name })
        assertEquals(listOf("Royal", "Ranobes"), groups.last().sources.map { it.name })
        assertTrue(groups.last().isOther)
    }

    @Test
    fun readingLanguagesFromTheWizardComeFirst() {
        val sources = listOf(Source("A", "zh"), Source("B", "ja"), Source("C", "ko"), Source("D", "en"))
        assertEquals(listOf("ko", "ja", "zh", null), BrowseCatalog.group(sources, listOf("ko-KR", "ja")).map { it.lang })
        assertEquals(listOf("en", "zh", "ja", "ko"), BrowseCatalog.group(sources, listOf("en")).map { it.lang })
    }

    @Test
    fun listingKindsMapOntoTheClosestListing() {
        val kakuyomu = Source(
            "Kakuyomu", "ja",
            mainCategories = listOf(
                "Weekly ranking", "All-time popular", "Completed: weekly ranking",
                "Completed: all-time popular", "Latest updates", "New works",
            ),
        )
        assertEquals(Listing(), BrowseCatalog.resolve(kakuyomu, ListingKind.POPULAR))
        assertEquals(Listing(category = 5), BrowseCatalog.resolve(kakuyomu, ListingKind.NEW))
        assertEquals(Listing(category = 2), BrowseCatalog.resolve(kakuyomu, ListingKind.COMPLETED))

        // "Recently completed" is not "new"; latest updates is the fallback for New.
        val alpha = Source("A", "ja", mainCategories = listOf("24-hour ranking", "Recently completed", "Latest updates"))
        assertEquals(Listing(category = 2), BrowseCatalog.resolve(alpha, ListingKind.NEW))
        assertEquals(Listing(category = 1), BrowseCatalog.resolve(alpha, ListingKind.COMPLETED))

        // Providers still using orderBys for the listing.
        val legacy = Source("L", "zh", orderBys = listOf("Popular", "Newest"))
        assertEquals(Listing(orderBy = 1), BrowseCatalog.resolve(legacy, ListingKind.NEW))
        assertNull("no equivalent: skipped", BrowseCatalog.resolve(legacy, ListingKind.COMPLETED))
    }

    // ---- CombinedBrowse ----

    private data class Book(val title: String, val source: String, val chapters: Int? = null, val author: String? = null)

    private fun feed(vararg sources: String) = CombinedBrowse<Book>(
        sources.toList(),
        title = { it.title },
        author = { it.author },
        chapters = { it.chapters },
    )

    private fun books(source: String, vararg titles: String) = titles.map { Book(it, source) }

    @Test
    fun interleavesByRank() {
        val f = feed("A", "B", "C")
        f.onPage("A", 1, books("A", "a1", "a2", "a3"))
        f.onPage("B", 1, books("B", "b1", "b2", "b3"))
        f.onPage("C", 1, books("C", "c1", "c2", "c3"))
        f.fill(6)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "c2"), f.feed.map { it.item.title })
    }

    @Test
    fun waitsForFirstPagesUnlessTheGracePeriodIsOver() {
        val f = feed("A", "B")
        f.onPage("A", 1, books("A", "a1", "a2"))
        assertEquals(0, f.fill(10))
        assertEquals(2, f.fill(10, allowPartial = true))
        // B answers late: it joins at the next round, nothing on screen moves.
        f.onPage("B", 1, books("B", "b1", "b2"))
        f.onPage("A", 2, emptyList())
        f.fill(10)
        assertEquals(listOf("a1", "a2", "b1", "b2"), f.feed.map { it.item.title })
    }

    @Test
    fun aFailingSourceDropsOutWithoutBlockingTheOthers() {
        val f = feed("A", "B", "C")
        f.onPage("A", 1, books("A", "a1", "a2"))
        f.onFailure("B")
        f.onPage("C", 1, books("C", "c1", "c2"))
        f.fill(10)
        assertEquals(listOf("a1", "c1", "a2", "c2"), f.feed.map { it.item.title })
        assertEquals(1, f.unavailable)
        assertEquals(CombinedBrowse.Status.FAILED, f.statusOf("B"))
        // Nobody else is waited for: the exhausted ready sources ask for their next page.
        assertEquals(listOf("A" to 2, "C" to 2), f.plan())
    }

    @Test
    fun paginationTopsUpOnlyExhaustedSourcesAndAppends() {
        val f = feed("A", "B")
        f.onPage("A", 1, books("A", "a1", "a2", "a3"))
        f.onPage("B", 1, books("B", "b1"))
        f.fill(10)
        // B ran out after rank 1; A still has items, so the feed stops and asks B for page 2.
        assertEquals(listOf("a1", "b1"), f.feed.map { it.item.title })
        assertEquals(listOf("B" to 2), f.plan())
        f.onFetchStarted("B")
        assertTrue("already fetching", f.plan().isEmpty())
        val before = f.feed.toList()
        f.onPage("B", 2, books("B", "b2", "b3"))
        f.fill(10)
        assertEquals(before, f.feed.take(2))
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3", "b3"), f.feed.map { it.item.title })
        // Both exhausted now: both get topped up.
        assertEquals(listOf("A" to 2, "B" to 3), f.plan())
        f.onPage("A", 2, emptyList())
        f.onPage("B", 3, emptyList())
        assertTrue(f.isComplete)
        assertEquals(0, f.fill(10))
    }

    @Test
    fun fillStopsAtTheTarget() {
        val f = feed("A", "B")
        f.onPage("A", 1, books("A", "a1", "a2", "a3"))
        f.onPage("B", 1, books("B", "b1", "b2", "b3"))
        assertEquals(4, f.fill(3)) // whole rounds only
        assertEquals(2, f.fill(6))
    }

    @Test
    fun duplicatesAreMergedKeepingTheCopyWithMostChapters() {
        val f = feed("Quanben", "Ixdzs", "Piaotia")
        f.onPage("Quanben", 1, listOf(Book("斗罗大陆", "Quanben", chapters = 300), Book("凡人修仙传", "Quanben")))
        f.onPage("Ixdzs", 1, listOf(Book("斗罗 大陆！", "Ixdzs", chapters = 336), Book("遮天", "Ixdzs")))
        f.onPage("Piaotia", 1, listOf(Book("《斗罗大陆》", "Piaotia"), Book("凡人修仙传", "Piaotia")))
        f.fill(10)
        assertEquals(listOf("斗罗 大陆！", "凡人修仙传", "遮天"), f.feed.map { it.item.title })
        val douluo = f.feed[0]
        assertEquals("Ixdzs", douluo.source)
        assertEquals(listOf("Quanben", "Piaotia"), douluo.alsoOn)
        assertEquals("Quanben", f.feed[1].source)
        assertEquals(listOf("Piaotia"), f.feed[1].alsoOn)
    }

    @Test
    fun differentAuthorsAreDifferentNovels() {
        val f = feed("A", "B")
        f.onPage("A", 1, listOf(Book("重生", "A", author = "甲")))
        f.onPage("B", 1, listOf(Book("重生", "B", author = "乙"), Book("其他", "B")))
        f.onPage("A", 2, emptyList())
        f.fill(10)
        assertEquals(3, f.feed.size)
        // Unknown author on one side: matched on the title alone.
        val g = feed("A", "B")
        g.onPage("A", 1, listOf(Book("重生", "A", author = "甲")))
        g.onPage("B", 1, listOf(Book("重生", "B")))
        g.fill(10)
        assertEquals(1, g.feed.size)
    }

    @Test
    fun foldMakesTraditionalAndSimplifiedTitlesMatch() {
        val f = CombinedBrowse<Book>(listOf("A", "B"), title = { it.title }, fold = { it.replace('陸', '陆').replace('鬥', '斗').replace('羅', '罗') })
        f.onPage("A", 1, listOf(Book("鬥羅大陸", "A")))
        f.onPage("B", 1, listOf(Book("斗罗大陆", "B")))
        f.fill(10)
        assertEquals(1, f.feed.size)
        assertEquals(listOf("B"), f.feed[0].alsoOn)
    }

    @Test
    fun normalisationAndChapterCounts() {
        assertEquals("abc123", CombinedBrowse.normalize(" A-b_C 12３！"))
        assertEquals(384, CombinedBrowse.chapterCount("총384회"))
        assertEquals(1200, CombinedBrowse.chapterCount("第1,200章"))
        assertNull(CombinedBrowse.chapterCount("最新章节"))
        assertFalse(feed("A").isComplete)
    }
}
