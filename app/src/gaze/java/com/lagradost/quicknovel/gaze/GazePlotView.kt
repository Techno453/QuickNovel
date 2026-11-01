package com.lagradost.quicknovel.gaze

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.lagradost.quicknovel.gaze.LineSweepDetector.Event
import com.lagradost.quicknovel.gaze.LineSweepDetector.Mark

/**
 * The gaze signal over time: live (the last [LIVE_SPAN_MS]) or a whole finished trial with its
 * sweeps (green) and rises (red). Calibrated values get a fixed axis with guides at the line
 * start (0) and end (1); raw values are scaled to fit. Reading looks like a sawtooth: a slow
 * climb along each line and a sharp drop at its end.
 */
class GazePlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val times = ArrayList<Long>()
    private val values = ArrayList<Float>()
    private val marks = ArrayList<Mark>()
    private val readerMarks = ArrayList<Long>()
    private val references = ArrayList<Float>()
    private var live = true
    private var calibrated = false

    private val density = resources.displayMetrics.density
    private val linePaint = stroke(0xFF4FC3F7.toInt(), 1.5f)
    private val guidePaint = stroke(0x55FFFFFF, 1f)
    private val sweepPaint = stroke(0xFF81C784.toInt(), 1.5f)
    private val risePaint = stroke(0xFFE57373.toInt(), 1.5f)
    private val readerMarkPaint = stroke(0xFFFFB300.toInt(), 3f)
    private val path = Path()

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
    }

    /** NaN [value] (no face, blink) breaks the line. */
    fun addLive(timeMs: Long, value: Float, calibrated: Boolean) {
        if (!live || calibrated != this.calibrated) clear()
        live = true
        this.calibrated = calibrated
        times += timeMs
        values += value
        while (times.isNotEmpty() && timeMs - times.first() > LIVE_SPAN_MS) {
            times.removeAt(0)
            values.removeAt(0)
        }
        invalidate()
    }

    /**
     * [lineMarks]: the reader's volume-up presses, drawn as orange ticks along the top.
     * [reference]: what the values should be (the dot test's line), drawn as an orange line.
     */
    fun showTrial(
        trialTimes: List<Long>,
        trialValues: List<Float>,
        trialMarks: List<Mark>,
        lineMarks: List<Long>,
        reference: List<Float>? = null,
    ) {
        clear()
        live = false
        calibrated = true
        times += trialTimes
        values += trialValues
        marks += trialMarks
        readerMarks += lineMarks
        if (reference != null) references += reference
        invalidate()
    }

    private fun clear() {
        times.clear()
        values.clear()
        marks.clear()
        readerMarks.clear()
        references.clear()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.argb(40, 255, 255, 255))
        if (times.size < 2) return
        val t1 = times.last()
        val t0 = if (live) t1 - LIVE_SPAN_MS else times.first()
        val span = maxOf(1L, t1 - t0).toFloat()
        var low = -0.3f
        var high = 1.3f
        if (!calibrated) {
            val finite = values.filter { !it.isNaN() }
            if (finite.isEmpty()) return
            low = finite.min()
            high = maxOf(finite.max(), low + 1e-4f)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        fun x(t: Long) = (t - t0) / span * w
        fun y(v: Float) = h - (v.coerceIn(low, high) - low) / (high - low) * h

        if (calibrated) {
            canvas.drawLine(0f, y(0f), w, y(0f), guidePaint)
            canvas.drawLine(0f, y(1f), w, y(1f), guidePaint)
        }
        for (mark in marks) {
            val paint = if (mark.event == Event.SWEEP) sweepPaint else risePaint
            canvas.drawLine(x(mark.timeMs), 0f, x(mark.timeMs), h, paint)
        }
        for (t in readerMarks) canvas.drawLine(x(t), 0f, x(t), h / 5, readerMarkPaint)
        if (references.size == times.size) {
            path.reset()
            for (i in times.indices) {
                if (i == 0) path.moveTo(x(times[i]), y(references[i])) else path.lineTo(x(times[i]), y(references[i]))
            }
            canvas.drawPath(path, readerMarkPaint)
        }
        path.reset()
        var drawing = false
        for (i in times.indices) {
            val v = values[i]
            if (v.isNaN() || (i > 0 && times[i] - times[i - 1] > GAP_MS)) {
                drawing = false
                if (v.isNaN()) continue
            }
            if (drawing) path.lineTo(x(times[i]), y(v)) else path.moveTo(x(times[i]), y(v))
            drawing = true
        }
        canvas.drawPath(path, linePaint)
    }

    companion object {
        const val LIVE_SPAN_MS = 10_000L

        /** Frames further apart than this are not joined (the camera stalled). */
        private const val GAP_MS = 250L
    }
}
