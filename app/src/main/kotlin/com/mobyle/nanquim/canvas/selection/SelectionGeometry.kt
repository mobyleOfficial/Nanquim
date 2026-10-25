package com.mobyle.nanquim.canvas.selection

import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object SelectionGeometry {
    const val TOLERANCE_PX = 0.05

    fun rectangle(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): List<Point> = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))

    fun ellipse(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): List<Point> {
        val radiusX = abs(right - left) / 2
        val radiusY = abs(bottom - top) / 2
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val radius = max(radiusX, radiusY)
        val segments =
            if (radius <= TOLERANCE_PX) {
                MINIMUM_ELLIPSE_SEGMENTS
            } else {
                val angle = 2 * acos((1 - TOLERANCE_PX / radius).coerceAtLeast(-1.0))
                ceil(2 * PI / angle).toInt().coerceIn(MINIMUM_ELLIPSE_SEGMENTS, MAXIMUM_ELLIPSE_SEGMENTS)
            }
        return List(segments) { index ->
            val angle = 2 * PI * index / segments
            Point(centerX + radiusX * cos(angle), centerY + radiusY * sin(angle))
        }
    }

    fun closedPath(nodes: List<PathNode>): List<Point> {
        if (nodes.size < 3) return emptyList()
        val count = nodes.size
        val points = mutableListOf<Point>()
        for (index in 0 until count) {
            val from = nodes[index]
            val to = nodes[(index + 1) % count]
            points += from.point
            if (from.corner || to.corner) continue
            val before = nodes[(index - 1 + count) % count].point
            val after = nodes[(index + 2) % count].point
            points += catmullRomInterior(before, from.point, to.point, after)
        }
        return points
    }

    fun catmullRomInterior(
        p0: Point,
        p1: Point,
        p2: Point,
        p3: Point,
    ): List<Point> {
        val chord = distance(p1, p2)
        if (chord < MINIMUM_CHORD_PX) return emptyList()
        val d01 = max(sqrt(distance(p0, p1)), KNOT_FLOOR)
        val d12 = max(sqrt(chord), KNOT_FLOOR)
        val d23 = max(sqrt(distance(p2, p3)), KNOT_FLOOR)
        val t0 = 0.0
        val t1 = t0 + d01
        val t2 = t1 + d12
        val t3 = t2 + d23
        val steps = ceil(chord / CURVE_STEP_PX).toInt().coerceIn(1, MAXIMUM_CURVE_STEPS)
        return (1 until steps).map { step ->
            val t = t1 + (t2 - t1) * step / steps
            val a1 = lerp(p0, p1, t0, t1, t)
            val a2 = lerp(p1, p2, t1, t2, t)
            val a3 = lerp(p2, p3, t2, t3, t)
            val b1 = lerp(a1, a2, t0, t2, t)
            val b2 = lerp(a2, a3, t1, t3, t)
            lerp(b1, b2, t1, t2, t)
        }
    }

    fun toFloatArray(points: List<Point>): FloatArray =
        FloatArray(points.size * 2) { index ->
            val point = points[index / 2]
            (if (index % 2 == 0) point.x else point.y).toFloat()
        }

    fun distance(
        a: Point,
        b: Point,
    ): Double = hypot(a.x - b.x, a.y - b.y)

    private fun lerp(
        a: Point,
        b: Point,
        ta: Double,
        tb: Double,
        t: Double,
    ): Point {
        val span = tb - ta
        if (span == 0.0) return a
        val weight = (t - ta) / span
        return Point(a.x + (b.x - a.x) * weight, a.y + (b.y - a.y) * weight)
    }

    private const val MINIMUM_ELLIPSE_SEGMENTS = 16
    private const val MAXIMUM_ELLIPSE_SEGMENTS = 8192
    private const val MINIMUM_CHORD_PX = 1e-6
    private val KNOT_FLOOR = 1e-3.pow(0.5)
    private const val CURVE_STEP_PX = 2.0
    private const val MAXIMUM_CURVE_STEPS = 256
}

data class PathNode(
    val point: Point,
    val corner: Boolean = false,
)
