package com.lagradost.quicknovel.ui.reader.paged

/**
 * Pure rules for the text selection on a page (docs/reader.md, "Paged mode"): a range
 * [start, end) inside one paragraph, moved by its two handles. Unit tested in
 * PageSelectionTest.
 */
object PageSelection {
    enum class Handle { START, END }

    data class Range(val start: Int, val end: Int) {
        init {
            require(start < end) { "empty selection" }
        }
    }

    /**
     * [handle] of [range] dragged to character [offset]. The other handle stays put; dragging
     * past it swaps the handles (the returned handle is the one under the finger now). The
     * selection keeps at least one character and stays inside [min, max) (the paragraph's text
     * on this page). Null if [min, max) is empty.
     */
    fun drag(range: Range, handle: Handle, offset: Int, min: Int, max: Int): Pair<Range, Handle>? {
        if (max <= min) return null
        val anchor = (if (handle == Handle.START) range.end else range.start).coerceIn(min, max)
        val o = offset.coerceIn(min, max)
        return when {
            o < anchor -> Range(o, anchor) to Handle.START
            o > anchor -> Range(anchor, o) to Handle.END
            // on the other handle: keep one character, on the side the finger came from
            handle == Handle.START -> (if (anchor > min) Range(anchor - 1, anchor) else Range(anchor, anchor + 1)) to handle
            else -> (if (anchor < max) Range(anchor, anchor + 1) else Range(anchor - 1, anchor)) to handle
        }
    }

    /** Index of the character under x, given [left] edges and the [right] edge of the last one. */
    fun charAt(x: Float, lefts: FloatArray, rights: FloatArray): Int {
        for (i in lefts.indices) {
            if (x >= minOf(lefts[i], rights[i]) && x < maxOf(lefts[i], rights[i])) return i
        }
        return -1
    }
}
