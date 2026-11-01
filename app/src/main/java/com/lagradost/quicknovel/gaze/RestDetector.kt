package com.lagradost.quicknovel.gaze

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Whether the phone lies at rest, screen up (on a table): then no one is reading it from the
 * front camera's view and the follower turns the camera off. Pure, unit tested in RestRuleTest.
 *
 * At rest means: for the last [WINDOW_MS] gravity stayed within about 20 degrees of the screen's
 * normal, and the acceleration's size varied by less than [STILL_RANGE] (a hand holding the phone
 * always shakes it a little more).
 */
class RestRule {
    private class Sample(val timeMs: Long, val size: Float, val up: Float)

    private val samples = ArrayDeque<Sample>()

    var resting = false
        private set

    /** One accelerometer reading (m/s²); returns the new state when it changed. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean? {
        val size = sqrt(x * x + y * y + z * z)
        if (size < 1e-3f) return null
        samples.addLast(Sample(timeMs, size, z / size))
        while (timeMs - samples.first().timeMs > WINDOW_MS) samples.removeFirst()
        val now = timeMs - samples.first().timeMs >= WINDOW_MS * 3 / 4 &&
                samples.all { it.up >= FLAT_COS } &&
                samples.maxOf { it.size } - samples.minOf { it.size } < STILL_RANGE
        if (now == resting) return null
        resting = now
        return now
    }

    fun reset() {
        samples.clear()
        resting = false
    }

    companion object {
        const val WINDOW_MS = 2000L
        const val FLAT_COS = 0.94f
        const val STILL_RANGE = 0.3f
    }
}

/** [RestRule] on the accelerometer while started; [onChange] on the main thread. */
class RestDetector(context: Context, private val onChange: (Boolean) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rule = RestRule()
    private var started = false

    fun start() {
        if (started || accelerometer == null) return
        started = true
        sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Stops listening and forgets the state, without calling [onChange]. */
    fun stop() {
        if (!started) return
        started = false
        sensors?.unregisterListener(this)
        rule.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        rule.add(event.timestamp / 1_000_000, x, y, z)?.let(onChange)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
