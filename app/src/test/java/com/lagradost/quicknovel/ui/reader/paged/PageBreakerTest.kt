package com.lagradost.quicknovel.ui.reader.paged

import com.lagradost.quicknovel.ui.reader.paged.PageBreaker.Block
import com.lagradost.quicknovel.ui.reader.paged.PageBreaker.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBreakerTest {
    /** A paragraph of [lines] lines of [h] px each. */
    private fun para(lines: Int, h: Int = 10, padTop: Int = 0, padBottom: Int = 0) =
        Block.Lines(IntArray(lines + 1) { it * h }, padTop, padBottom)

    @Test
    fun everythingFitsOnOnePage() {
        val pages = PageBreaker.paginate(listOf(para(3), para(2)), 100)
        assertEquals(1, pages.size)
        assertEquals(listOf(Piece(0, 0, 3, 0), Piece(1, 0, 2, 30)), pages[0])
    }

    @Test
    fun paragraphSplitsAcrossPagesByLine() {
        // 25 lines of 10px on 100px pages: 10 + 10 + 5
        val pages = PageBreaker.paginate(listOf(para(25)), 100)
        assertEquals(3, pages.size)
        assertEquals(listOf(Piece(0, 0, 10, 0)), pages[0])
        assertEquals(listOf(Piece(0, 10, 20, 0)), pages[1])
        assertEquals(listOf(Piece(0, 20, 25, 0)), pages[2])
    }

    @Test
    fun partialLineDoesNotFit() {
        // 95px used, the next 10px line goes to the next page
        val pages = PageBreaker.paginate(listOf(para(1, h = 95), para(2)), 100)
        assertEquals(2, pages.size)
        assertEquals(listOf(Piece(0, 0, 1, 0)), pages[0])
        assertEquals(listOf(Piece(1, 0, 2, 0)), pages[1])
    }

    @Test
    fun paragraphSpacingDroppedAtPageTopAndKeptInside() {
        val blocks = listOf(para(2, padTop = 5, padBottom = 5), para(2, padTop = 5, padBottom = 5))
        val pages = PageBreaker.paginate(blocks, 100)
        assertEquals(1, pages.size)
        // first paragraph at the top without its top padding; second after 20 + 5 + 5
        assertEquals(listOf(Piece(0, 0, 2, 0), Piece(1, 0, 2, 30)), pages[0])
    }

    @Test
    fun continuationStartsAtTopOfNextPage() {
        val pages = PageBreaker.paginate(listOf(para(8), para(5, padTop = 6)), 100)
        // 80 + 6 = 86: one line fits (96), the rest continue at y = 0
        assertEquals(listOf(Piece(0, 0, 8, 0), Piece(1, 0, 1, 86)), pages[0])
        assertEquals(listOf(Piece(1, 1, 5, 0)), pages[1])
    }

    @Test
    fun lineTallerThanPageGetsItsOwnPage() {
        val pages = PageBreaker.paginate(listOf(para(1), para(1, h = 150), para(1)), 100)
        assertEquals(3, pages.size)
        assertEquals(listOf(Piece(1, 0, 1, 0)), pages[1])
    }

    @Test
    fun fixedBlockMovesToNextPageWhenItDoesNotFit() {
        val pages = PageBreaker.paginate(listOf(para(9), Block.Fixed(20), para(1)), 100)
        assertEquals(2, pages.size)
        assertEquals(listOf(Piece(1, 0, 0, 0), Piece(2, 0, 1, 20)), pages[1])
    }

    @Test
    fun everyLineIsOnExactlyOnePage() {
        val blocks = (1..40).map { para(it % 7 + 1, h = 13, padTop = 4, padBottom = 4) }
        val pages = PageBreaker.paginate(blocks, 211)
        for ((i, b) in blocks.withIndex()) {
            val lines = pages.flatten().filter { it.block == i }.flatMap { (it.firstLine until it.endLine).toList() }
            assertEquals((0 until (b as Block.Lines).lineCount).toList(), lines)
        }
        // and no page overflows (no line taller than the page here)
        for (page in pages) {
            for (p in page) {
                val b = blocks[p.block]
                val bottom = p.y + (b.lineTops[p.endLine] - b.lineTops[p.firstLine])
                assertTrue("overflow $bottom", bottom <= 211)
            }
        }
    }

    @Test
    fun emptyInput() {
        assertEquals(0, PageBreaker.paginate(emptyList(), 100).size)
        assertEquals(0, PageBreaker.paginate(listOf(para(0)), 100).size)
    }
}
