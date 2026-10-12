package com.lagradost.quicknovel.ui.reader.paged

import com.lagradost.quicknovel.ui.reader.ReadingAnchor
import com.lagradost.quicknovel.ui.reader.paged.PageSelection.Handle
import com.lagradost.quicknovel.ui.reader.paged.PageSelection.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Scroll position -> page mapping, the scroll anchor, and the page selection rules. */
class PagePositionTest {
    // Chapter 3: title page, pages starting at 0, 400, 820; chapter 4: title, 0, 380.
    private val chapters = intArrayOf(3, 3, 3, 3, 4, 4, 4)
    private val starts = intArrayOf(-1, 0, 400, 820, -1, 0, 380)

    @Test
    fun charOnAPageStartShowsThatPageNotThePreviousOne() {
        assertEquals(2, PageBreaker.pageFor(chapters, starts, 3, 400))
        assertEquals(3, PageBreaker.pageFor(chapters, starts, 3, 820))
    }

    @Test
    fun charInsideAPageLineShowsThePageHoldingIt() {
        // scroll and page widths differ: the scroll line start is in the middle of a page line
        assertEquals(1, PageBreaker.pageFor(chapters, starts, 3, 399))
        assertEquals(2, PageBreaker.pageFor(chapters, starts, 3, 401))
        assertEquals(3, PageBreaker.pageFor(chapters, starts, 3, 5000))
    }

    @Test
    fun chapterStartAndUnknownChapters() {
        assertEquals("title page", 0, PageBreaker.pageFor(chapters, starts, 3, -1))
        assertEquals("first char", 1, PageBreaker.pageFor(chapters, starts, 3, 0))
        assertEquals(4, PageBreaker.pageFor(chapters, starts, 4, -1))
        assertEquals(6, PageBreaker.pageFor(chapters, starts, 4, 380))
        assertEquals(-1, PageBreaker.pageFor(chapters, starts, 9, 10))
        // a chapter that is still loading has only its status page
        assertEquals(0, PageBreaker.pageFor(intArrayOf(5), intArrayOf(-1), 5, 300))
    }

    // ---- ReadingAnchor ----

    @Test
    fun anchorIsTheFirstLineEntirelyOnScreen() {
        // line 0 is cut by the top edge (top -20), line 1 is the first whole line
        val tops = intArrayOf(-20, 20, 60, 100)
        val bottoms = intArrayOf(20, 60, 100, 140)
        assertEquals(1, ReadingAnchor.pick(tops, bottoms, 0, 130))
        // a top margin: the area starts at 30, so line 1 (top 20) is partly hidden too
        assertEquals(2, ReadingAnchor.pick(tops, bottoms, 30, 130))
    }

    @Test
    fun anchorFallsBackToThePartlyVisibleLine() {
        // one line taller than the area (a big image): it is the position
        assertEquals(1, ReadingAnchor.pick(intArrayOf(-500, -100), intArrayOf(-100, 900), 0, 800))
        // nothing on screen: the first line
        assertEquals(0, ReadingAnchor.pick(intArrayOf(-500), intArrayOf(-400), 0, 800))
        assertEquals(-1, ReadingAnchor.pick(IntArray(0), IntArray(0), 0, 800))
    }

    @Test
    fun paragraphForACharIsTheOneContainingIt() {
        val starts = intArrayOf(0, 51, 120)
        val ends = intArrayOf(50, 119, 300)
        assertEquals(0, ReadingAnchor.paragraphFor(starts, ends, 0))
        assertEquals("inside, not the next paragraph", 1, ReadingAnchor.paragraphFor(starts, ends, 90))
        assertEquals(1, ReadingAnchor.paragraphFor(starts, ends, 51))
        assertEquals("the newline between paragraphs", 1, ReadingAnchor.paragraphFor(starts, ends, 50))
        assertEquals(2, ReadingAnchor.paragraphFor(starts, ends, 299))
        assertEquals(-1, ReadingAnchor.paragraphFor(starts, ends, 300))
    }

    // ---- PageSelection ----

    @Test
    fun draggingTheEndHandleExtendsAndShrinks() {
        val word = Range(10, 15)
        assertEquals(Range(10, 30) to Handle.END, PageSelection.drag(word, Handle.END, 30, 0, 100))
        assertEquals(Range(10, 12) to Handle.END, PageSelection.drag(word, Handle.END, 12, 0, 100))
        assertEquals(Range(3, 15) to Handle.START, PageSelection.drag(word, Handle.START, 3, 0, 100))
    }

    @Test
    fun handlesSwapWhenTheyCross() {
        val word = Range(10, 15)
        // the end handle dragged before the start: the start handle becomes the end
        assertEquals(Range(4, 10) to Handle.START, PageSelection.drag(word, Handle.END, 4, 0, 100))
        assertEquals(Range(15, 40) to Handle.END, PageSelection.drag(word, Handle.START, 40, 0, 100))
    }

    @Test
    fun selectionKeepsOneCharacterAndStaysOnThePage() {
        val word = Range(10, 15)
        assertEquals(Range(10, 11) to Handle.END, PageSelection.drag(word, Handle.END, 10, 0, 100))
        assertEquals(Range(14, 15) to Handle.START, PageSelection.drag(word, Handle.START, 15, 0, 100))
        // the paragraph continues on the next page: clamped to the part on this page
        assertEquals(Range(10, 60) to Handle.END, PageSelection.drag(word, Handle.END, 500, 0, 60))
        assertEquals(Range(8, 15) to Handle.START, PageSelection.drag(word, Handle.START, -3, 8, 60))
        assertNull(PageSelection.drag(word, Handle.END, 5, 20, 20))
    }

    @Test
    fun charUnderTheFingerNotTheNearestCaret() {
        val lefts = floatArrayOf(100f, 110f)
        val rights = floatArrayOf(110f, 125f)
        assertEquals(0, PageSelection.charAt(108f, lefts, rights))
        assertEquals(1, PageSelection.charAt(110f, lefts, rights))
        assertEquals(-1, PageSelection.charAt(130f, lefts, rights))
        // right-to-left text: right edge left of the left edge
        assertEquals(0, PageSelection.charAt(105f, floatArrayOf(110f), floatArrayOf(100f)))
    }
}
