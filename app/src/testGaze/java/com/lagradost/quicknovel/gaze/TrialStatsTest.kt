package com.lagradost.quicknovel.gaze

import com.lagradost.quicknovel.gaze.LineSweepDetector.Event
import com.lagradost.quicknovel.gaze.LineSweepDetector.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrialStatsTest {
    @Test
    fun settleAndTailAreNotCounted() {
        val marks = listOf(
            Mark(1_300, Event.SWEEP), // eyes going from the Read button to line 1
            Mark(4_000, Event.SWEEP),
            Mark(6_500, Event.SWEEP),
            Mark(9_000, Event.RISE),
            Mark(9_800, Event.SWEEP), // eyes going to the Done button
        )
        val r = TrialStats.of(marks, startMs = 1_000, endMs = 10_000)
        assertEquals(2, r.sweeps)
        assertEquals(1, r.rises)
        assertEquals(2_500L, r.medianLineMs)
    }

    @Test
    fun windowEdgesAreCounted() {
        val marks = listOf(Mark(1_800, Event.SWEEP), Mark(9_500, Event.SWEEP))
        assertEquals(2, TrialStats.of(marks, startMs = 1_000, endMs = 10_000).sweeps)
    }

    @Test
    fun medianLineTime() {
        val marks = listOf(2_000L, 4_000L, 7_000L, 9_500L).map { Mark(it, Event.SWEEP) }
        assertEquals(2_500L, TrialStats.of(marks, startMs = 0, endMs = 20_000).medianLineMs)
    }

    @Test
    fun noLineTimeFromOneSweep() {
        assertNull(TrialStats.of(listOf(Mark(5_000, Event.SWEEP)), 0, 20_000).medianLineMs)
    }

    @Test
    fun sweepsScoredAgainstLineMarks() {
        val sweeps = listOf(3_000L, 5_600L, 6_400L, 11_000L).map { Mark(it, Event.SWEEP) } +
                Mark(8_000, Event.RISE)
        // 3_000 and 5_600 match a mark; 6_400 is a second sweep near the same mark; 8_200 has none
        val s = TrialStats.score(sweeps, listOf(3_300L, 5_900L, 8_200L), startMs = 0, endMs = 20_000)
        assertEquals(TrialStats.Score(marks = 3, hits = 2, extra = 2), s)
    }

    @Test
    fun lineErrorFollowsTheMarks() {
        // 5 lines, marks at 2 s, 4 s, 6 s, 8 s; the signal is right on each line, then half a
        // line low throughout
        val times = (1_000L..9_900L step 100).toList()
        val exact = times.map { t -> (t / 2_000).toFloat().coerceAtMost(4f) / 4f }
        val marks = listOf(2_000L, 4_000L, 6_000L, 8_000L)
        assertEquals(0f, TrialStats.lineError(times, exact, marks, 5, 0, 20_000)!!, 1e-5f)
        val low = exact.map { it - 0.5f / 4f }
        assertEquals(0.5f, TrialStats.lineError(times, low, marks, 5, 0, 20_000)!!, 1e-5f)
    }

    @Test
    fun lineErrorSkipsMissingFrames() {
        val times = listOf(1_000L, 2_000L, 3_000L)
        assertNull(TrialStats.lineError(times, listOf(null, Float.NaN, null), emptyList(), 5, 0, 20_000))
    }

    @Test
    fun scoreIgnoresSettleAndTail() {
        val sweeps = listOf(500L, 19_800L).map { Mark(it, Event.SWEEP) }
        assertEquals(TrialStats.Score(1, 0, 0), TrialStats.score(sweeps, listOf(10_000L), 0, 20_000))
    }
}
