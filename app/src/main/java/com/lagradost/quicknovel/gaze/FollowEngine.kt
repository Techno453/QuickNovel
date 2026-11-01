package com.lagradost.quicknovel.gaze

import kotlin.math.abs
import kotlin.math.sign

/**
 * The reader's follow mode without Android (docs/gaze-follow.md): counts the lines read and says
 * when to move on. The caller ([GazeFollower]) keeps which line on screen is the counted one and
 * passes its shape with every camera frame. Pure, unit tested in FollowEngineTest.
 *
 * - A **return sweep** ([LineSweepDetector] on the calibrated left-right signal) ends a line. In
 *   the reader sweeps came out smaller and slower than in the gaze test (0.3 to 0.7 of a line
 *   over half a second, against about 1.0 in a third of one), and smaller still once the text was
 *   gliding, so: the drop is looked for over [WINDOW_MS]; the drop needed for a full line is
 *   [SIZE_SHARE] of the reader's recent sweeps (between [ADAPTIVE_FLOOR] and [MAX_DROP],
 *   [MAX_DROP] until three are known); a line read for longer than it should take at the
 *   reader's pace needs less, down to [OVERDUE_SHARE] of that at twice the time. A short line
 *   (the end of a paragraph, a line of dialogue) ends in a smaller sweep, so the drop needed
 *   shrinks with its width, down to [MIN_DROP]; under [SHORT_LINE] of the width the sweep is too
 *   small to see and the line ends after the time it should take. The first moments after a
 *   start or a jump ([startPage]'s settle time) count nothing: the eyes are finding the text.
 * - The sweep after the **last line on screen** turns the page (paged mode); if none comes, the
 *   page turns after [LAST_LINE_PACES] lines' time. Scroll mode glides the text instead
 *   ([GazeFollower]) and the counted line never reaches the bottom.
 * - **Up-down** cannot place the eyes on a line (docs/gaze-test.md), so it only guards a paged
 *   turn, relative to the page itself: the eyes' height in the first seconds of a page against
 *   their height when the page is due to turn, compared with what that change was on earlier
 *   pages. Far less change (the counter ran ahead on rereads while the eyes stayed high) holds the
 *   page for another line, at most [MAX_HOLDS] times. The direction and size are learned from the
 *   first [GUARD_PAGES] pages read without corrections, so no up-down calibration is needed, and
 *   the zero point is taken afresh on every page (it drifts by several lines within a minute).
 * - No valid frame (face gone, eyes away or closed) for [PAUSE_AFTER_MS] pauses everything; the
 *   first frame back resumes without counting the jump as a sweep.
 */
class FollowEngine {
    enum class Event { NEXT_LINE, TURN, HOLD, PAUSE, RESUME }

    /** Why the last [Event.NEXT_LINE], [Event.TURN] or [Event.HOLD] came. */
    enum class Cause { SWEEP, TIMEOUT }

    /** The counted line: its text width as a share of the column, and whether it is the last line on screen. */
    data class Line(val width: Float, val last: Boolean)

    private val detector = LineSweepDetector(windowMs = WINDOW_MS)

    /** Full size of the last sweeps (peak to the lowest point after), for the drop needed. */
    private val sweepSizes = ArrayDeque<Float>()
    private var measuring = false
    private var measuredPeak = 0f
    private var measuredLow = 0f
    private var measuredSinceMs = 0L

    /** Valid time still to wait before counting, after [startPage]. */
    private var settleMs = 0L

    var paused = true
        private set
    var lastCause: Cause? = null
        private set

    /** Reading time per full line (median of the last ones), [DEFAULT_PACE_MS] until known. */
    val paceMs: Long get() = if (paces.isEmpty()) DEFAULT_PACE_MS else paces.sorted()[paces.size / 2]
    private val paces = ArrayDeque<Long>()

    private var lastValidMs: Long? = null

    /** Valid reading time on the counted line so far (the glide in scroll mode follows it). */
    var lineMs = 0L
        private set

    /** Valid time on the page. */
    private var pageMs = 0L

    // Page-turn guard
    private val recentV = ArrayDeque<Pair<Long, Float>>()
    private val pageStartV = ArrayList<Float>()
    private val pageChanges = ArrayDeque<Float>()
    private var pageGuarded = false
    private var pageTeaches = false
    private var holds = 0

    /**
     * A new page (or a new place): the counted line is its first line. For [settleMs] of valid
     * time nothing is counted (the eyes are on their way to the text).
     */
    fun startPage(fresh: Boolean, settleMs: Long = 0) {
        lineMs = 0
        pageMs = 0
        pageStartV.clear()
        holds = 0
        this.settleMs = settleMs
        if (settleMs > 0) detector.reset()
        // after a drag or a jump the eyes may be anywhere on the page: no guard, nothing learned
        pageGuarded = fresh
        pageTeaches = fresh
    }

    /** The counted line was moved by hand (one line back). The guard still holds; nothing learned. */
    fun moved() {
        lineMs = 0
        pageTeaches = false
    }

    /**
     * One camera frame. [valid]: face found and eyes open. [x]: calibrated left-right gaze (0 line
     * start, 1 line end); [v]: raw up-down gaze, NaN if unknown.
     */
    fun onFrame(timeMs: Long, valid: Boolean, x: Float, v: Float, line: Line): Event? {
        val last = lastValidMs
        if (!valid || x.isNaN()) {
            if (!paused && last != null && timeMs - last >= PAUSE_AFTER_MS) {
                paused = true
                return Event.PAUSE
            }
            return null
        }
        lastValidMs = timeMs
        if (paused || last == null || timeMs - last >= PAUSE_AFTER_MS) {
            // first frame, or the eyes are back: the jump since the last frame is no sweep
            detector.reset()
            recentV.clear()
            val wasPaused = paused && last != null
            paused = false
            addV(timeMs, v)
            return if (wasPaused) Event.RESUME else null
        }
        val step = minOf(timeMs - last, MAX_STEP_MS)
        if (settleMs > 0) {
            settleMs -= step
            return null
        }
        lineMs += step
        pageMs += step
        addV(timeMs, v)

        val sweep = detector.add(timeMs, x, dropFor(line.width), intervalFor(line.width)) ==
                LineSweepDetector.Event.SWEEP
        measureSweep(timeMs, newSweep = sweep && line.width >= FULL_LINE)
        val timedOut = when {
            line.last -> lineMs >= maxOf(LAST_LINE_MIN_MS, (LAST_LINE_PACES * paceMs).toLong())
            line.width < SHORT_LINE -> lineMs >= maxOf(SHORT_LINE_MIN_MS, (line.width * paceMs * SHORT_LINE_SLACK).toLong())
            else -> false
        }
        if (!sweep && !timedOut) return null
        lastCause = if (sweep) Cause.SWEEP else Cause.TIMEOUT
        if (sweep && line.width >= FULL_LINE && lineMs in PACE_MIN_MS..PACE_MAX_MS) {
            paces.addLast(lineMs)
            if (paces.size > PACE_LINES) paces.removeFirst()
        }
        lineMs = 0
        if (!line.last) return Event.NEXT_LINE

        val change = pageChange(timeMs)
        if (holds < MAX_HOLDS && change != null && eyesStayedHigh(change)) {
            holds++
            pageTeaches = false
            return Event.HOLD
        }
        if (pageTeaches && change != null) {
            pageChanges.addLast(change)
            if (pageChanges.size > GUARD_HISTORY) pageChanges.removeFirst()
        }
        return Event.TURN
    }

    /**
     * The drop that ends the counted line now: [SIZE_SHARE] of the recent sweeps, less on a short
     * line (its sweep is about as wide as the line) and on a line read for longer than it should
     * take at the reader's pace.
     */
    private fun dropFor(width: Float): Float {
        val usual = if (sweepSizes.size < MIN_SWEEPS) {
            MAX_DROP
        } else {
            (SIZE_SHARE * median(sweepSizes.toList())).coerceIn(ADAPTIVE_FLOOR, MAX_DROP)
        }
        val overdue = lineMs / maxOf(SHORT_LINE_MIN_MS.toFloat(), width * paceMs)
        val relax = if (overdue <= 1f) 1f else maxOf(OVERDUE_SHARE, 1f - (1f - OVERDUE_SHARE) * (overdue - 1f))
        return (usual * minOf(1f, width * SHORT_LINE_SCALE) * relax).coerceAtLeast(MIN_DROP)
    }

    /** Follows a full line's sweep down to its lowest point to learn its size. */
    private fun measureSweep(timeMs: Long, newSweep: Boolean) {
        val value = detector.filtered
        if (measuring) {
            measuredLow = minOf(measuredLow, value)
            if (timeMs - measuredSinceMs > SIZE_MEASURE_MS || value > measuredLow + SIZE_TURN) {
                measuring = false
                sweepSizes.addLast(measuredPeak - measuredLow)
                if (sweepSizes.size > SIZE_HISTORY) sweepSizes.removeFirst()
            }
        }
        if (newSweep) {
            measuring = true
            measuredPeak = detector.lastPeak
            measuredLow = value
            measuredSinceMs = timeMs
        }
    }

    private fun addV(timeMs: Long, v: Float) {
        if (v.isNaN()) return
        recentV.addLast(timeMs to v)
        while (timeMs - recentV.first().first > RECENT_V_MS) recentV.removeFirst()
        if (pageMs in PAGE_START_FROM_MS..PAGE_START_TO_MS) pageStartV.add(v)
    }

    /** How far the eyes went down since the page's first seconds, in raw up-down units. */
    private fun pageChange(timeMs: Long): Float? {
        if (pageStartV.size < MIN_V_SAMPLES) return null
        val end = recentV.filter { timeMs - it.first in END_V_FROM_MS..END_V_TO_MS }.map { it.second }
        if (end.size < MIN_V_SAMPLES) return null
        return end.average().toFloat() - pageStartV.average().toFloat()
    }

    private fun eyesStayedHigh(change: Float): Boolean {
        if (!pageGuarded || pageChanges.size < GUARD_PAGES) return false
        val usual = median(pageChanges.toList())
        if (usual == 0f) return false
        return change * sign(usual) < GUARD_SHARE * abs(usual)
    }

    companion object {
        const val PAUSE_AFTER_MS = 1500L

        /** Longer gaps between frames (a stalled camera) count as this much reading time. */
        const val MAX_STEP_MS = 250L

        const val DEFAULT_PACE_MS = 2000L
        const val PACE_LINES = 9
        const val PACE_MIN_MS = 600L
        const val PACE_MAX_MS = 8000L

        /** A line this wide (share of the column) or wider teaches the pace. */
        const val FULL_LINE = 0.8f

        /** Sweeps are looked for over this long: in the reader they took up to 0.7 s. */
        const val WINDOW_MS = 700L

        /** A full line needs this share of the median of the last [SIZE_HISTORY] sweeps... */
        const val SIZE_SHARE = 0.55f
        const val SIZE_HISTORY = 7
        const val MIN_SWEEPS = 3

        /** ...between these; [MAX_DROP] until [MIN_SWEEPS] are known. */
        const val ADAPTIVE_FLOOR = 0.22f
        const val MAX_DROP = 0.40f

        /** A sweep's size: from its peak to its lowest point, until the eyes rise this much or this long. */
        const val SIZE_TURN = 0.1f
        const val SIZE_MEASURE_MS = 600L

        /** A line read twice as long as it should take needs this share of the drop. */
        const val OVERDUE_SHARE = 0.6f

        /** A short line needs its width times this, of the drop (full from 2/3 of the width). */
        const val SHORT_LINE_SCALE = 1.5f

        /** No drop needed is below this (about six times the frame noise at a calibration SNR of 30). */
        const val MIN_DROP = 0.18f

        /** Sweeps closer together than this are one sweep; short lines are read faster. */
        fun intervalFor(width: Float) = if (width < 0.6f) 450L else 900L

        /** Narrower lines also end on time: their width times the pace, times the slack. */
        const val SHORT_LINE = 0.35f
        const val SHORT_LINE_MIN_MS = 500L
        const val SHORT_LINE_SLACK = 1.6f

        /** The last line on screen ends on time after this many lines' time (at least the minimum). */
        const val LAST_LINE_PACES = 2.5f
        const val LAST_LINE_MIN_MS = 4000L

        /** The page's first height is taken over this part of its first valid seconds. */
        const val PAGE_START_FROM_MS = 300L
        const val PAGE_START_TO_MS = 1800L

        /** The height at the turn: this window before the sweep (while reading the last line). */
        const val END_V_FROM_MS = 200L
        const val END_V_TO_MS = 1500L
        const val RECENT_V_MS = 2000L
        const val MIN_V_SAMPLES = 5

        /** Pages read without corrections needed before the guard acts, and how many it keeps. */
        const val GUARD_PAGES = 2
        const val GUARD_HISTORY = 6

        /** Less than this share of the usual change in height holds the page. */
        const val GUARD_SHARE = 0.35f
        const val MAX_HOLDS = 4
    }
}
