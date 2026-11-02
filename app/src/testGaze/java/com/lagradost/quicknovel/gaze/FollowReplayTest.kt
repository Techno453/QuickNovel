package com.lagradost.quicknovel.gaze

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Replays follow logs (`follow-*.csv`, docs/gaze-follow.md) and marked gaze-test trials
 * (`trial-*.csv` with `marks_ms`, docs/gaze-test.md) through [FollowEngine] to compare detector
 * settings. Skipped unless given a folder of logs:
 *
 *     ./gradlew :app:testGazeUnitTest --tests '*FollowReplayTest*' -Dreplay=<dir>
 *
 * Follow logs have no truth, so it prints what tells of over-counting: a line ended on time and
 * then by a sweep within 1 s, or two sweeps within 0.8 s. Marked trials have a key press at every
 * real line change: it prints how many were found and how many sweeps had no press.
 */
class FollowReplayTest {
    /** [engine] starts at its own sensitivity; with [logged] the log's setting and changes apply. */
    private class Config(val name: String, val logged: Boolean = false, val engine: () -> FollowEngine)

    private val configs = listOf(
        Config("logged sensitivity", logged = true) { FollowEngine() },
        Config("all less sensitive") { FollowEngine().apply { dropScale = FollowEngine.SENSITIVITIES[0] } },
        Config("all more sensitive") { FollowEngine().apply { dropScale = FollowEngine.SENSITIVITIES[2] } },
    )

    @Test
    fun replay() {
        val dir = System.getProperty("replay")
        assumeTrue("set -Dreplay=<dir with follow-*.csv / trial-*.csv>", dir != null)
        val files = File(dir!!).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        for (config in configs) {
            println("== ${config.name}")
            for (file in files) {
                val text = file.readLines()
                when {
                    file.name.startsWith("follow-") -> println("  ${file.name}: ${follow(text, config.engine(), config.logged)}")
                    file.name.startsWith("trial-") -> trial(text, config.engine())?.let { println("  ${file.name}: $it") }
                }
            }
        }
    }

    private class Ended(val timeMs: Long, val cause: FollowEngine.Cause)

    private fun follow(text: List<String>, engine: FollowEngine, logged: Boolean): String {
        if (logged) {
            text.firstNotNullOfOrNull { Regex("drop_scale=([0-9.]+)").find(it) }?.let { engine.dropScale = it.groupValues[1].toFloat() }
        }
        val rows = text.filter { !it.startsWith("#") }
        val col = rows.first().split(',').withIndex().associate { it.value to it.index }
        val ended = ArrayList<Ended>()
        var started = false
        var validMs = 0L
        var lastMs: Long? = null
        for (row in rows.drop(1)) {
            val c = row.split(',')
            val t = c[col.getValue("t_ms")].toLongOrNull() ?: continue
            if (c[col.getValue("valid")].isEmpty()) {
                // what GazeFollower did around the engine
                when (c[col.getValue("event")]) {
                    "ANCHOR" -> {
                        engine.startPage(fresh = false, settleMs = if (started) 800 else 1500)
                        started = true
                    }

                    "BACK", "FORWARD" -> engine.moved()
                    "DRAG_END" -> engine.startPage(fresh = false, settleMs = 800)
                    "MANUAL_TURN" -> engine.startPage(fresh = true)
                    else -> if (logged) {
                        c[col.getValue("event")].removePrefix("SENSITIVITY_").toFloatOrNull()?.let { engine.dropScale = it }
                    }
                }
                continue
            }
            val valid = c[col.getValue("valid")] == "1"
            if (valid) lastMs?.let { validMs += minOf(t - it, 250) }
            lastMs = if (valid) t else null
            val event = engine.onFrame(
                t, valid,
                c[col.getValue("iris_norm")].toFloatOrNull() ?: Float.NaN,
                c[col.getValue("iris_v_raw")].toFloatOrNull() ?: Float.NaN,
                FollowEngine.Line(c[col.getValue("line_width")].toFloat(), c[col.getValue("line_last")] == "1"),
            )
            if (event == FollowEngine.Event.NEXT_LINE || event == FollowEngine.Event.TURN) {
                ended += Ended(t, engine.lastCause!!)
                if (event == FollowEngine.Event.TURN) engine.startPage(fresh = true)
            }
        }
        val timedThenSwept = ended.zipWithNext().count { (a, b) ->
            a.cause == FollowEngine.Cause.TIMEOUT && b.cause == FollowEngine.Cause.SWEEP && b.timeMs - a.timeMs < 1000
        }
        val sweptTwice = ended.zipWithNext().count { (a, b) ->
            a.cause == FollowEngine.Cause.SWEEP && b.cause == FollowEngine.Cause.SWEEP && b.timeMs - a.timeMs < 800
        }
        val perLine = if (ended.isEmpty()) 0.0 else validMs / 1000.0 / ended.size
        // -Dreplay.times=1: every line end, in seconds from the first row, to compare settings
        val first = rows.drop(1).firstNotNullOfOrNull { it.substringBefore(',').toLongOrNull() } ?: 0L
        if (System.getProperty("replay.times") != null) {
            println("    " + ended.joinToString(" ") { String.format(Locale.US, "%.2f%s", (it.timeMs - first) / 1000.0, if (it.cause == FollowEngine.Cause.TIMEOUT) "t" else "") })
        }
        return String.format(
            Locale.US, "%3d lines (%d on time), %.2f s a line; on time then swept <1 s: %d, swept twice <0.8 s: %d",
            ended.size, ended.count { it.cause == FollowEngine.Cause.TIMEOUT }, perLine, timedThenSwept, sweptTwice,
        )
    }

    private fun trial(text: List<String>, engine: FollowEngine): String? {
        val meta = HashMap<String, String>()
        text.filter { it.startsWith("#") }.forEach { line ->
            line.drop(1).split(' ').filter { '=' in it }.forEach { meta[it.substringBefore('=')] = it.substringAfter('=') }
        }
        val marks = meta["marks_ms"]?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        if (marks.isEmpty()) return null
        val start = meta.getValue("start_ms").toLong()
        val end = meta.getValue("end_ms").toLong()
        val rows = text.filter { !it.startsWith("#") }
        val col = rows.first().split(',').withIndex().associate { it.value to it.index }
        engine.startPage(fresh = false, settleMs = meta["settle_ms"]?.toLongOrNull() ?: 800)
        val ended = ArrayList<Long>()
        for (row in rows.drop(1)) {
            val c = row.split(',')
            val t = c[col.getValue("t_ms")].toLong()
            if (t < start || t > end) continue
            val event = engine.onFrame(
                t, c[col.getValue("valid")] == "1",
                c[col.getValue("iris_norm")].toFloatOrNull() ?: Float.NaN,
                col["iris_v_raw"]?.let { c[it].toFloatOrNull() } ?: Float.NaN,
                FollowEngine.Line(1f, last = false),
            )
            if (event == FollowEngine.Event.NEXT_LINE) ended += t
        }
        // a press comes just after the sweep it marks
        val unmatched = ended.toMutableList()
        var found = 0
        for (mark in marks) {
            val hit = unmatched.filter { it in mark - 800..mark + 600 }.minByOrNull { kotlin.math.abs(it - mark) }
            if (hit != null) {
                found++
                unmatched -= hit
            }
        }
        return "${ended.size} lines; $found of ${marks.size} line changes found, ${unmatched.size} extra"
    }
}
