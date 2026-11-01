package com.lagradost.quicknovel.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class GazeCalibrationTest {
    private fun around(center: Float, noise: Float, n: Int = 30, seed: Int = 1): List<Float> {
        val random = Random(seed)
        return List(n) { center + (random.nextFloat() * 2 - 1) * noise }
    }

    @Test
    fun dotsMapToZeroAndOne() {
        val cal = GazeCalibration.fit(List(10) { 0.40f }, List(10) { 0.60f })!!.calibration
        assertEquals(0f, cal.normalize(0.40f), 1e-5f)
        assertEquals(1f, cal.normalize(0.60f), 1e-5f)
        assertEquals(0.5f, cal.normalize(0.50f), 1e-5f)
    }

    @Test
    fun mirroredCameraStillMapsLeftToZero() {
        val cal = GazeCalibration.fit(List(10) { 0.60f }, List(10) { 0.40f })!!.calibration
        assertEquals(0f, cal.normalize(0.60f), 1e-5f)
        assertEquals(1f, cal.normalize(0.40f), 1e-5f)
    }

    @Test
    fun outliersDoNotMoveTheDots() {
        val left = List(20) { 0.40f } + listOf(0.9f, 0.95f)
        val cal = GazeCalibration.fit(left, List(20) { 0.60f })!!.calibration
        assertEquals(0.40f, cal.left, 1e-5f)
    }

    @Test
    fun snrFallsWithNoise() {
        val quiet = GazeCalibration.fit(around(0.40f, 0.01f), around(0.60f, 0.01f, seed = 2))!!
        val noisy = GazeCalibration.fit(around(0.40f, 0.15f), around(0.60f, 0.15f, seed = 2))!!
        assertTrue("quiet ${quiet.snr}", quiet.snr > 10f)
        assertTrue("noisy ${noisy.snr}", noisy.snr < 3f)
    }

    @Test
    fun tooFewSamplesOrNoSeparation() {
        assertNull(GazeCalibration.fit(List(3) { 0.4f }, List(10) { 0.6f }))
        assertNull(GazeCalibration.fit(List(10) { 0.5f }, List(10) { 0.5f }))
        assertNotNull(GazeCalibration.fit(List(GazeCalibration.MIN_SAMPLES) { 0.4f }, List(10) { 0.6f }))
    }

    @Test
    fun medianOfEvenAndOdd() {
        assertEquals(2f, median(listOf(3f, 1f, 2f)), 0f)
        assertEquals(2.5f, median(listOf(4f, 1f, 2f, 3f)), 0f)
    }
}
