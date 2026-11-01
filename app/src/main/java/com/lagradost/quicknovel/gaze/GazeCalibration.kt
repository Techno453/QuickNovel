package com.lagradost.quicknovel.gaze

import kotlin.math.abs

/**
 * Two-point calibration of a horizontal gaze signal: the reader looks at a dot at the left end of
 * a text line, then at the right end. [normalize] maps the left end to 0 and the right end to 1,
 * whatever the camera's mirroring or the signal's scale. Pure, unit tested in
 * GazeCalibrationTest.
 */
class GazeCalibration(val left: Float, val right: Float) {
    fun normalize(raw: Float): Float = (raw - left) / (right - left)

    /**
     * [snr]: the distance between the two dots in units of the signal's noise (a robust standard
     * deviation of the samples around each dot). A line end has to stand clear of the jitter:
     * below about 3, sweeps cannot be told from noise.
     */
    class Fit(val calibration: GazeCalibration, val snr: Float)

    companion object {
        const val MIN_SAMPLES = 8

        /**
         * From the samples taken while looking at each dot, the ones from while the eyes were
         * still travelling already left out. Null with fewer than [MIN_SAMPLES] per dot or when
         * the two dots give the same value.
         */
        fun fit(leftSamples: List<Float>, rightSamples: List<Float>): Fit? {
            if (leftSamples.size < MIN_SAMPLES || rightSamples.size < MIN_SAMPLES) return null
            val left = median(leftSamples)
            val right = median(rightSamples)
            val separation = abs(right - left)
            if (separation < 1e-6f) return null
            val noise = (spread(leftSamples, left) + spread(rightSamples, right)) / 2
            return Fit(GazeCalibration(left, right), separation / maxOf(noise, 1e-6f))
        }

        /** Median absolute deviation from [center], scaled to a standard deviation for normal noise. */
        private fun spread(samples: List<Float>, center: Float): Float =
            1.4826f * median(samples.map { abs(it - center) })
    }
}

fun median(values: List<Float>): Float {
    require(values.isNotEmpty()) { "median of nothing" }
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}
