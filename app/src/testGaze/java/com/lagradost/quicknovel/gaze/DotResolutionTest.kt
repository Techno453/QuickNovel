package com.lagradost.quicknovel.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DotResolutionTest {
    /** Dots on [lines], the signal 0.002 per line plus [noise] (uniform +-) per frame. */
    private fun dots(lines: List<Int>, noise: Float, frames: Int = 27, seed: Int = 1, slope: Float = 0.002f) =
        Random(seed).let { random ->
            lines.map { line -> line to List(frames) { 0.1f + slope * line + (random.nextFloat() * 2 - 1) * noise } }
        }

    @Test
    fun noiselessSignalPlacesEveryLine() {
        val r = DotResolution.fit(dots(listOf(0, 2, 4, 6, 8, 10, 12, 14), noise = 0f))!!
        assertEquals(0.002, r.slope, 1e-6)
        assertEquals(0.0, r.perDot, 1e-3)
        assertEquals(0.0, r.perFrame, 1e-3)
    }

    @Test
    fun frameNoiseAveragesDownPerDot() {
        // +-0.004 uniform is a standard deviation of 0.0023, about 1.15 lines per frame
        val r = DotResolution.fit(dots((0..14 step 2).toList() + (0..14 step 2).toList(), noise = 0.004f))!!
        assertEquals(1.15, r.perFrame, 0.15)
        assertTrue("per dot ${r.perDot}", r.perDot < 0.4)
    }

    @Test
    fun negativeSlopeIsStillInLines() {
        val r = DotResolution.fit(dots(listOf(0, 4, 8, 12), noise = 0f, slope = -0.01f))!!
        assertEquals(-0.01, r.slope, 1e-6)
        assertEquals(0.0, r.perDot, 1e-3)
    }

    @Test
    fun tooFewDotsOrFramesOrLines() {
        assertNull(DotResolution.fit(dots(listOf(0, 14), noise = 0f)))
        assertNull(DotResolution.fit(dots(listOf(0, 4, 8), noise = 0f, frames = 3)))
        assertNull(DotResolution.fit(dots(listOf(4, 4, 4), noise = 0.001f)))
    }

    @Test
    fun targetsCoverThePageWithoutRepeats() {
        val order = DotResolution.targets(lines = 15, random = Random(3))
        assertEquals(listOf(0, 2, 4, 6, 8, 10, 12, 14).flatMap { listOf(it, it) }, order.sorted())
        assertTrue(order.zipWithNext().none { (a, b) -> a == b })
        assertEquals(listOf(0, 3, 6, 8), DotResolution.targets(lines = 9, every = 3, times = 1, random = Random(1)).sorted())
    }
}
