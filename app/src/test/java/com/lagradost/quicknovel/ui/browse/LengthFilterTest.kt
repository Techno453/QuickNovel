package com.lagradost.quicknovel.ui.browse

import com.lagradost.quicknovel.LengthRange
import com.lagradost.quicknovel.LengthUnit
import com.lagradost.quicknovel.NovelLength
import com.lagradost.quicknovel.ui.browse.BrowseRepository.diskKey
import com.lagradost.quicknovel.ui.browse.LengthFilter.Summary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LengthFilterTest {

    // ---- presets and ranges ----

    @Test
    fun presetsPerUnit() {
        assertEquals(
            listOf(LengthRange(null, 100), LengthRange(100, 500), LengthRange(500, 1500), LengthRange(1500, null)),
            LengthFilter.presets(LengthUnit.PAGES).map { it.range },
        )
        assertEquals(
            listOf(LengthRange(null, 100_000), LengthRange(100_000, 500_000), LengthRange(500_000, 2_000_000), LengthRange(2_000_000, null)),
            LengthFilter.presets(LengthUnit.CHARACTERS).map { it.range },
        )
        assertEquals(
            listOf(LengthRange(null, 50), LengthRange(50, 200), LengthRange(200, 1000), LengthRange(1000, null)),
            LengthFilter.presets(LengthUnit.CHAPTERS).map { it.range },
        )
        assertEquals(LengthFilter.PresetId.LONG, LengthFilter.presetOf(LengthUnit.PAGES, LengthRange(500, 1500)))
        assertNull(LengthFilter.presetOf(LengthUnit.PAGES, LengthRange(400, 1500)))
        assertNull(LengthFilter.presetOf(LengthUnit.PAGES, null))
    }

    @Test
    fun normalizeOpensNonPositiveEndsAndOrdersThem() {
        assertNull(LengthFilter.normalize(null))
        assertNull(LengthFilter.normalize(LengthRange(0, 0)))
        assertNull(LengthFilter.normalize(LengthRange(null, -5)))
        assertEquals(LengthRange(500, 1500), LengthFilter.normalize(LengthRange(1500, 500)))
        assertEquals(LengthRange(null, 100), LengthFilter.normalize(LengthRange(-1, 100)))
        assertEquals(LengthRange(200, null), LengthFilter.normalize(LengthRange(200, 0)))
    }

    @Test
    fun matchesIsInclusiveAndDropsItemsWithoutTheUnit() {
        val range = LengthRange(500, 1500)
        assertTrue(LengthFilter.matches(NovelLength(pages = 500), LengthUnit.PAGES, range))
        assertTrue(LengthFilter.matches(NovelLength(pages = 1500), LengthUnit.PAGES, range))
        assertFalse(LengthFilter.matches(NovelLength(pages = 1501), LengthUnit.PAGES, range))
        assertFalse(LengthFilter.matches(NovelLength(pages = 499), LengthUnit.PAGES, range))
        // No length at all, or only another unit: dropped while a filter is active.
        assertFalse(LengthFilter.matches(null, LengthUnit.PAGES, range))
        assertFalse(LengthFilter.matches(NovelLength(chapters = 900), LengthUnit.PAGES, range))
        // Approximate counts filter like exact ones.
        assertTrue(LengthFilter.matches(NovelLength(chapters = 1417, approximate = true), LengthUnit.CHAPTERS, LengthRange(1000, null)))
        // No range: everything passes.
        assertTrue(LengthFilter.matches(null, LengthUnit.PAGES, null))
    }

    @Test
    fun encodeAndDecodeTheStoredRange() {
        assertEquals("500-1500", LengthFilter.encode(LengthRange(500, 1500)))
        assertEquals("-1500", LengthFilter.encode(LengthRange(null, 1500)))
        assertEquals("500-", LengthFilter.encode(LengthRange(500, null)))
        assertEquals(LengthRange(500, 1500), LengthFilter.decode("500-1500"))
        assertEquals(LengthRange(null, 1500), LengthFilter.decode("-1500"))
        assertEquals(LengthRange(500, null), LengthFilter.decode("500-"))
        assertEquals(LengthRange(500, 1500), LengthFilter.decode("1500-500"))
        assertNull(LengthFilter.decode(null))
        assertNull(LengthFilter.decode("-"))
        assertNull(LengthFilter.decode("garbage"))
        assertNull(LengthFilter.decode("a-b"))
    }

    // ---- cache keys ----

    @Test
    fun cacheKeysWithoutARangeAreUnchanged() {
        assertEquals("", LengthFilter.keyPart(null))
        val plain = BrowseRepository.PageKey("Kakuyomu", 0, 0, listOf(2, 5), 1)
        // Exactly the key of the multi-genre version, so pages cached before stay valid.
        assertEquals("v1|Kakuyomu|0,0,2+5|1", plain.diskKey())
        assertEquals(TagFilter.pageKey("Kakuyomu", 0, 0, listOf(2, 5), 1), plain.diskKey())
        val single = BrowseRepository.PageKey("Royal Road", BrowseCatalog.Listing(0, 1, 3), 2)
        assertEquals("v1|Royal Road|0,1,3|2", single.diskKey())
        // A server-side range is part of the key.
        assertEquals(
            "v1|Royal Road|0,1,3|2|len=500-1500",
            BrowseRepository.PageKey("Royal Road", BrowseCatalog.Listing(0, 1, 3), 2, LengthRange(500, 1500)).diskKey(),
        )
        assertEquals(
            "v1|Syosetu|0,0,0|1|len=-100000",
            BrowseRepository.PageKey("Syosetu", 0, 0, listOf(0), 1, LengthRange(null, 100_000)).diskKey(),
        )
    }

    // ---- text ----

    @Test
    fun summaryFormatsEachUnit() {
        assertEquals(Summary.Any, LengthFilter.summary(LengthUnit.PAGES, null))
        assertEquals(Summary.Between("500", "1,500"), LengthFilter.summary(LengthUnit.PAGES, LengthRange(500, 1500)))
        assertEquals(Summary.AtLeast("1,000"), LengthFilter.summary(LengthUnit.CHAPTERS, LengthRange(1000, null)))
        assertEquals(Summary.AtMost("100"), LengthFilter.summary(LengthUnit.PAGES, LengthRange(null, 100)))
        assertEquals(Summary.Between("10万", "50万"), LengthFilter.summary(LengthUnit.CHARACTERS, LengthRange(100_000, 500_000)))
        assertEquals(Summary.AtLeast("200万"), LengthFilter.summary(LengthUnit.CHARACTERS, LengthRange(2_000_000, null)))
        assertEquals(Summary.AtMost("5,000"), LengthFilter.summary(LengthUnit.CHARACTERS, LengthRange(null, 5_000)))
    }

    @Test
    fun wanFormatting() {
        assertEquals("283", LengthFilter.formatWan(2_830_123))
        assertEquals("12.3", LengthFilter.formatWan(123_456))
        assertEquals("100", LengthFilter.formatWan(1_000_000))
        assertEquals("10", LengthFilter.formatWan(100_000))
        assertEquals("1,234", LengthFilter.formatWan(12_340_000))
    }

    @Test
    fun customFieldsReadAndWriteTheUnit() {
        assertEquals("50", LengthFilter.inputText(LengthUnit.CHARACTERS, 500_000))
        assertEquals("12.5", LengthFilter.inputText(LengthUnit.CHARACTERS, 125_000))
        assertEquals("1500", LengthFilter.inputText(LengthUnit.PAGES, 1500))
        assertEquals("", LengthFilter.inputText(LengthUnit.PAGES, null))
        assertEquals(125_000L, LengthFilter.parseInput(LengthUnit.CHARACTERS, "12.5"))
        assertEquals(500_000L, LengthFilter.parseInput(LengthUnit.CHARACTERS, "50"))
        assertEquals(1500L, LengthFilter.parseInput(LengthUnit.PAGES, "1,500"))
        assertEquals(12L, LengthFilter.parseInput(LengthUnit.CHAPTERS, "１２"))
        assertNull(LengthFilter.parseInput(LengthUnit.PAGES, ""))
        assertNull(LengthFilter.parseInput(LengthUnit.PAGES, "0"))
        assertNull(LengthFilter.parseInput(LengthUnit.PAGES, "abc"))
        assertNull(LengthFilter.parseInput(LengthUnit.PAGES, "1.5"))
    }

    private val labels = LengthFilter.CardLabels(
        pages = "%1\$s pages",
        chapters = "%1\$s ch",
        chaptersApprox = "≈%1\$s ch",
        characters = "%1\$s字",
        charactersApprox = "≈%1\$s字",
    )

    @Test
    fun cardLine() {
        assertEquals("2,932 pages · 109 ch", LengthFilter.cardText(NovelLength(chapters = 109, pages = 2932), labels))
        assertEquals("283万字 · 286 ch", LengthFilter.cardText(NovelLength(chapters = 286, characters = 2_830_123), labels))
        assertEquals("≈1,417 ch", LengthFilter.cardText(NovelLength(chapters = 1417, approximate = true), labels))
        assertEquals("≈884万字 · ≈1,000 ch", LengthFilter.cardText(NovelLength(chapters = 1000, characters = 8_839_680, approximate = true), labels))
        assertEquals("9,876字", LengthFilter.cardText(NovelLength(characters = 9_876), labels))
        assertNull(LengthFilter.cardText(null, labels))
        assertNull(LengthFilter.cardText(NovelLength(), labels))
    }

    // ---- number parsing ----

    @Test
    fun countsInListingText() {
        assertEquals(1234L, LengthFilter.parseCount("1,234 Pages"))
        assertEquals(109L, LengthFilter.parseCount("109 Chapters"))
        assertEquals(1_234_000L, LengthFilter.parseCount("123.4万字"))
        assertEquals(5_060_100L, LengthFilter.parseCount("506.01萬字"))
        assertEquals(123L, LengthFilter.parseCount("全123部分"))
        assertEquals(123L, LengthFilter.parseCount("123화"))
        assertEquals(76L, LengthFilter.parseCount("76회"))
        assertEquals(5_450_000L, LengthFilter.parseCount("5.45M명"))
        assertEquals(560_800L, LengthFilter.parseCount("560.8K회"))
        assertEquals(12L, LengthFilter.parseCount("12 min"))
        assertEquals(2_209_879L, LengthFilter.parseCount("2,209,879文字"))
        assertEquals(12L, LengthFilter.parseCount("第１２章"))
        assertNull(LengthFilter.parseCount("abc"))
        assertNull(LengthFilter.parseCount(""))
        assertNull(LengthFilter.parseCount(null))
    }

    @Test
    fun chapterNumberOfALatestChapter() {
        val cases = mapOf(
            "Chapter 523" to 523L,
            "Ch. 523: The Return" to 523L,
            "ch 7" to 7L,
            "Episode 12" to 12L,
            "EP.138" to 138L,
            "Vol. 2 Chapter 15 - Two Kings" to 15L,
            "第523章 标题" to 523L,
            "第一千零五章 标题" to 1005L,
            "第3卷 第12章" to 12L,
            "第１２章" to 12L,
            "第307頁" to 307L,
            "第463章 寻仙的人（万字）" to 463L,
            "123화" to 123L,
            "총 76회" to 76L,
            "全123部分" to 123L,
            "(全662エピソード)" to 662L,
            "109 Chapters" to 109L,
            "32 episodes" to 32L,
            "1417.新书已发" to 1417L,
            "1. It Is All Your Fault" to 1L,
            "Side Story 3" to 3L,
        )
        for ((text, expected) in cases) assertEquals(text, expected, LengthFilter.chapterNumber(text))
        for (text in listOf("后记", "後記", "Chapter One", "289.4万字", "2,209,879文字", "2024-04-03", "12345678", "", null)) {
            assertNull(text, LengthFilter.chapterNumber(text))
        }
    }

    @Test
    fun latestChapterFallbackIsApproximateChapters() {
        assertEquals(NovelLength(chapters = 523, approximate = true), LengthFilter.fromLatestChapter("Chapter 523"))
        assertNull(LengthFilter.fromLatestChapter("後記"))
        assertNull(LengthFilter.fromLatestChapter(null))
    }

    @Test
    fun chineseNumerals() {
        assertEquals(1005L, LengthFilter.chineseNumber("一千零五"))
        assertEquals(12L, LengthFilter.chineseNumber("十二"))
        assertEquals(10L, LengthFilter.chineseNumber("十"))
        assertEquals(200L, LengthFilter.chineseNumber("两百"))
        assertEquals(35_000L, LengthFilter.chineseNumber("三万五千"))
        assertEquals(200_000L, LengthFilter.chineseNumber("二十万"))
        assertEquals(1000L, LengthFilter.chineseNumber("一千"))
        assertNull(LengthFilter.chineseNumber("abc"))
    }

    // ---- client-side paging ----

    private data class Item(val url: String, val pages: Long)

    /** Fake listing: [perPage] items per page, every third one 1000 pages long, [lastPage] pages. */
    private class Site(val perPage: Int = 5, val lastPage: Int = 100, val repeatLast: Boolean = false) {
        val loaded = mutableListOf<Int>()
        var failAt: Int? = null
        suspend fun load(page: Int): Result<Pair<String, List<Item>>> {
            loaded += page
            if (page == failAt) return Result.failure(IllegalStateException("page $page failed"))
            val p = if (page > lastPage) (if (repeatLast) lastPage else return Result.success("u$page" to emptyList())) else page
            return Result.success("u$page" to (1..perPage).map { i ->
                val n = (p - 1) * perPage + i
                Item("n$n", if (n % 3 == 0) 1000 else 10)
            })
        }
    }

    private fun scan(site: Site, start: Int = 1, known: Set<String> = emptySet(), minItems: Int = 5, pauses: MutableList<Int>? = null) =
        runBlocking {
            LengthFilter.scan(
                startPage = start,
                known = known,
                urlOf = { it.url },
                keep = { it.pages >= 500 },
                minItems = minItems,
                pause = { pauses?.add(it) },
            ) { site.load(it) }
        }

    @Test
    fun scanStopsOnceEnoughItemsMatched() {
        val site = Site()
        val pauses = mutableListOf<Int>()
        val result = scan(site, minItems = 3, pauses = pauses).getOrThrow()
        // Pages of 5: 1 match on page 1, 2 on page 2 (n=6, 9), so 3 after page 2... (3, 6, 9).
        assertEquals(listOf("n3", "n6", "n9"), result.items.map { it.url })
        assertEquals(2, result.lastPage)
        assertFalse(result.end)
        assertEquals(listOf(1, 2), site.loaded)
        // The pause runs before every page after the first.
        assertEquals(listOf(2), pauses)
        assertEquals(10, result.scannedUrls.size)
    }

    @Test
    fun scanIsBoundedByTheExtraPages() {
        val site = Site()
        val result = scan(site, start = 3, minItems = 100).getOrThrow()
        assertEquals(listOf(3, 4, 5, 6), site.loaded)
        assertEquals(1 + LengthFilter.MAX_EXTRA_PAGES, site.loaded.size)
        assertEquals(6, result.lastPage)
        assertFalse(result.end)
    }

    @Test
    fun scanWithNothingMatchingReportsNoItemsButNotTheEnd() {
        val site = Site()
        val result = runBlocking {
            LengthFilter.scan<Item>(1, emptySet(), { it.url }, { false }) { site.load(it) }
        }.getOrThrow()
        assertTrue(result.items.isEmpty())
        assertFalse(result.end)
        assertEquals(4, result.lastPage)
        assertEquals(20, result.scannedUrls.size)
    }

    @Test
    fun scanEndsOnAnEmptyOrRepeatedPage() {
        val empty = scan(Site(lastPage = 2), minItems = 100).getOrThrow()
        assertTrue(empty.end)
        assertEquals(3, empty.lastPage)
        assertEquals(listOf("n3", "n6", "n9"), empty.items.map { it.url })

        val repeated = Site(lastPage = 2, repeatLast = true)
        val result = scan(repeated, minItems = 100).getOrThrow()
        assertTrue(result.end)
        assertEquals(listOf(1, 2, 3), repeated.loaded)
    }

    @Test
    fun scanSkipsItemsKnownFromEarlierLoads() {
        // Page 2 was scanned by the previous load (nothing matched there); repeating it is the end.
        val known = (6..10).map { "n$it" }.toSet()
        val site = Site(lastPage = 2, repeatLast = true)
        val result = scan(site, start = 3, known = known, minItems = 100).getOrThrow()
        assertTrue(result.end)
        assertTrue(result.items.isEmpty())
        assertEquals(listOf(3), site.loaded)
    }

    @Test
    fun scanFailures() {
        val first = Site().apply { failAt = 1 }
        assertTrue(scan(first).isFailure)

        // A later page failing keeps what matched so far; the next load starts after the last good page.
        val later = Site().apply { failAt = 3 }
        val partial = scan(later, minItems = 100).getOrThrow()
        assertEquals(listOf("n3", "n6", "n9"), partial.items.map { it.url })
        assertEquals(2, partial.lastPage)
        assertFalse(partial.end)

        // Nothing matched before the failure: the load fails (Retry asks again).
        val none = Site().apply { failAt = 2 }
        val result = runBlocking {
            LengthFilter.scan<Item>(1, emptySet(), { it.url }, { false }) { none.load(it) }
        }
        assertTrue(result.isFailure)
    }
}
