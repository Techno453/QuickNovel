package com.lagradost.quicknovel.gaze

import kotlin.math.hypot

/**
 * Eye and head measures from MediaPipe Face Landmarker's 478 landmarks (the last ten are the
 * irises). Pure, unit tested in FaceGeometryTest.
 *
 * The iris position is the iris centre projected onto the corner-to-corner axis of its eye: 0 at
 * the corner with the smaller image x, 1 at the other, so both eyes move the same way and head
 * roll does not change it. Which iris belongs to which eye is decided by distance, not by index,
 * so a mirrored image or a swapped index convention cannot pair them wrong.
 */
object FaceGeometry {
    class Point(val x: Float, val y: Float)

    /**
     * [ratio]: mean iris position of both eyes (see above).
     * [vertical]: mean iris distance from the corner-to-corner line, square to it, over the eye
     * width; grows when the eyes turn down in the image (towards the chin).
     * [openness]: mean lid gap / eye width, drops towards 0 in a blink (and a little when looking
     * down, as the upper lid follows the eye).
     * [headTurn]: nose tip along the cheek-to-cheek axis, 0 when facing the camera; logged to tell
     * eye movement from head movement.
     * [headTilt]: nose tip along the forehead-to-chin axis, grows when the head tips forward.
     */
    data class Sample(
        val ratio: Float,
        val vertical: Float,
        val openness: Float,
        val headTurn: Float,
        val headTilt: Float,
    )

    const val LANDMARK_COUNT = 478

    /** Two corners, then the middle of the upper and of the lower lid. */
    private class Eye(val corner1: Int, val corner2: Int, val upperLid: Int, val lowerLid: Int)

    private val EYES = listOf(Eye(33, 133, 159, 145), Eye(362, 263, 386, 374))
    private val IRIS_CENTERS = intArrayOf(468, 473)
    private const val NOSE_TIP = 1
    private const val CHEEK_1 = 234
    private const val CHEEK_2 = 454
    private const val FOREHEAD = 10
    private const val CHIN = 152

    /**
     * [at] gives a landmark in pixels: MediaPipe's normalised x and y have different scales on a
     * non-square image. Null when an eye or the face has no width (a degenerate detection).
     */
    fun measure(at: (Int) -> Point): Sample? {
        val irises = IRIS_CENTERS.map(at)
        var ratio = 0f
        var vertical = 0f
        var openness = 0f
        for (eye in EYES) {
            val (a, b) = ordered(at(eye.corner1), at(eye.corner2))
            val width = distance(a, b)
            if (width < MIN_WIDTH) return null
            val middle = Point((a.x + b.x) / 2, (a.y + b.y) / 2)
            val iris = irises.minBy { distance(it, middle) }
            ratio += along(iris, a, b)
            vertical += across(iris, a, b)
            openness += distance(at(eye.upperLid), at(eye.lowerLid)) / width
        }
        val (c1, c2) = ordered(at(CHEEK_1), at(CHEEK_2))
        val forehead = at(FOREHEAD)
        val chin = at(CHIN)
        if (distance(c1, c2) < MIN_WIDTH || distance(forehead, chin) < MIN_WIDTH) return null
        return Sample(
            ratio = ratio / 2,
            vertical = vertical / 2,
            openness = openness / 2,
            headTurn = along(at(NOSE_TIP), c1, c2) - 0.5f,
            headTilt = along(at(NOSE_TIP), forehead, chin),
        )
    }

    private const val MIN_WIDTH = 1e-3f

    private fun ordered(a: Point, b: Point) = if (a.x <= b.x) a to b else b to a

    /** Where [p] falls along the axis from [a] (0) to [b] (1). */
    private fun along(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
    }

    /**
     * How far [p] is from the line through [a] and [b], square to it, in units of |ab|. Positive
     * on the side the axis turned a quarter clockwise points to: below a level a-to-b axis that
     * runs left to right, as image y grows downwards.
     */
    private fun across(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return ((p.y - a.y) * dx - (p.x - a.x) * dy) / (dx * dx + dy * dy)
    }

    private fun distance(a: Point, b: Point) = hypot(b.x - a.x, b.y - a.y)
}
