package com.lagradost.quicknovel.gaze

import com.lagradost.quicknovel.gaze.FaceGeometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceGeometryTest {
    /**
     * A face looking at the camera: eyes 40 px wide, irises [irisShift] px right of and [irisDrop]
     * px below the eye middles, the nose tip at ([nose], [noseY]) between forehead (y 120) and
     * chin (y 320).
     */
    private fun face(
        irisShift: Float = 0f,
        irisDrop: Float = 0f,
        lidGap: Float = 8f,
        nose: Float = 170f,
        noseY: Float = 240f,
    ): MutableMap<Int, Point> = mutableMapOf(
        33 to Point(100f, 200f), 133 to Point(140f, 200f),
        159 to Point(120f, 200f - lidGap / 2), 145 to Point(120f, 200f + lidGap / 2),
        362 to Point(200f, 200f), 263 to Point(240f, 200f),
        386 to Point(220f, 200f - lidGap / 2), 374 to Point(220f, 200f + lidGap / 2),
        468 to Point(120f + irisShift, 200f + irisDrop), 473 to Point(220f + irisShift, 200f + irisDrop),
        1 to Point(nose, noseY), 234 to Point(70f, 230f), 454 to Point(270f, 230f),
        10 to Point(170f, 120f), 152 to Point(170f, 320f),
    )

    private fun measure(points: Map<Int, Point>) = FaceGeometry.measure { points.getValue(it) }!!

    @Test
    fun centredIrisIsHalfway() {
        val s = measure(face())
        assertEquals(0.5f, s.ratio, 1e-5f)
        assertEquals(0f, s.vertical, 1e-5f)
        assertEquals(0.2f, s.openness, 1e-5f)
        assertEquals(0f, s.headTurn, 1e-5f)
        assertEquals(0.6f, s.headTilt, 1e-5f)
    }

    @Test
    fun irisBelowTheCornersIsPositive() {
        assertEquals(0.1f, measure(face(irisDrop = 4f)).vertical, 1e-5f)
        assertEquals(-0.05f, measure(face(irisDrop = -2f)).vertical, 1e-5f)
        // Moving sideways does not move it up or down
        assertEquals(0.1f, measure(face(irisShift = 8f, irisDrop = 4f)).vertical, 1e-5f)
    }

    @Test
    fun noseLowerIsAHeadTilt() {
        assertEquals(0.7f, measure(face(noseY = 260f)).headTilt, 1e-5f)
    }

    @Test
    fun irisShiftMovesTheRatio() {
        assertEquals(0.7f, measure(face(irisShift = 8f)).ratio, 1e-5f)
        assertEquals(0.3f, measure(face(irisShift = -8f)).ratio, 1e-5f)
    }

    @Test
    fun cornerOrderDoesNotMatter() {
        val points = face(irisShift = 8f)
        val c1 = points.getValue(33)
        points[33] = points.getValue(133)
        points[133] = c1
        assertEquals(0.7f, measure(points).ratio, 1e-5f)
    }

    @Test
    fun irisesArePairedByDistanceNotIndex() {
        val points = face(irisShift = 8f)
        val i1 = points.getValue(468)
        points[468] = points.getValue(473)
        points[473] = i1
        assertEquals(0.7f, measure(points).ratio, 1e-5f)
    }

    @Test
    fun headRollDoesNotChangeTheRatio() {
        val angle = Math.toRadians(25.0)
        val rolled = face(irisShift = 8f).mapValues { (_, p) ->
            Point(
                (p.x * cos(angle) - p.y * sin(angle)).toFloat(),
                (p.x * sin(angle) + p.y * cos(angle)).toFloat(),
            )
        }
        val s = measure(rolled)
        assertEquals(0.7f, s.ratio, 1e-4f)
        assertEquals(0f, s.vertical, 1e-4f)
        assertEquals(0.2f, s.openness, 1e-4f)
        assertEquals(0.6f, s.headTilt, 1e-4f)
    }

    @Test
    fun blinkClosesTheEyes() {
        assertEquals(0.025f, measure(face(lidGap = 1f)).openness, 1e-5f)
    }

    @Test
    fun noseOffCentreIsAHeadTurn() {
        assertEquals(0.1f, measure(face(nose = 190f)).headTurn, 1e-5f)
    }

    @Test
    fun degenerateEyeGivesNothing() {
        val points = face()
        points[133] = points.getValue(33)
        assertNull(FaceGeometry.measure { points.getValue(it) })
    }
}
