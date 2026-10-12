package com.lagradost.quicknovel.ui.reader.paged

/**
 * Splits a run of laid-out paragraphs into pages of a fixed height. Pure (the line metrics come
 * from StaticLayout in [ReaderPaginator]); unit tested in PageBreakerTest.
 *
 * Rules: lines are never split; a paragraph's top padding is dropped at the top of a page; a
 * paragraph continues on the next page line by line; a single line taller than the page gets a
 * page of its own; fixed blocks (separators) move to the next page if they do not fit.
 */
object PageBreaker {
    sealed class Block {
        /**
         * A paragraph. [lineTops] has one entry per line plus the bottom of the last line
         * (StaticLayout.getLineTop(0..lineCount)); [padTop]/[padBottom] are paragraph spacing.
         */
        class Lines(val lineTops: IntArray, val padTop: Int, val padBottom: Int) : Block() {
            val lineCount: Int get() = lineTops.size - 1
            fun lineHeight(i: Int) = lineTops[i + 1] - lineTops[i]
        }

        /** A block of fixed height (e.g. a separator rule). */
        class Fixed(val height: Int) : Block()
    }

    /**
     * Lines [firstLine, endLine) of block [block] drawn with their first line's top at [y]
     * (for [Block.Fixed]: firstLine = endLine = 0).
     */
    data class Piece(val block: Int, val firstLine: Int, val endLine: Int, val y: Int)

    fun paginate(blocks: List<Block>, pageHeight: Int): List<List<Piece>> {
        require(pageHeight > 0) { "pageHeight must be positive" }
        val pages = ArrayList<List<Piece>>()
        var page = ArrayList<Piece>()
        var y = 0

        fun newPage() {
            if (page.isNotEmpty()) pages += page
            page = ArrayList()
            y = 0
        }

        for ((index, block) in blocks.withIndex()) {
            when (block) {
                is Block.Fixed -> {
                    if (y > 0 && y + block.height > pageHeight) newPage()
                    page += Piece(index, 0, 0, y)
                    y += block.height
                }

                is Block.Lines -> {
                    if (block.lineCount <= 0) continue
                    if (y > 0) y += block.padTop
                    var first = 0
                    var pieceY = y
                    var line = 0
                    while (line < block.lineCount) {
                        val h = block.lineHeight(line)
                        if (y + h > pageHeight && y > 0) {
                            // close what fits on this page, continue on the next one
                            if (line > first) page += Piece(index, first, line, pieceY)
                            newPage()
                            first = line
                            pieceY = 0
                        }
                        y += h
                        line++
                    }
                    page += Piece(index, first, block.lineCount, pieceY)
                    y += block.padBottom
                }
            }
        }
        newPage()
        return pages
    }

    /**
     * The page to show for a reading position ([chapter], [char]), given each page's chapter and
     * first character ([pageChapters], [pageStarts] in reading order; -1 for a title or status
     * page). Scroll mode saves the first character of the first fully visible line; scroll and
     * page widths can differ, so that character may sit inside a page line.
     *
     * - A negative [char] (chapter start) gives the chapter's first page.
     * - Otherwise the last page of the chapter starting at or before [char]: the page whose text
     *   contains it.
     * - A chapter without text pages (loading, failed) gives its first page; an unknown chapter -1.
     */
    fun pageFor(pageChapters: IntArray, pageStarts: IntArray, chapter: Int, char: Int): Int {
        require(pageChapters.size == pageStarts.size) { "one start per page" }
        val first = pageChapters.indexOfFirst { it == chapter }
        if (first == -1) return -1
        if (char < 0) return first
        var best = first
        var i = first
        while (i < pageChapters.size && pageChapters[i] == chapter) {
            if (pageStarts[i] in 0..char) best = i
            i++
        }
        return best
    }
}
