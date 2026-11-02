package com.lagradost.quicknovel.gaze

/**
 * Finds return sweeps, the jump from the end of one line back to the start of the next, in a
 * horizontal gaze signal calibrated so the line start is 0 and the line end is 1
 * ([GazeCalibration]). Pure, unit tested in LineSweepDetectorTest.
 *
 * A sweep is a drop of at least [minDrop] below the highest value of the last [windowMs], at most
 * one per [minIntervalMs]. A drop sooner than that after a line end belongs to it (a corrective
 * jump, the end of a slow sweep, the sweep from a short line that ended on time) and is used up:
 * kept in the window it would count the moment the interval ran out, a second line for one
 * ([lineEnded] starts the interval for a line that ended without a sweep). A saccade is over within a frame or two; the window also bridges a
 * blink at the end of a line (the caller leaves blink frames out). Regressions inside a line are
 * shorter. Only the size and speed of the drop count, not where it starts or lands: the levels
 * drift between calibration and reading (by up to 0.4 in a trial on a phone), while the drop
 * stays clear of the noise (docs/gaze-test.md). Jumping back to reread a line from its start
 * looks the same as a sweep. A sweep can be slow (in the reader the face landmarks took up to
 * 0.7 s to come all the way back), so after one the signal is followed down until it rises by
 * [DESCENT_END] or [DESCENT_MAX_MS] pass: the rest of the way down is the same sweep.
 *
 * The mirror image, a rise of at least [minRise], is reported as [Event.RISE] for diagnosis
 * (many mean the calibration is reversed or the eyes went elsewhere; on short phone lines quick
 * reading towards the line end makes some). Rises have their own interval and never hold back a
 * sweep: a quick jump to the last word is often followed by the sweep within a second. A median
 * of three samples removes single-frame landmark glitches.
 */
class LineSweepDetector(
    private val minDrop: Float = 0.40f,
    private val minRise: Float = 0.45f,
    private val windowMs: Long = 400,
    private val minIntervalMs: Long = 900,
) {
    enum class Event { SWEEP, RISE }

    class Mark(val timeMs: Long, val event: Event)

    private val recent = ArrayDeque<Float>()
    private val windowTimes = ArrayDeque<Long>()
    private val windowValues = ArrayDeque<Float>()
    private var lastSweepMs: Long? = null
    private var lastRiseMs: Long? = null

    /** The last sample after the median filter. */
    var filtered = Float.NaN
        private set

    /** Where the last sweep started: the highest value of its window. */
    var lastPeak = Float.NaN
        private set

    /** Since the last sweep, while the eyes are still coming down from it (a slow sweep). */
    private var descentSinceMs: Long? = null
    private var descentLow = 0f

    fun reset() {
        recent.clear()
        windowTimes.clear()
        windowValues.clear()
        lastSweepMs = null
        lastRiseMs = null
        filtered = Float.NaN
        descentSinceMs = null
    }

    /** A line ended without a sweep (on time): drops within the interval from now belong to it. */
    fun lineEnded(timeMs: Long) {
        lastSweepMs = timeMs
    }

    /**
     * One valid sample (face found, eyes open); returns the event it completes, if any. [drop] and
     * [intervalMs] replace [minDrop] and [minIntervalMs] for this sample: a short line ends in a
     * smaller sweep, sooner ([FollowEngine]).
     */
    fun add(timeMs: Long, value: Float, drop: Float = minDrop, intervalMs: Long = minIntervalMs): Event? {
        recent.addLast(value)
        if (recent.size > 3) recent.removeFirst()
        val v = if (recent.size < 3) value else recent.sorted()[1]
        filtered = v

        // the rest of a sweep's way down is the same sweep, whatever the interval allows
        descentSinceMs?.let { since ->
            descentLow = minOf(descentLow, v)
            if (v <= descentLow + DESCENT_END && timeMs - since <= DESCENT_MAX_MS) {
                windowTimes.clear()
                windowValues.clear()
                windowTimes.addLast(timeMs)
                windowValues.addLast(v)
                return null
            }
            descentSinceMs = null
        }

        while (windowTimes.isNotEmpty() && timeMs - windowTimes.first() > windowMs) {
            windowTimes.removeFirst()
            windowValues.removeFirst()
        }
        var event: Event? = null
        if (windowValues.isNotEmpty()) {
            if (windowValues.max() - v >= drop && !since(lastSweepMs, timeMs, intervalMs)) {
                // too soon after the last line end: part of it
                windowTimes.clear()
                windowValues.clear()
            } else if (windowValues.max() - v >= drop) {
                event = Event.SWEEP
                lastSweepMs = timeMs
                lastPeak = windowValues.max()
                descentSinceMs = timeMs
                descentLow = v
                windowTimes.clear()
                windowValues.clear()
            } else if (v - windowValues.min() >= minRise && since(lastRiseMs, timeMs, minIntervalMs)) {
                event = Event.RISE
                lastRiseMs = timeMs
            }
        }
        windowTimes.addLast(timeMs)
        windowValues.addLast(v)
        return event
    }

    private fun since(last: Long?, timeMs: Long, intervalMs: Long) = last == null || timeMs - last >= intervalMs

    companion object {
        /** A sweep's descent is over once the signal rises this far above its lowest point... */
        const val DESCENT_END = 0.1f

        /** ...or after this long. */
        const val DESCENT_MAX_MS = 600L
    }
}
