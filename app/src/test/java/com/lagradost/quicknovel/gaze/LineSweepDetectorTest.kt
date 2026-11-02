package com.lagradost.quicknovel.gaze

import com.lagradost.quicknovel.gaze.LineSweepDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class LineSweepDetectorTest {
    private val frameMs = 33L

    /**
     * [lines] lines read in [lineMs] each: the signal climbs from 0 to 1 and drops back at once.
     * [noise] is uniform +- noise, [shape] may edit each sample (time in the line, value).
     */
    private fun reading(
        lines: Int,
        lineMs: Long = 2500,
        noise: Float = 0f,
        seed: Int = 1,
        shape: (Long, Float) -> Float? = { _, v -> v },
    ): List<Pair<Long, Float>> {
        val random = Random(seed)
        val samples = ArrayList<Pair<Long, Float>>()
        var t = 0L
        repeat(lines) {
            var inLine = 0L
            while (inLine < lineMs) {
                val clean = inLine.toFloat() / lineMs
                val v = shape(inLine, clean)
                if (v != null) samples += t to v + (random.nextFloat() * 2 - 1) * noise
                t += frameMs
                inLine += frameMs
            }
        }
        return samples
    }

    private fun events(samples: List<Pair<Long, Float>>): List<Event> {
        val detector = LineSweepDetector()
        return samples.mapNotNull { (t, v) -> detector.add(t, v) }
    }

    @Test
    fun everyLineEndIsOneSweep() {
        val found = events(reading(lines = 20))
        assertEquals(19, found.count { it == Event.SWEEP })
        assertEquals(0, found.count { it == Event.RISE })
    }

    @Test
    fun noisySignalStillCountsLines() {
        for (seed in 1..5) {
            val found = events(reading(lines = 20, noise = 0.08f, seed = seed))
            assertEquals("seed $seed", 19, found.count { it == Event.SWEEP })
            assertEquals("seed $seed", 0, found.count { it == Event.RISE })
        }
    }

    @Test
    fun regressionsInsideALineAreNotSweeps() {
        // Twice per line the eyes jump back a quarter line for 200 ms
        val samples = reading(lines = 10) { t, v ->
            if (t in 800L..1000L || t in 1600L..1800L) v - 0.25f else v
        }
        assertEquals(listOf(Event.SWEEP).repeatList(9), events(samples))
    }

    @Test
    fun shortReadingRangeStillSweeps() {
        // As measured on a phone: lines read from 0.05 to 0.65, not 0 to 1
        val samples = reading(lines = 10, noise = 0.03f) { _, v -> 0.05f + 0.6f * v }
        assertEquals(9, events(samples).count { it == Event.SWEEP })
    }

    @Test
    fun shiftedReadingRangeStillSweeps() {
        // Second phone trial: the levels drifted after calibration, lines read from -0.3 to 0.6
        val samples = reading(lines = 10, noise = 0.03f) { _, v -> -0.3f + 0.9f * v }
        assertEquals(9, events(samples).count { it == Event.SWEEP })
    }

    @Test
    fun riseJustBeforeTheLineEndDoesNotHideTheSweep() {
        // Each line: a slow climb to 0.5, then one quick jump to the last word, then the sweep
        val samples = reading(lines = 3) { t, _ -> if (t < 2000) 0.25f * t / 1000 else 1f }
        val found = events(samples)
        assertEquals(3, found.count { it == Event.RISE })
        assertEquals(2, found.count { it == Event.SWEEP })
    }

    @Test
    fun blinkAtTheLineEndIsBridged() {
        // No samples in the last 150 ms of each line and the first 100 ms of the next
        val samples = reading(lines = 10) { t, v -> if (t > 2350 || t < 100) null else v }
        assertEquals(9, events(samples).count { it == Event.SWEEP })
    }

    @Test
    fun singleFrameGlitchIsIgnored() {
        val samples = reading(lines = 1, lineMs = 3000).map { (t, v) ->
            t to if (t == 33L * 30) v + 0.8f else v
        }
        assertEquals(emptyList<Event>(), events(samples))
    }

    @Test
    fun slowDescentIsNotASweep() {
        val samples = (0..60).map { i -> i * frameMs to 1f - i / 60f }
        assertEquals(emptyList<Event>(), events(samples))
    }

    @Test
    fun reversedCalibrationGivesRises() {
        val found = events(reading(lines = 10).map { (t, v) -> t to 1f - v })
        assertEquals(0, found.count { it == Event.SWEEP })
        assertEquals(9, found.count { it == Event.RISE })
    }

    @Test
    fun twoDropsCloseTogetherCountOnce() {
        val samples = listOf(0L to 1f, 33L to 1f, 66L to 1f, 99L to 0f, 132L to 0f) +
                listOf(165L to 1f, 198L to 1f, 231L to 1f, 264L to 0f, 297L to 0f, 330L to 0f)
        assertEquals(listOf(Event.SWEEP), events(samples).filter { it == Event.SWEEP })
    }

    @Test
    fun resetForgetsTheWindow() {
        val detector = LineSweepDetector()
        listOf(0L to 1f, 33L to 1f, 66L to 1f).forEach { (t, v) -> detector.add(t, v) }
        detector.reset()
        listOf(99L to 0f, 132L to 0f, 165L to 0f).forEach { (t, v) ->
            assertEquals(null, detector.add(t, v))
        }
    }

    @Test
    fun dropAndIntervalPerSample() {
        // two 0.3 drops 500 ms apart (two short lines)
        val samples = listOf(0L to 0.3f, 33L to 0.3f, 66L to 0.3f, 99L to 0f, 132L to 0f) +
                listOf(400L to 0.3f, 433L to 0.3f, 466L to 0.3f, 600L to 0f, 633L to 0f)
        assertEquals(emptyList<Event>(), events(samples))
        val detector = LineSweepDetector()
        val wide = samples.mapNotNull { (t, v) -> detector.add(t, v, drop = 0.25f) }
        assertEquals(listOf(Event.SWEEP), wide)
        detector.reset()
        val short = samples.mapNotNull { (t, v) -> detector.add(t, v, drop = 0.25f, intervalMs = 450) }
        assertEquals(listOf(Event.SWEEP, Event.SWEEP), short)
    }

    @Test
    fun slowSweepCountsOnce() {
        // 0.8 down to 0 over 0.5 s, and the short-line interval would allow a second sweep
        val samples = listOf(0L to 0.8f, 66L to 0.8f, 132L to 0.8f) +
                (1..8).map { i -> 132L + 66 * i to 0.8f - 0.1f * i } +
                (1..6).map { i -> 660L + 66 * i to 0f }
        val detector = LineSweepDetector(windowMs = 700)
        val found = samples.mapNotNull { (t, v) -> detector.add(t, v, drop = 0.2f, intervalMs = 300) }
        assertEquals(listOf(Event.SWEEP), found)
        assertEquals(0.8f, detector.lastPeak, 1e-5f)
    }

    @Test
    fun dropTooSoonAfterASweepIsUsedUp() {
        // a sweep at 0.1 s, then a jump right and back within the 900 ms interval: kept in the
        // 700 ms window it would count as soon as the interval ran out, with no new drop
        val samples = listOf(0L to 1f, 33L to 1f, 66L to 1f, 99L to 0f, 132L to 0f) +
                (1..15).map { i -> 132L + 33 * i to 0f } +
                listOf(700L to 0.6f, 733L to 0.6f, 766L to 0.6f, 800L to 0f, 833L to 0f) +
                (1..15).map { i -> 833L + 33 * i to 0f }
        val detector = LineSweepDetector(windowMs = 700)
        val found = samples.mapNotNull { (t, v) -> detector.add(t, v, drop = 0.4f) }
        assertEquals(listOf(Event.SWEEP), found.filter { it == Event.SWEEP })
    }

    @Test
    fun lineEndedOnTimeStartsTheInterval() {
        val detector = LineSweepDetector()
        detector.lineEnded(0L)
        // the sweep of the line that ended on time, 0.3 s later
        val samples = listOf(33L to 0.6f, 66L to 0.6f, 99L to 0.6f, 300L to 0f, 333L to 0f, 366L to 0f)
        assertEquals(emptyList<Event>(), samples.mapNotNull { (t, v) -> detector.add(t, v, drop = 0.4f) })
    }

    private fun <T> List<T>.repeatList(n: Int): List<T> = (1..n).flatMap { this }
}
