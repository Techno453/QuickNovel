package com.lagradost.quicknovel.ui.reader.paged

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TTSHelper

/**
 * Draws one [ReaderPage.Text]: each [Slice] is the visible line range of a paragraph's
 * StaticLayout, translated to its place on the page and clipped to those lines. The TTS line
 * being read is highlighted behind the text.
 */
@SuppressLint("ViewConstructor")
class PageTextView(context: Context, private val ttsLine: () -> TTSHelper.TTSLine?) : View(context) {
    var page: ReaderPage.Text? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Text area insets inside this view (px). */
    var areaLeft = 0
    var areaTop = 0

    var ruleColor: Int = 0

    private val rulePaint = Paint()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.readerHightlightedTextColor)
        pathEffect = CornerPathEffect(6f * context.resources.displayMetrics.density)
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = page ?: return
        val line = ttsLine()
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
            layout.draw(canvas)
            canvas.restore()
        }
    }

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
}
