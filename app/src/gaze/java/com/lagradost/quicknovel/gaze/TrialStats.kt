package com.lagradost.quicknovel.gaze

import com.lagradost.quicknovel.gaze.LineSweepDetector.Event
import com.lagradost.quicknovel.gaze.LineSweepDetector.Mark
import kotlin.math.abs

/**
 * What one reading trial found. Pure, unit tested in TrialStatsTest.
 *
 * Events in the first [SETTLE_MS] (the eyes travel from the Read button to the first line) and
 * the last [TAIL_MS] (they travel to the Done button) are not counted. Reading a screen of N lines
 * should give N - 1 sweeps.
 */
object TrialStats {
    const val SETTLE_MS = 800L
    const val TAIL_MS = 500L

    /** [medianLineMs]: median time between counted sweeps, null with fewer than two. */
    data class Result(val sweeps: Int, val rises: Int, val medianLineMs: Long?)

    fun of(marks: List<Mark>, startMs: Long, endMs: Long): Result {
        val counted = marks.filter { it.timeMs >= startMs + SETTLE_MS && it.timeMs <= endMs - TAIL_MS }
        val sweepTimes = counted.filter { it.event == Event.SWEEP }.map { it.timeMs }
        val gaps = sweepTimes.zipWithNext { a, b -> b - a }.sorted()
        return Result(
            sweeps = sweepTimes.size,
            rises = counted.count { it.event == Event.RISE },
            medianLineMs = gaps.getOrNull(gaps.size / 2),
        )
    }

    /**
     * Sweeps scored against the reader's own marks (a key press on reaching each new line).
     * [hits]: marks with a sweep within [toleranceMs], each sweep used once; [extra]: sweeps
     * matching no mark. Sweeps outside the counted window (see [of]) are ignored.
     */
    data class Score(val marks: Int, val hits: Int, val extra: Int)

    const val MARK_TOLERANCE_MS = 800L

    fun score(
        marks: List<Mark>,
        markTimes: List<Long>,
        startMs: Long,
        endMs: Long,
        toleranceMs: Long = MARK_TOLERANCE_MS,
    ): Score {
        val sweeps = marks
            .filter { it.event == Event.SWEEP && it.timeMs >= startMs + SETTLE_MS && it.timeMs <= endMs - TAIL_MS }
            .map { it.timeMs }
            .toMutableList()
        var hits = 0
        for (mark in markTimes.sorted()) {
            val nearest = sweeps.minByOrNull { abs(it - mark) } ?: break
            if (abs(nearest - mark) <= toleranceMs) {
                hits++
                sweeps.remove(nearest)
            }
        }
        return Score(markTimes.size, hits, sweeps.size)
    }

    /**
     * How well an up-down signal tells which line is being read. [values] are calibrated so the
     * first line is 0 and the last is 1 (null: no usable frame); the line the reader is on is 0
     * before the first of [markTimes] and k after the k-th. Returns the median distance in lines
     * between the two over the counted window, null without usable frames.
     */
    fun lineError(
        times: List<Long>,
        values: List<Float?>,
        markTimes: List<Long>,
        lines: Int,
        startMs: Long,
        endMs: Long,
    ): Float? {
        require(times.size == values.size) { "one value per time" }
        if (lines < 2) return null
        val sortedMarks = markTimes.sorted()
        val errors = ArrayList<Float>()
        for (i in times.indices) {
            val t = times[i]
            val v = values[i] ?: continue
            if (v.isNaN() || t < startMs + SETTLE_MS || t > endMs - TAIL_MS) continue
            val line = sortedMarks.count { it <= t }.coerceAtMost(lines - 1)
            errors += abs(v * (lines - 1) - line)
        }
        return if (errors.isEmpty()) null else median(errors)
    }
}
