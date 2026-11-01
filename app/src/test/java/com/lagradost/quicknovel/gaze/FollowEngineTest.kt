package com.lagradost.quicknovel.gaze

import com.lagradost.quicknovel.gaze.FollowEngine.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowEngineTest {
    /** Feeds a reader at 15 fps: [widths] per line on screen, events collected. */
    private class Reader(val engine: FollowEngine = FollowEngine()) {
        var timeMs = 1_000L
        val events = ArrayList<Pair<Long, Event>>()

        fun frame(x: Float, v: Float, width: Float, last: Boolean, valid: Boolean = true): Event? {
            timeMs += FRAME_MS
            return engine.onFrame(timeMs, valid, x, v, FollowEngine.Line(width, last))?.also { events += timeMs to it }
        }

        /**
         * Reads a line of [width] in [ms] (the eyes move from 0 to [width]) and sweeps back to the
         * start; [v] is the up-down value meanwhile. Returns the events of the line.
         */
        fun line(width: Float, ms: Long, v: Float = 0f, last: Boolean = false, sweep: Boolean = true): List<Event> {
            val before = events.size
            val frames = (ms / FRAME_MS).toInt()
            for (i in 0 until frames) frame(width * i / frames, v, width, last)
            if (sweep) repeat(3) { frame(0f, v, width, last) }
            return events.drop(before).map { it.second }
        }

        fun idle(ms: Long, v: Float = 0f, width: Float = 1f, last: Boolean = false): List<Event> {
            val before = events.size
            repeat((ms / FRAME_MS).toInt()) { frame(width, v, width, last) }
            return events.drop(before).map { it.second }
        }

        /** A page of [lines] full lines, the eyes going down by [step] per line; the events. */
        fun page(lines: Int, step: Float, from: Float = 0f): List<Event> {
            engine.startPage(fresh = true)
            return (0 until lines).flatMap { i ->
                line(1f, 1800, v = from + step * i, last = i == lines - 1)
            }
        }
    }

    @Test
    fun everyFullLineEndsInOneSweep() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false) // first frame starts the count
        r.engine.startPage(fresh = true)
        repeat(5) { assertEquals(listOf(Event.NEXT_LINE), r.line(1f, 2000)) }
        assertEquals(listOf(Event.TURN), r.line(1f, 2000, last = true))
        assertEquals(FollowEngine.Cause.SWEEP, r.engine.lastCause)
    }

    @Test
    fun paceIsLearnedFromFullLines() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        repeat(5) { r.line(1f, 1500) }
        // the line's reading frames plus the two frames the median filter needs to see the drop
        assertTrue("pace ${r.engine.paceMs}", r.engine.paceMs in 1500L..1750L)
    }

    @Test
    fun shortLineEndsInASmallerSweep() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // a line 0.3 wide ends in a 0.3 drop: under the 0.40 a full line needs, over its own 0.18
        assertEquals(listOf(Event.NEXT_LINE), r.line(0.3f, 900).take(1))
        assertEquals(FollowEngine.Cause.SWEEP, r.engine.lastCause)
    }

    @Test
    fun smallDropOnAFullLineIsNotASweep() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        val before = r.events.size
        // eyes at 0.6 of a full line jump back 0.3 (a regression) and read on
        repeat(10) { r.frame(0.6f, 0f, 1f, false) }
        repeat(10) { r.frame(0.3f, 0f, 1f, false) }
        assertEquals(before, r.events.size)
    }

    /** A full line read from [from] to [to] in [ms], then the eyes back to [from] over [backMs]. */
    private fun Reader.swing(from: Float, to: Float, ms: Long, backMs: Long = FRAME_MS * 2): List<Event> {
        val before = events.size
        val frames = (ms / FRAME_MS).toInt()
        for (i in 0 until frames) frame(from + (to - from) * i / frames, 0f, 1f, false)
        val back = (backMs / FRAME_MS).toInt().coerceAtLeast(1)
        for (i in 1..back) frame(to - (to - from) * i / back, 0f, 1f, false)
        repeat(3) { frame(from, 0f, 1f, false) }
        return events.drop(before).map { it.second }
    }

    @Test
    fun slowSweepIsCounted() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // the eyes come back to the line start over 0.6 s (as in the reader), not within a frame
        assertEquals(listOf(Event.NEXT_LINE), r.swing(0.2f, 0.9f, 1800, backMs = 600))
    }

    @Test
    fun smallerSweepsLowerTheDropNeeded() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // a 0.33 sweep on a full line is too small at first...
        assertEquals(emptyList<Event>(), r.swing(0.25f, 0.58f, 1500))
        // ...but after a few 0.5 sweeps the reader's sweeps are known to be small
        repeat(4) { assertEquals(listOf(Event.NEXT_LINE), r.swing(0.25f, 0.75f, 1800)) }
        assertEquals(listOf(Event.NEXT_LINE), r.swing(0.42f, 0.75f, 1500))
    }

    @Test
    fun overdueLineNeedsLess() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // a 0.35 drop within the line's time is a regression...
        assertEquals(emptyList<Event>(), r.swing(0.43f, 0.78f, 1200))
        // ...but a line read twice as long as the pace (2 s) ends with it
        repeat(40) { r.frame(0.43f, 0f, 1f, false) }
        assertEquals(listOf(Event.NEXT_LINE), r.swing(0.43f, 0.78f, 600))
    }

    @Test
    fun settleCountsNothing() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.engine.startPage(fresh = true, settleMs = 1500)
        // the eyes come from the menu at the top right: a big drop, not a line
        assertEquals(emptyList<Event>(), r.swing(0.2f, 1f, 600))
        repeat(12) { r.frame(0f, 0f, 1f, false) }
        assertEquals(listOf(Event.NEXT_LINE), r.line(1f, 2000))
    }

    @Test
    fun veryShortLineEndsOnTime() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // "Huh?": 0.1 of the width, no visible sweep; ends after max(500, 0.1 * 2000 * 1.6) ms
        val events = r.idle(700, width = 0.1f)
        assertEquals(listOf(Event.NEXT_LINE), events)
        assertEquals(FollowEngine.Cause.TIMEOUT, r.engine.lastCause)
    }

    @Test
    fun lastLineTurnsOnTimeWithoutASweep() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.engine.startPage(fresh = true)
        // 2.5 lines' time at the default pace of 2 s
        assertEquals(emptyList<Event>(), r.idle(4900, last = true))
        assertEquals(listOf(Event.TURN), r.idle(300, last = true))
        assertEquals(FollowEngine.Cause.TIMEOUT, r.engine.lastCause)
    }

    @Test
    fun noFacePausesAndTheReturnIsNoSweep() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.idle(500) // eyes at the line end
        val paused = (0 until 30).mapNotNull { r.frame(Float.NaN, Float.NaN, 1f, false, valid = false) }
        assertEquals(listOf(Event.PAUSE), paused)
        assertTrue(r.engine.paused)
        // back at the line start: a big drop from before, but not a sweep
        assertEquals(Event.RESUME, r.frame(0f, 0f, 1f, false))
        val after = (0 until 10).mapNotNull { r.frame(0f, 0f, 1f, false) }
        assertEquals(emptyList<Event>(), after)
    }

    @Test
    fun timeAwayDoesNotCount() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.engine.startPage(fresh = true)
        r.idle(4000, last = true)
        repeat(60) { r.frame(Float.NaN, Float.NaN, 1f, true, valid = false) }
        r.frame(1f, 0f, 1f, true)
        // 4 s were read before the 4 s away: the 5 s timeout is still 1 s off
        assertEquals(emptyList<Event>(), r.idle(800, last = true))
        assertEquals(listOf(Event.TURN), r.idle(400, last = true))
    }

    @Test
    fun blinksDoNotPause() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        repeat(4) { r.frame(Float.NaN, Float.NaN, 1f, false, valid = false) }
        assertEquals(null, r.frame(0.1f, 0f, 1f, false))
        assertTrue(!r.engine.paused)
    }

    @Test
    fun guardHoldsWhenTheEyesStayedHigh() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // two pages read straight teach that the eyes go down by about 0.01 per page
        assertEquals(Event.TURN, r.page(6, step = 0.002f).last())
        assertEquals(Event.TURN, r.page(6, step = 0.002f, from = 0.003f).last())
        // the counter reaches the last line while the eyes are still on the first lines
        r.engine.startPage(fresh = true)
        repeat(5) { r.line(1f, 1800, v = 0.0005f * it) }
        assertEquals(listOf(Event.HOLD), r.line(1f, 1800, v = 0.002f, last = true))
        // the eyes read on down; once they are low enough the page turns
        assertEquals(listOf(Event.HOLD), r.line(1f, 1800, v = 0.002f, last = true))
        assertEquals(listOf(Event.TURN), r.line(1f, 1800, v = 0.009f, last = true))
    }

    @Test
    fun guardGivesUpAfterMaxHolds() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.page(6, step = 0.002f)
        r.page(6, step = 0.002f)
        r.engine.startPage(fresh = true)
        repeat(5) { r.line(1f, 1800) }
        val events = (0..FollowEngine.MAX_HOLDS).flatMap { r.line(1f, 1800, last = true) }
        assertEquals(List(FollowEngine.MAX_HOLDS) { Event.HOLD } + Event.TURN, events)
    }

    @Test
    fun guardWaitsForPagesReadStraight() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // a page after a drag (not fresh) teaches nothing; one fresh page is not enough
        r.engine.startPage(fresh = false)
        (0 until 6).forEach { r.line(1f, 1800, v = 0.002f * it, last = it == 5) }
        r.page(6, step = 0.002f)
        r.engine.startPage(fresh = true)
        repeat(5) { r.line(1f, 1800) }
        assertEquals(listOf(Event.TURN), r.line(1f, 1800, last = true))
    }

    @Test
    fun guardIsOffAfterAJump() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        r.page(6, step = 0.002f)
        r.page(6, step = 0.002f)
        // after a drag the eyes may start anywhere: no hold
        r.engine.startPage(fresh = false)
        repeat(5) { r.line(1f, 1800) }
        assertEquals(listOf(Event.TURN), r.line(1f, 1800, last = true))
    }

    @Test
    fun guardLearnsTheSignalsDirection() {
        val r = Reader()
        r.frame(0f, 0f, 1f, false)
        // this face's up-down signal falls as the eyes go down
        r.page(6, step = -0.002f)
        r.page(6, step = -0.002f)
        r.engine.startPage(fresh = true)
        repeat(5) { r.line(1f, 1800) }
        assertEquals(listOf(Event.HOLD), r.line(1f, 1800, last = true))
        assertEquals(listOf(Event.TURN), r.line(1f, 1800, v = -0.01f, last = true))
    }

    private companion object {
        const val FRAME_MS = 66L
    }
}
