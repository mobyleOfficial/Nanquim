package com.mobyle.nanquim.document.shape

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

object QuickShapeStroke {
    const val OUTLINE_STEP = 1.0

    const val LINE_ANGLE_STEP = PI / 12

    const val THIN_MAXIMUM_STEP = 8.0
    const val THIN_TOLERANCE = 0.25

    const val CORNER_TURN = PI / 18
    private const val CORNER_NEIGHBORS = 2

    fun profile(
        original: List<ShapePoint>,
        outline: List<ShapePoint>,
    ): DoubleArray {
        require(original.isNotEmpty() && outline.isNotEmpty()) { "Empty stroke or outline" }
        val strokeLengths = cumulativeLengths(original)
        val outlineLengths = cumulativeLengths(outline)
        val strokeLength = strokeLengths.last()
        val outlineLength = outlineLengths.last()
        val result = DoubleArray(outline.size)
        if (original.size == 1 || strokeLength == 0.0) return result
        var index = 1
        for (point in outline.indices) {
            val fraction = if (outlineLength == 0.0) 0.0 else outlineLengths[point] / outlineLength
            val target = fraction * strokeLength
            while (index < original.size - 1 && strokeLengths[index] < target) index++
            val span = strokeLengths[index] - strokeLengths[index - 1]
            val within = if (span == 0.0) 0.0 else ((target - strokeLengths[index - 1]) / span).coerceIn(0.0, 1.0)
            result[point] = index - 1 + within
        }
        return result
    }

    fun resample(
        points: List<ShapePoint>,
        count: Int,
    ): List<ShapePoint> {
        require(points.isNotEmpty() && count >= 2) { "Nothing to resample" }
        if (points.size == 1) return List(count) { points[0] }
        val lengths = cumulativeLengths(points)
        val total = lengths.last()
        val result = ArrayList<ShapePoint>(count)
        var index = 1
        for (k in 0 until count) {
            val target = total * k / (count - 1)
            while (index < points.size - 1 && lengths[index] < target) index++
            val span = lengths[index] - lengths[index - 1]
            val within = if (span == 0.0) 0.0 else ((target - lengths[index - 1]) / span).coerceIn(0.0, 1.0)
            result += points[index - 1] + (points[index] - points[index - 1]) * within
        }
        return result
    }

    fun morph(
        stroke: List<ShapePoint>,
        outline: List<ShapePoint>,
        progress: Double,
    ): List<ShapePoint> {
        val t = progress.coerceIn(0.0, 1.0)
        if (t >= 1.0) return outline
        val eased = t * t * (3 - 2 * t)
        val from = matching(stroke, outline)
        return outline.indices.map { from[it] + (outline[it] - from[it]) * eased }
    }

    fun matching(
        points: List<ShapePoint>,
        outline: List<ShapePoint>,
    ): List<ShapePoint> =
        profile(points, outline).map { position ->
            val below = floor(position).toInt().coerceIn(0, points.lastIndex)
            val above = (below + 1).coerceAtMost(points.lastIndex)
            points[below] + (points[above] - points[below]) * (position - below)
        }

    fun strokeOutline(shape: RecognizedShape): List<ShapePoint> = thin(shape.outline(OUTLINE_STEP))

    fun thin(
        points: List<ShapePoint>,
        maximumStep: Double = THIN_MAXIMUM_STEP,
        tolerance: Double = THIN_TOLERANCE,
    ): List<ShapePoint> {
        if (points.size <= 2) return points
        val last = points.lastIndex
        val forced = BooleanArray(points.size)
        forced[0] = true
        forced[last] = true
        for (index in 1 until last) {
            val turn = QuickShape.angleBetween(points[index] - points[index - 1], points[index + 1] - points[index])
            if (turn >
                CORNER_TURN
            ) {
                for (near in index - CORNER_NEIGHBORS..index + CORNER_NEIGHBORS) if (near in 0..last) forced[near] = true
            }
        }
        val kept = mutableListOf(points[0])
        var anchor = 0
        while (anchor < last) {
            var end = anchor + 1
            var length = points[anchor].distanceTo(points[end])
            while (!forced[end] && end < last) {
                val next = end + 1
                val nextLength = length + points[end].distanceTo(points[next])
                if (nextLength > maximumStep) break
                if ((anchor + 1..end).any { ShapeFitting.segmentDistance(points[it], points[anchor], points[next]) > tolerance }) break
                end = next
                length = nextLength
            }
            kept += points[end]
            anchor = end
        }
        return kept
    }

    fun constrained(shape: RecognizedShape): RecognizedShape =
        when (shape) {
            is LineShape -> constrainedLine(shape)
            is EllipseShape -> {
                val radius = (shape.ellipse.radiusX + shape.ellipse.radiusY) / 2
                shape.copy(ellipse = shape.ellipse.copy(radiusX = radius, radiusY = radius), circle = true)
            }
            is PolygonShape ->
                when {
                    !shape.closed -> shape
                    shape.rectangle && shape.vertices.size == 4 -> square(shape)
                    else -> regularPolygon(shape)
                }
            else -> shape
        }

    private fun constrainedLine(line: LineShape): LineShape {
        val offset = line.end - line.start
        val length = offset.length()
        if (length == 0.0) return line
        val angle = (atan2(offset.y, offset.x) / LINE_ANGLE_STEP).roundToInt() * LINE_ANGLE_STEP
        return LineShape(line.start, line.start + ShapePoint(cos(angle), sin(angle)) * length)
    }

    private fun square(rectangle: PolygonShape): PolygonShape {
        val (first, second, _, fourth) = rectangle.vertices
        val center = ShapeFitting.centroid(rectangle.vertices)
        val side = sideLengths(rectangle.vertices).average()
        val along = (second - first).normalized()
        val across = ShapePoint(-along.y, along.x).let { if (it.dot(fourth - first) >= 0) it else it * -1.0 }
        val corner = center - along * (side / 2) - across * (side / 2)
        val vertices = listOf(corner, corner + along * side, corner + along * side + across * side, corner + across * side)
        return rectangle.copy(vertices = vertices)
    }

    private fun regularPolygon(polygon: PolygonShape): PolygonShape {
        val vertices = polygon.vertices
        val center = ShapeFitting.centroid(vertices)
        val radius = vertices.map { it.distanceTo(center) }.average()
        val start = atan2(vertices[0].y - center.y, vertices[0].x - center.x)
        val turn = if (QuickShape.signedArea(vertices) >= 0) 1.0 else -1.0
        val count = vertices.size
        val regular =
            (0 until count).map {
                val angle = start + turn * 2 * PI * it / count
                ShapePoint(center.x + radius * cos(angle), center.y + radius * sin(angle))
            }
        return polygon.copy(vertices = regular)
    }

    private fun sideLengths(vertices: List<ShapePoint>): List<Double> =
        vertices.indices.map { index ->
            val next = vertices[(index + 1) % vertices.size]
            vertices[index].distanceTo(next)
        }

    private fun cumulativeLengths(points: List<ShapePoint>): DoubleArray {
        val lengths = DoubleArray(points.size)
        for (index in 1 until points.size) lengths[index] = lengths[index - 1] + points[index].distanceTo(points[index - 1])
        return lengths
    }
}
