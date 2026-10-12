package com.lagradost.quicknovel.imported

import com.lagradost.quicknovel.imported.EpubChapterPlan.Chapter
import com.lagradost.quicknovel.imported.EpubChapterPlan.SpineItem
import com.lagradost.quicknovel.imported.EpubChapterPlan.TocEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class EpubChapterPlanTest {
    private fun spine(vararg linear: Boolean) = linear.mapIndexed { i, l -> SpineItem("item$i", l) }

    @Test
    fun tocEntriesSpanUpToTheNextEntry() {
        // cover, title page, ch1 (2 files), ch2, ch3
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("一", 2), TocEntry("二", 4), TocEntry("三", 5)),
            spine = spine(true, true, true, true, true, true),
        )
        assertEquals(listOf(Chapter("一", listOf(2, 3)), Chapter("二", listOf(4)), Chapter("三", listOf(5))), plan)
    }

    @Test
    fun nestedEntriesAreFlattenedDepthFirst() {
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("第一卷", 0, listOf(TocEntry("第一章", 1), TocEntry("第二章", 2))), TocEntry("第二卷", 3)),
            spine = spine(true, true, true, true),
        )
        assertEquals(listOf("第一卷", "第一章", "第二章", "第二卷"), plan.map { it.title })
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), plan.map { it.spineIndices })
    }

    @Test
    fun brokenEntriesAndTheirChildrenAreSkipped() {
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("a", 0), TocEntry("missing", -1, listOf(TocEntry("child", 1))), TocEntry("b", 2)),
            spine = spine(true, true, true),
        )
        assertEquals(listOf(Chapter("a", listOf(0, 1)), Chapter("b", listOf(2))), plan)
    }

    @Test
    fun nonLinearItemsInsideAChapterAreSkipped() {
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("a", 0), TocEntry("b", 3)),
            spine = spine(true, false, true, true),
        )
        assertEquals(listOf(0, 2), plan[0].spineIndices)
    }

    @Test
    fun withoutAUsableTocEveryLinearSpineItemIsAChapter() {
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("only", 0)),
            spine = listOf(SpineItem("Cover", true), SpineItem(null, false), SpineItem(null, true), SpineItem("End", true)),
        )
        assertEquals(listOf(Chapter("Cover", listOf(0)), Chapter("Chapter 2", listOf(2)), Chapter("End", listOf(3))), plan)
    }

    @Test
    fun anchorsIntoOneLongFileKeepTheReadersBehaviour() {
        // Two TOC entries pointing into the same (very long) spine item: the first is empty,
        // the second holds the file; the reader has always shown them like this.
        val plan = EpubChapterPlan.plan(
            toc = listOf(TocEntry("一", 0), TocEntry("二", 0), TocEntry("三", 1)),
            spine = spine(true, true),
        )
        assertEquals(listOf(emptyList(), listOf(0), listOf(1)), plan.map { it.spineIndices })
    }

    @Test
    fun missingTitlesAreNumbered() {
        val plan = EpubChapterPlan.plan(toc = listOf(TocEntry(null, 0), TocEntry(null, 1)), spine = spine(true, true))
        assertEquals(listOf("Chapter 1", "Chapter 2"), plan.map { it.title })
    }
}
