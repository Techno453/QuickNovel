package com.lagradost.quicknovel.gaze

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Best-case up-down resolution from the dot test (docs/gaze-test.md): the reader looks at dots on
 * known lines without reading, and a signal's mean per dot is fitted against the line by least
 * squares. How far the dots scatter around that line, converted into lines, is how finely the
 * signal can place the eyes. Pure, unit tested in DotResolutionTest.
 */
object DotResolution {
    /** A dot needs this many settled frames to count. */
    const val MIN_FRAMES = 8

    /**
     * [slope]: change of the signal per line. [perDot]: scatter of the dot means around the fit,
     * in lines (what a ~1 s look at one line gives). [perFrame]: the same for single frames.
     */
    data class Result(val slope: Double, val perDot: Double, val perFrame: Double, val dots: Int)

    /** [dots]: per dot, its line and the signal's values over its settled frames. */
    fun fit(dots: List<Pair<Int, List<Float>>>): Result? {
        val used = dots.filter { it.second.size >= MIN_FRAMES }
        if (used.size < 3 || used.map { it.first }.distinct().size < 2) return null
        val xs = used.map { it.first.toDouble() }
        val ys = used.map { (_, values) -> values.sumOf { it.toDouble() } / values.size }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        if (abs(slope) < 1e-12) return null
        val intercept = my - slope * mx

        fun inLines(value: Double, line: Double) = (value - intercept - slope * line) / slope
        val dotResiduals = xs.indices.map { inLines(ys[it], xs[it]) }
        // Two parameters were fitted to the dot means
        val perDot = sqrt(dotResiduals.sumOf { it * it } / maxOf(1, used.size - 2))
        val frameResiduals = used.flatMap { (line, values) ->
            values.map { inLines(it.toDouble(), line.toDouble()) }
        }
        val perFrame = sqrt(frameResiduals.sumOf { it * it } / frameResiduals.size)
        return Result(slope, perDot, perFrame, used.size)
    }

    /**
     * Dot targets for a page of [lines] lines: every [every]-th line from the first to the last
     * (the last always included), each [times] times, shuffled so no line follows itself.
     */
    fun targets(lines: Int, every: Int = 2, times: Int = 2, random: Random): List<Int> {
        if (lines < 2) return emptyList()
        val base = ((0 until lines step every).toList() + (lines - 1)).distinct()
        repeat(100) {
            val order = List(times) { base }.flatten().shuffled(random)
            if (order.zipWithNext().none { (a, b) -> a == b }) return order
        }
        return List(times) { base }.flatten()
    }
}
