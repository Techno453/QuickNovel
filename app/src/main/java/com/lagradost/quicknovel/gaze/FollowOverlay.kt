package com.lagradost.quicknovel.gaze

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.lagradost.quicknovel.ui.reader.ReaderFollow

/**
 * What the follower draws over the reader, never taking touches: a bar left of the counted line
 * (its colour says the state), the calibration dot and a short message.
 */
class FollowOverlay(private val activity: Activity) {
    enum class Look(val color: Int) {
        FOLLOWING(0xCC4FC3F7.toInt()),
        HOLD(0xDDFFB300.toInt()),
        PAUSED(0x77888888),
        RESTING(0x44888888),
    }

    private val density = activity.resources.displayMetrics.density
    private val marker = Marker(activity)
    private val dot = View(activity).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFB300.toInt())
        }
        visibility = View.INVISIBLE
    }
    private val label = TextView(activity).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        val pad = (12 * density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        background = GradientDrawable().apply {
            setColor(0xCC000000.toInt())
            cornerRadius = 8 * density
        }
        visibility = View.INVISIBLE
    }
    private val layer = FrameLayout(activity).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(marker, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val size = (DOT_DP * density).toInt()
        addView(dot, FrameLayout.LayoutParams(size, size))
        addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_HORIZONTAL or Gravity.TOP
        ).apply { topMargin = (LABEL_TOP_DP * density).toInt() })
    }
    private val origin = IntArray(2)

    fun show() {
        if (layer.parent != null) return
        activity.findViewById<FrameLayout>(android.R.id.content).addView(
            layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    fun hide() {
        (layer.parent as? FrameLayout)?.removeView(layer)
        hideDot()
        say(null)
        mark(null, Look.FOLLOWING)
    }

    /** The bar beside [line] (window pixels), or none. */
    fun mark(line: ReaderFollow.Line?, look: Look) {
        layer.getLocationInWindow(origin)
        marker.set(line?.let { RectF(it.left - 10 * density, it.top.toFloat(), it.left - 6 * density, it.bottom.toFloat()) }
            ?.apply { offset(-origin[0].toFloat(), -origin[1].toFloat()) }, look.color)
    }

    /** The calibration dot centred on ([x], [y]) in window pixels. */
    fun dotAt(x: Int, y: Int) {
        layer.getLocationInWindow(origin)
        val half = DOT_DP * density / 2
        dot.translationX = x - origin[0] - half
        dot.translationY = y - origin[1] - half
        dot.visibility = View.VISIBLE
    }

    fun hideDot() {
        dot.visibility = View.INVISIBLE
    }

    fun say(text: String?) {
        label.text = text
        label.visibility = if (text == null) View.INVISIBLE else View.VISIBLE
    }

    private class Marker(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var rect: RectF? = null

        fun set(rect: RectF?, color: Int) {
            if (rect == this.rect && color == paint.color) return
            this.rect = rect
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = rect ?: return
            canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, paint)
        }
    }

    private companion object {
        const val DOT_DP = 18
        const val LABEL_TOP_DP = 96
    }
}
