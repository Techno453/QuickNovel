package com.lagradost.quicknovel.ui.reader.paged

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.ClickableSpan
import android.view.View
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TextSpan
import com.lagradost.quicknovel.ui.reader.paged.PageSelection.Handle
import kotlin.math.abs

/**
 * Draws one [ReaderPage.Text]: each [Slice] is the visible line range of a paragraph's
 * StaticLayout, translated to its place on the page and clipped to those lines. The TTS line
 * being read is highlighted behind the text.
 *
 * Also the page's touch targets: links ([linkAt]) and the text selection ([select], drawn
 * with the platform's selection colour and handles, moved with [beginDrag] / [dragTo]). A
 * selection stays inside one paragraph and the part of it on this page.
 */
@SuppressLint("ViewConstructor")
class PageTextView(context: Context, private val ttsLine: () -> TTSHelper.TTSLine?) : View(context) {
    var page: ReaderPage.Text? = null
        set(value) {
            if (field !== value) selection = null
            field = value
            invalidate()
        }

    /** Text area insets inside this view (px). */
    var areaLeft = 0
    var areaTop = 0

    var ruleColor: Int = 0

    /** The selected paragraph and range (relative to the paragraph's text), or null. */
    class Selection(val span: TextSpan, val range: PageSelection.Range)

    var selection: Selection? = null
        private set

    private val rulePaint = Paint()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.readerHightlightedTextColor)
        pathEffect = CornerPathEffect(6f * context.resources.displayMetrics.density)
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = themeColor(android.R.attr.textColorHighlight, 0x6633B5E5)
    }
    private val path = Path()

    private val handleLeft: Drawable? = themeDrawable(android.R.attr.textSelectHandleLeft)
    private val handleRight: Drawable? = themeDrawable(android.R.attr.textSelectHandleRight)

    private fun themeColor(attr: Int, fallback: Int): Int {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            a.getColor(0, fallback)
        } finally {
            a.recycle()
        }
    }

    private fun themeDrawable(attr: Int): Drawable? {
        val a = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            a.getDrawable(0)?.mutate()
        } finally {
            a.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = page ?: return
        val line = ttsLine()
        val sel = selection
        for (slice in page.slices) {
            val layout = slice.layout
            if (layout == null) {
                // separator rule, 2dp centred in its block
                val thick = 2f * resources.displayMetrics.density
                val mid = areaTop + slice.y + slice.ruleHeight / 2f
                rulePaint.color = ruleColor
                canvas.drawRect(
                    areaLeft.toFloat(), mid - thick / 2, (width - areaLeft).toFloat(), mid + thick / 2, rulePaint
                )
                continue
            }
            val top = layout.getLineTop(slice.firstLine)
            val bottom = layout.getLineTop(slice.endLine)
            canvas.save()
            canvas.translate(areaLeft.toFloat(), (areaTop + slice.y - top).toFloat())
            canvas.clipRect(0, top, layout.width, bottom)
            if (line != null && line.index == slice.span.index) {
                val s = (line.startChar - slice.span.start).coerceIn(0, layout.text.length)
                val e = (line.endChar - slice.span.start).coerceIn(0, layout.text.length)
                if (e > s) {
                    path.reset()
                    layout.getSelectionPath(s, e, path)
                    canvas.drawPath(path, highlightPaint)
                }
            }
            if (sel != null && sel.span === slice.span) {
                path.reset()
                layout.getSelectionPath(sel.range.start, sel.range.end, path)
                canvas.drawPath(path, selectionPaint)
            }
            layout.draw(canvas)
            canvas.restore()
        }
        if (sel != null) {
            handlePoint(sel, Handle.START)?.let { (x, y) -> drawHandle(canvas, handleLeft, x, y, start = true) }
            handlePoint(sel, Handle.END)?.let { (x, y) -> drawHandle(canvas, handleRight, x, y, start = false) }
        }
    }

    // ============================== hit testing ==============================

    private fun lineTopOnPage(slice: Slice, layout: StaticLayout, line: Int): Int =
        areaTop + slice.y + layout.getLineTop(line) - layout.getLineTop(slice.firstLine)

    private fun lineBottomOnPage(slice: Slice, layout: StaticLayout, line: Int): Int =
        areaTop + slice.y + layout.getLineBottom(line) - layout.getLineTop(slice.firstLine)

    /** The paragraph and character (relative to the paragraph) under ([x], [y]), or null. */
    fun hit(x: Float, y: Float): Pair<Slice, Int>? {
        val page = page ?: return null
        val localY = y - areaTop
        for (slice in page.slices) {
            val layout = slice.layout ?: continue
            val top = layout.getLineTop(slice.firstLine)
            val height = layout.getLineTop(slice.endLine) - top
            if (localY < slice.y || localY >= slice.y + height) continue
            val line = layout.getLineForVertical((localY - slice.y + top).toInt())
                .coerceIn(slice.firstLine, slice.endLine - 1)
            val localX = x - areaLeft
            if (localX < layout.getLineLeft(line) || localX > layout.getLineRight(line)) return null
            return slice to layout.getOffsetForHorizontal(line, localX)
        }
        return null
    }

    /**
     * The character drawn under ([x], [y]) (not the nearest caret position, which can be the
     * character after the finger), relative to its paragraph, or null.
     */
    fun charUnder(x: Float, y: Float): Pair<Slice, Int>? {
        val (slice, offset) = hit(x, y) ?: return null
        val layout = slice.layout ?: return null
        val line = layout.getLineForOffset(offset)
        val localX = x - areaLeft
        val candidates = listOf(offset - 1, offset).filter {
            it >= layout.getLineStart(line) && it < layout.getLineEnd(line)
        }
        if (candidates.isEmpty()) return null
        val lefts = FloatArray(candidates.size) { layout.getPrimaryHorizontal(candidates[it]) }
        val rights = FloatArray(candidates.size) {
            val next = candidates[it] + 1
            if (next < layout.getLineEnd(line)) layout.getPrimaryHorizontal(next) else layout.getLineRight(line)
        }
        val i = PageSelection.charAt(localX, lefts, rights)
        return if (i >= 0) slice to candidates[i] else null
    }

    /** The link (same spans the scroll view's LinkMovementMethod clicks) under the finger. */
    fun linkAt(x: Float, y: Float): ClickableSpan? {
        val (slice, char) = charUnder(x, y) ?: return null
        val text = slice.layout?.text as? Spanned ?: return null
        return text.getSpans(char, char + 1, ClickableSpan::class.java).firstOrNull()
    }

    // ============================== selection ==============================

    /** Selects [range] of [span] (clamped to the part of the paragraph on this page). */
    fun select(span: TextSpan, range: PageSelection.Range) {
        val (min, max) = visibleRange(span) ?: return
        val start = range.start.coerceIn(min, max - 1)
        val end = range.end.coerceIn(start + 1, max)
        selection = Selection(span, PageSelection.Range(start, end))
        invalidate()
    }

    fun clearSelection() {
        if (selection == null) return
        selection = null
        dragging = null
        invalidate()
    }

    /** The selected text, or null. */
    fun selectedText(): CharSequence? {
        val sel = selection ?: return null
        return sel.span.text.subSequence(sel.range.start, sel.range.end)
    }

    /** [min, max) of [span]'s characters drawn on this page, or null. */
    private fun visibleRange(span: TextSpan): Pair<Int, Int>? {
        val slices = page?.slices?.filter { it.span === span && it.layout != null } ?: return null
        if (slices.isEmpty()) return null
        val first = slices.first()
        val last = slices.last()
        val min = first.layout!!.getLineStart(first.firstLine)
        val max = last.layout!!.getLineEnd(last.endLine - 1)
        return if (max > min) min to max else null
    }

    /** Where a handle points (bottom of its line, at the selection edge), in view px. */
    private fun handlePoint(sel: Selection, handle: Handle): Pair<Float, Float>? {
        val slices = page?.slices ?: return null
        for (slice in slices) {
            if (slice.span !== sel.span) continue
            val layout = slice.layout ?: continue
            val (line, x) = if (handle == Handle.START) {
                val l = layout.getLineForOffset(sel.range.start)
                l to layout.getPrimaryHorizontal(sel.range.start)
            } else {
                // an end at a line break belongs to the end of the line before it
                val l = layout.getLineForOffset((sel.range.end - 1).coerceAtLeast(sel.range.start))
                val x = if (layout.getLineForOffset(sel.range.end) == l) layout.getPrimaryHorizontal(sel.range.end)
                else layout.getLineRight(l)
                l to x
            }
            if (line < slice.firstLine || line >= slice.endLine) continue
            return (areaLeft + x) to lineBottomOnPage(slice, layout, line).toFloat()
        }
        return null
    }

    private fun handleBounds(handle: Handle): Rect? {
        val sel = selection ?: return null
        val (x, y) = handlePoint(sel, handle) ?: return null
        val d = (if (handle == Handle.START) handleLeft else handleRight) ?: return null
        val w = d.intrinsicWidth
        val left = if (handle == Handle.START) (x - w * 3 / 4).toInt() else (x - w / 4).toInt()
        return Rect(left, y.toInt(), left + w, y.toInt() + d.intrinsicHeight)
    }

    private fun drawHandle(canvas: Canvas, d: Drawable?, x: Float, y: Float, start: Boolean) {
        d ?: return
        val w = d.intrinsicWidth
        val left = if (start) (x - w * 3 / 4).toInt() else (x - w / 4).toInt()
        d.setBounds(left, y.toInt(), left + w, y.toInt() + d.intrinsicHeight)
        d.draw(canvas)
    }

    /** The handle under ([x], [y]) (with a finger-sized margin), or null. */
    fun handleAt(x: Float, y: Float): Handle? {
        val slop = 12 * resources.displayMetrics.density
        var best: Handle? = null
        var bestDist = Float.MAX_VALUE
        for (h in Handle.entries) {
            val r = handleBounds(h) ?: continue
            if (x < r.left - slop || x > r.right + slop || y < r.top - slop || y > r.bottom + slop) continue
            val d = abs(r.exactCenterX() - x) + abs(r.exactCenterY() - y)
            if (d < bestDist) {
                bestDist = d
                best = h
            }
        }
        return best
    }

    /** Area of the selection in view px, for the floating Copy / Glossary toolbar. */
    fun selectionRect(out: Rect) {
        val sel = selection ?: return
        val start = handlePoint(sel, Handle.START)
        val end = handlePoint(sel, Handle.END)
        if (start == null || end == null) return
        val lineHeight = page?.slices?.firstOrNull { it.span === sel.span }?.layout?.let { l ->
            l.getLineBottom(0) - l.getLineTop(0)
        } ?: 0
        val top = minOf(start.second, end.second) - lineHeight
        val bottom = maxOf(start.second, end.second)
        val left = if (start.second == end.second) minOf(start.first, end.first) else areaLeft.toFloat()
        val right = if (start.second == end.second) maxOf(start.first, end.first) else (width - areaLeft).toFloat()
        out.set(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    private var dragging: Handle? = null
    private var dragDx = 0f
    private var dragDy = 0f

    val isDragging: Boolean get() = dragging != null

    /** Starts moving [handle] with the finger at ([x], [y]). */
    fun beginDrag(handle: Handle, x: Float, y: Float) {
        val sel = selection ?: return
        val (hx, hy) = handlePoint(sel, handle) ?: return
        dragging = handle
        // aim at the middle of the line the handle hangs under, wherever the finger grabbed it
        val lineHeight = page?.slices?.firstOrNull { it.span === sel.span }?.layout?.let { l ->
            l.getLineBottom(0) - l.getLineTop(0)
        } ?: 0
        dragDx = hx - x
        dragDy = hy - lineHeight / 2f - y
    }

    /** Moves the dragged handle; true if the selection changed. */
    fun dragTo(x: Float, y: Float): Boolean {
        val handle = dragging ?: return false
        val sel = selection ?: return false
        val (min, max) = visibleRange(sel.span) ?: return false
        val offset = offsetInParagraph(sel.span, x + dragDx, y + dragDy) ?: return false
        val (range, now) = PageSelection.drag(sel.range, handle, offset, min, max) ?: return false
        dragging = now
        if (range == sel.range) return false
        selection = Selection(sel.span, range)
        invalidate()
        return true
    }

    fun endDrag() {
        dragging = null
    }

    /** The caret offset in [span] nearest to ([x], [y]), looking only at its lines on this page. */
    private fun offsetInParagraph(span: TextSpan, x: Float, y: Float): Int? {
        val slices = page?.slices?.filter { it.span === span && it.layout != null } ?: return null
        if (slices.isEmpty()) return null
        var bestSlice = slices.first()
        var bestLine = bestSlice.firstLine
        var bestDist = Float.MAX_VALUE
        for (slice in slices) {
            val layout = slice.layout!!
            for (line in slice.firstLine until slice.endLine) {
                val top = lineTopOnPage(slice, layout, line)
                val bottom = lineBottomOnPage(slice, layout, line)
                val d = when {
                    y < top -> top - y
                    y >= bottom -> y - bottom
                    else -> 0f
                }
                if (d < bestDist) {
                    bestDist = d
                    bestSlice = slice
                    bestLine = line
                }
            }
        }
        return bestSlice.layout!!.getOffsetForHorizontal(bestLine, x - areaLeft)
    }
}
