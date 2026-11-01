package com.lagradost.quicknovel.gaze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RestRuleTest {
    /** Readings every 200 ms (SENSOR_DELAY_NORMAL) for [ms]; the last change, if any. */
    private fun RestRule.feed(fromMs: Long, ms: Long, reading: (Long) -> Triple<Float, Float, Float>): Boolean? {
        var change: Boolean? = null
        var t = fromMs
        while (t < fromMs + ms) {
            val (x, y, z) = reading(t)
            add(t, x, y, z)?.let { change = it }
            t += 200
        }
        return change
    }

    @Test
    fun flatAndStillOnATableRests() {
        val rule = RestRule()
        assertNull(rule.feed(0, 1000) { Triple(0.05f, -0.03f, 9.81f) })
        assertEquals(true, rule.feed(1000, 1000) { Triple(0.05f, -0.03f, 9.81f) })
        assertTrue(rule.resting)
    }

    @Test
    fun heldInTheHandDoesNot() {
        val rule = RestRule()
        // tilted towards the face, with the small shake of a hand
        assertNull(rule.feed(0, 5000) { t -> Triple(0.2f, 6.9f + 0.3f * sin(t / 90.0).toFloat(), 6.9f) })
        assertFalse(rule.resting)
    }

    @Test
    fun heldFlatButShakingDoesNot() {
        val rule = RestRule()
        assertNull(rule.feed(0, 5000) { t -> Triple(0f, 0f, 9.81f + 0.4f * cos(t / 70.0).toFloat()) })
    }

    @Test
    fun screenDownIsNotRest() {
        // reading lying down, the phone held flat above the face
        val rule = RestRule()
        assertNull(rule.feed(0, 5000) { Triple(0f, 0f, -9.81f) })
    }

    @Test
    fun pickedUpEndsRest() {
        val rule = RestRule()
        rule.feed(0, 3000) { Triple(0f, 0f, 9.81f) }
        assertTrue(rule.resting)
        assertEquals(false, rule.feed(3000, 400) { Triple(0f, 5f, 8.4f) })
    }
}
