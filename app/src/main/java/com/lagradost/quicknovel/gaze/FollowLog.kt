package com.lagradost.quicknovel.gaze

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * One follow session as a CSV next to the gaze test's trials (shared from the Gaze test app), so
 * real reading can tune the thresholds: a row per camera frame while following, and a row per
 * event (anchor, back, manual turn, rest). Written off the main thread.
 */
class FollowLog(dir: File, header: List<String>) : AutoCloseable {
    private val io = Executors.newSingleThreadExecutor()
    private val file = File(dir, "follow-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv")
    private val pending = StringBuilder()

    init {
        header.forEach { pending.append("# ").appendLine(it) }
        pending.appendLine("t_ms,valid,iris_norm,iris_v_raw,openness,line,lines,line_width,line_last,event,cause")
        flush()
    }

    fun frame(
        timeMs: Long, valid: Boolean, iris: Float, irisV: Float, openness: Float,
        line: Int, lines: Int, width: Float, last: Boolean, event: String?, cause: String?,
    ) {
        pending.append(timeMs).append(',').append(if (valid) 1 else 0).append(',')
            .append(num(iris)).append(',').append(num(irisV)).append(',').append(num(openness)).append(',')
            .append(line).append(',').append(lines).append(',').append(num(width)).append(',')
            .append(if (last) 1 else 0).append(',').append(event.orEmpty()).append(',').appendLine(cause.orEmpty())
        if (pending.length > FLUSH_CHARS) flush()
    }

    fun event(timeMs: Long, event: String) {
        pending.append(timeMs).append(",,,,,,,,,").append(event).appendLine(',')
    }

    fun flush() {
        if (pending.isEmpty()) return
        val text = pending.toString()
        pending.setLength(0)
        io.execute {
            file.parentFile?.mkdirs()
            file.appendText(text)
        }
    }

    override fun close() {
        flush()
        io.shutdown()
    }

    private fun num(value: Float) = if (value.isNaN()) "" else String.format(Locale.US, "%.5f", value)

    private companion object {
        const val FLUSH_CHARS = 16_000
    }
}
