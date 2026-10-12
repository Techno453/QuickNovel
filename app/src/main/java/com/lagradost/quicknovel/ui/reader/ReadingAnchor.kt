package com.lagradost.quicknovel.ui.reader

/**
 * Pure rules for the saved reading position (docs/reader.md, "Position"). Unit tested in
 * ReadingAnchorTest.
 */
object ReadingAnchor {
    /**
     * Which visible line is saved as the position in scroll mode. Lines are given top to bottom
     * by their [tops] and [bottoms] (window px); the text area is [viewTop, viewBottom).
     *
     * - The first line drawn entirely inside the area: the reader has seen all of it, and the
     *   paged view shows the page holding it (never the page before, which would only hold a
     *   line cut off at the top edge).
     * - Else (one line taller than the area, an image) the first line still partly on screen.
     * - Else (nothing on screen, list not laid out) the first line, index 0; -1 when empty.
     */
    fun pick(tops: IntArray, bottoms: IntArray, viewTop: Int, viewBottom: Int): Int {
        require(tops.size == bottoms.size) { "one bottom per line" }
        if (tops.isEmpty()) return -1
        for (i in tops.indices) {
            if (tops[i] >= viewTop && bottoms[i] <= viewBottom) return i
        }
        for (i in tops.indices) {
            if (bottoms[i] > viewTop && tops[i] < viewBottom) return i
        }
        return 0
    }

    /**
     * The paragraph holding chapter character [char], given each paragraph's [starts] and
     * (exclusive) [ends] in reading order: the paragraph containing it, else the next one
     * (a character between two paragraphs), else -1 (past the end).
     */
    fun paragraphFor(starts: IntArray, ends: IntArray, char: Int): Int {
        require(starts.size == ends.size) { "one end per paragraph" }
        for (i in starts.indices) {
            if (char < ends[i]) return i
        }
        return -1
    }
}
