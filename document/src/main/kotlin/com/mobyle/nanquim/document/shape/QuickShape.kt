package com.mobyle.nanquim.document.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class ShapeKind(
    val label: String,
) {
    Line("line"),
    Arc("arc"),
    Circle("circle"),
    Ellipse("ellipse"),
    Rectangle("rectangle"),
    Polygon("polygon"),
    Polyline("polyline"),
    Curve("curve"),
    ;

    companion object {
        fun fromLabel(label: String): ShapeKind? = entries.firstOrNull { it.label == label }
    }
}

sealed interface RecognizedShape {
    val kind: ShapeKind

    fun outline(step: Double): List<ShapePoint>
}

data class LineShape(
    val start: ShapePoint,
    val end: ShapePoint,
) : RecognizedShape {
    override val kind get() = ShapeKind.Line

    override fun outline(step: Double): List<ShapePoint> = subdivide(listOf(start, end), step)
}

data class ArcShape(
    val center: ShapePoint,
    val radius: Double,
    val startAngle: Double,
    val sweep: Double,
) : RecognizedShape {
    override val kind get() = ShapeKind.Arc

    override fun outline(step: Double): List<ShapePoint> {
        val count = segmentCount(abs(sweep) * radius, step)
        return (0..count).map {
            val angle = startAngle + sweep * it / count
            ShapePoint(center.x + radius * cos(angle), center.y + radius * sin(angle))
        }
    }
}

data class EllipseShape(
    val ellipse: FittedEllipse,
    val startParameter: Double,
    val clockwise: Boolean,
    val circle: Boolean,
) : RecognizedShape {
    override val kind get() = if (circle) ShapeKind.Circle else ShapeKind.Ellipse

    override fun outline(step: Double): List<ShapePoint> {
        val a = ellipse.radiusX
        val b = ellipse.radiusY
        val perimeter = PI * (3 * (a + b) - sqrt((3 * a + b) * (a + 3 * b)))
        val count = max(MINIMUM_ELLIPSE_SEGMENTS, segmentCount(perimeter, step))
        val direction = if (clockwise) -1.0 else 1.0
        return (0..count).map { pointAt(startParameter + direction * 2 * PI * it / count) }
    }

    fun pointAt(parameter: Double): ShapePoint {
        val cosRotation = cos(ellipse.rotation)
        val sinRotation = sin(ellipse.rotation)
        val x = ellipse.radiusX * cos(parameter)
        val y = ellipse.radiusY * sin(parameter)
        return ShapePoint(ellipse.center.x + x * cosRotation - y * sinRotation, ellipse.center.y + x * sinRotation + y * cosRotation)
    }

    private companion object {
        const val MINIMUM_ELLIPSE_SEGMENTS = 32
    }
}

data class PolygonShape(
    val vertices: List<ShapePoint>,
    val closed: Boolean,
    val rectangle: Boolean = false,
) : RecognizedShape {
    override val kind
        get() =
            when {
                !closed -> ShapeKind.Polyline
                rectangle -> ShapeKind.Rectangle
                else -> ShapeKind.Polygon
            }

    override fun outline(step: Double): List<ShapePoint> = subdivide(if (closed) vertices + vertices.first() else vertices, step)
}

data class CurveShape(
    val segments: List<CubicBezier>,
) : RecognizedShape {
    override val kind get() = ShapeKind.Curve

    override fun outline(step: Double): List<ShapePoint> {
        val points = mutableListOf(segments.first().start)
        for (segment in segments) {
            val length =
                segment.start.distanceTo(segment.control1) + segment.control1.distanceTo(segment.control2) +
                    segment.control2.distanceTo(segment.end)
            val count = segmentCount(length, step)
            for (i in 1..count) points += segment.at(i.toDouble() / count)
        }
        return points
    }
}

data class ShapeCandidate(
    val shape: RecognizedShape,
    val error: Double,
)

data class ShapeRecognition(
    val shape: RecognizedShape?,
    val candidates: List<ShapeCandidate>,
)

data class QuickShapeSettings(
    val lineThreshold: Double = 0.02,
    val arcThreshold: Double = 0.03,
    val ellipseThreshold: Double = 0.04,
    val polygonThreshold: Double = 0.03,
    val closedDistance: Double = 0.15,
    val polygonTolerance: Double = 0.04,
    val circleAxisRatio: Double = 0.92,
    val maximumArcSweep: Double = 300 * PI / 180,
    val curveTolerance: Double = 0.02,
    val maximumCurveSegments: Int = 8,
    val maximumPolygonVertices: Int = 12,
    val minimumSize: Double = 12.0,
)

object QuickShape {
    fun recognize(
        stroke: List<ShapePoint>,
        settings: QuickShapeSettings = QuickShapeSettings(),
    ): ShapeRecognition {
        val points = prepare(stroke) ?: return ShapeRecognition(null, emptyList())
        val diagonal = boundingDiagonal(points)
        if (diagonal < settings.minimumSize) return ShapeRecognition(null, emptyList())
        val closure = closureStart(points, diagonal * settings.closedDistance)
        val candidates = mutableListOf<ShapeCandidate>()
        if (closure == null) {
            line(points, diagonal)?.let(candidates::add)
            arc(points, diagonal, settings)?.let(candidates::add)
            candidates += polygons(points, closed = false, diagonal, settings)
        } else {
            val loop = points.subList(closure, points.size)
            ellipse(loop, diagonal, settings)?.let(candidates::add)
            candidates += polygons(loop, closed = true, diagonal, settings)
        }
        val accepted = candidates.filter { it.error <= threshold(it.shape.kind, settings) }
        val best = accepted.minOfOrNull { it.error }
        if (best != null) {
            val chosen = accepted.filter { it.error <= best * TIE_FACTOR + TIE_SLACK }.minBy { complexity(it.shape.kind) }
            return ShapeRecognition(chosen.shape, candidates)
        }
        val curve = curve(points, diagonal, settings) ?: return ShapeRecognition(null, candidates)
        return ShapeRecognition(curve.shape, candidates + curve)
    }

    private fun complexity(kind: ShapeKind): Int =
        when (kind) {
            ShapeKind.Line -> 0
            ShapeKind.Arc -> 1
            ShapeKind.Circle -> 2
            ShapeKind.Ellipse -> 3
            ShapeKind.Rectangle -> 4
            ShapeKind.Polygon, ShapeKind.Polyline -> 5
            ShapeKind.Curve -> 6
        }

    private fun threshold(
        kind: ShapeKind,
        settings: QuickShapeSettings,
    ): Double =
        when (kind) {
            ShapeKind.Line -> settings.lineThreshold
            ShapeKind.Arc -> settings.arcThreshold
            ShapeKind.Circle, ShapeKind.Ellipse -> settings.ellipseThreshold
            ShapeKind.Rectangle, ShapeKind.Polygon, ShapeKind.Polyline -> settings.polygonThreshold
            ShapeKind.Curve -> Double.MAX_VALUE
        }

    fun prepare(stroke: List<ShapePoint>): List<ShapePoint>? {
        val distinct = mutableListOf<ShapePoint>()
        for (p in stroke) if (distinct.isEmpty() || distinct.last().distanceTo(p) > DUPLICATE_DISTANCE) distinct += p
        if (distinct.size < 3) return null
        var length = 0.0
        for (i in 1 until distinct.size) length += distinct[i].distanceTo(distinct[i - 1])
        val count = min(RESAMPLED_POINTS, max(MINIMUM_POINTS, length.roundToInt()))
        val resampled = resample(distinct, length, count)
        return resampled.indices.map { i ->
            if (i == 0 || i == resampled.lastIndex) {
                resampled[i]
            } else {
                (resampled[i - 1] + resampled[i] * 2.0 + resampled[i + 1]) * 0.25
            }
        }
    }

    private fun resample(
        points: List<ShapePoint>,
        length: Double,
        count: Int,
    ): List<ShapePoint> {
        val spacing = length / (count - 1)
        val result = mutableListOf(points.first())
        var segment = 1
        var walked = 0.0
        for (i in 1 until count - 1) {
            val target = spacing * i
            while (segment < points.lastIndex && walked + points[segment].distanceTo(points[segment - 1]) < target) {
                walked += points[segment].distanceTo(points[segment - 1])
                segment++
            }
            val a = points[segment - 1]
            val b = points[segment]
            val segmentLength = a.distanceTo(b)
            val t = if (segmentLength > 0) ((target - walked) / segmentLength).coerceIn(0.0, 1.0) else 0.0
            result += a + (b - a) * t
        }
        result += points.last()
        return result
    }

    fun boundingDiagonal(points: List<ShapePoint>): Double =
        hypot(
            points.maxOf { it.x } - points.minOf { it.x },
            points.maxOf { it.y } - points.minOf { it.y },
        )

    fun closureStart(
        points: List<ShapePoint>,
        distance: Double,
    ): Int? {
        val end = points.last()
        val searched = max(1, (points.size * CLOSURE_SEARCH).toInt())
        val nearest = (0 until searched).minBy { points[it].distanceTo(end) }
        return nearest.takeIf { points[it].distanceTo(end) <= distance && points.size - it >= MINIMUM_POINTS }
    }

    private fun line(
        points: List<ShapePoint>,
        diagonal: Double,
    ): ShapeCandidate {
        val fitted = ShapeFitting.line(points)
        val shape = LineShape(fitted.at(fitted.parameter(points.first())), fitted.at(fitted.parameter(points.last())))
        return ShapeCandidate(shape, ShapeFitting.meanDistance(points, listOf(shape.start, shape.end)) / diagonal)
    }

    private fun arc(
        points: List<ShapePoint>,
        diagonal: Double,
        settings: QuickShapeSettings,
    ): ShapeCandidate? {
        val circle = ShapeFitting.circle(points) ?: return null
        if (circle.radius > MAXIMUM_ARC_RADIUS * diagonal) return null
        val startAngle = atan2(points.first().y - circle.center.y, points.first().x - circle.center.x)
        var sweep = 0.0
        var travel = 0.0
        var previous = startAngle
        for (p in points) {
            val angle = atan2(p.y - circle.center.y, p.x - circle.center.x)
            var step = angle - previous
            if (step > PI) step -= 2 * PI
            if (step < -PI) step += 2 * PI
            sweep += step
            travel += abs(step)
            previous = angle
        }
        if (abs(sweep) >= settings.maximumArcSweep || travel > abs(sweep) * ARC_TRAVEL_FACTOR + ARC_TRAVEL_SLACK) return null
        val error = points.sumOf { abs(it.distanceTo(circle.center) - circle.radius) } / points.size / diagonal
        return ShapeCandidate(ArcShape(circle.center, circle.radius, startAngle, sweep), error)
    }

    private fun ellipse(
        loop: List<ShapePoint>,
        diagonal: Double,
        settings: QuickShapeSettings,
    ): ShapeCandidate? {
        val fitted = ShapeFitting.ellipse(loop) ?: return null
        if (max(fitted.radiusX, fitted.radiusY) > MAXIMUM_ELLIPSE_RADIUS * diagonal) return null
        val ratio = min(fitted.radiusX, fitted.radiusY) / max(fitted.radiusX, fitted.radiusY)
        val circle = if (ratio > settings.circleAxisRatio) ShapeFitting.circle(loop) else null
        val ellipse = circle?.let { FittedEllipse(it.center, it.radius, it.radius, 0.0) } ?: fitted
        val start = loop.first() - ellipse.center
        val cosRotation = cos(ellipse.rotation)
        val sinRotation = sin(ellipse.rotation)
        val localX = start.x * cosRotation + start.y * sinRotation
        val localY = -start.x * sinRotation + start.y * cosRotation
        val shape =
            EllipseShape(
                ellipse = ellipse,
                startParameter = atan2(localY / ellipse.radiusY, localX / ellipse.radiusX),
                clockwise = signedArea(loop) < 0,
                circle = circle != null,
            )
        return ShapeCandidate(shape, ShapeFitting.meanDistance(loop, shape.outline(diagonal / ERROR_OUTLINE_SEGMENTS)) / diagonal)
    }

    private fun polygons(
        points: List<ShapePoint>,
        closed: Boolean,
        diagonal: Double,
        settings: QuickShapeSettings,
    ): List<ShapeCandidate> {
        val tolerance = settings.polygonTolerance * diagonal
        var corners =
            if (closed) {
                val far = points.indices.maxBy { points[it].distanceTo(points[0]) }
                if (far == 0) return emptyList()
                (ShapeFitting.simplify(points, tolerance, 0, far) + ShapeFitting.simplify(points, tolerance, far, points.lastIndex).drop(1))
                    .filter { it != points.lastIndex }
            } else {
                ShapeFitting.simplify(points, tolerance)
            }
        corners = dropStraightCorners(points, corners, closed)
        val interior = if (closed) corners else corners.subList(1, corners.size - 1)
        if (closed && corners.size < 3 || !closed && interior.isEmpty() ||
            corners.size > settings.maximumPolygonVertices
        ) {
            return emptyList()
        }
        if (!interior.all { isSharp(points, corners, it, closed) }) return emptyList()
        val vertices = refineVertices(points, corners, closed, diagonal)
        val polygon = PolygonShape(vertices, closed)
        val polygonError = ShapeFitting.meanDistance(points, polygon.outline(Double.MAX_VALUE)) / diagonal
        val result = mutableListOf(ShapeCandidate(polygon, polygonError))
        if (closed && vertices.size == 4 && hasRightCorners(vertices)) {
            val rectangle = PolygonShape(rectangleCorners(vertices), closed = true, rectangle = true)
            result += ShapeCandidate(rectangle, ShapeFitting.meanDistance(points, rectangle.outline(Double.MAX_VALUE)) / diagonal)
        }
        return result
    }

    private fun dropStraightCorners(
        points: List<ShapePoint>,
        corners: List<Int>,
        closed: Boolean,
    ): List<Int> {
        val kept = corners.toMutableList()
        while (kept.size > (if (closed) 3 else 2)) {
            val candidates = if (closed) kept.indices else 1 until kept.lastIndex
            val flattest = candidates.minBy { turning(points, kept, it, closed) }
            if (turning(points, kept, flattest, closed) >= MINIMUM_CORNER_TURN) break
            kept.removeAt(flattest)
        }
        return kept
    }

    private fun turning(
        points: List<ShapePoint>,
        corners: List<Int>,
        index: Int,
        closed: Boolean,
    ): Double {
        val previous = points[corners[if (index == 0 && closed) corners.lastIndex else index - 1]]
        val next = points[corners[if (index == corners.lastIndex && closed) 0 else index + 1]]
        return angleBetween(points[corners[index]] - previous, next - points[corners[index]])
    }

    private fun isSharp(
        points: List<ShapePoint>,
        corners: List<Int>,
        pointIndex: Int,
        closed: Boolean,
    ): Boolean {
        val window = max(MINIMUM_CORNER_WINDOW, (points.size * CORNER_WINDOW).roundToInt())
        val count = points.size
        if (closed && (pointIndex <= window || pointIndex >= count - 1 - window)) return true

        fun at(i: Int): ShapePoint = if (closed) points[Math.floorMod(i, count)] else points[i.coerceIn(0, count - 1)]
        val local =
            (pointIndex - window..pointIndex + window).maxOf { center ->
                angleBetween(at(center) - at(center - window), at(center + window) - at(center))
            }
        val cornerTurn = turning(points, corners, corners.indexOf(pointIndex), closed)
        return local >= cornerTurn * SHARP_FRACTION
    }

    private fun refineVertices(
        points: List<ShapePoint>,
        corners: List<Int>,
        closed: Boolean,
        diagonal: Double,
    ): List<ShapePoint> {
        val count = points.size
        val edgeCount = if (closed) corners.size else corners.size - 1
        val edges =
            (0 until edgeCount).map { edge ->
                val from = corners[edge]
                val to = if (edge + 1 < corners.size) corners[edge + 1] else corners[0] + count
                val margin = ((to - from) * SIDE_MARGIN).toInt()
                val side = (from + margin..to - margin).map { points[it % count] }
                ShapeFitting.line(if (side.size >= 2) side else listOf(points[from % count], points[to % count]))
            }
        return corners.indices.map { i ->
            val original = points[corners[i]]
            when {
                !closed && i == 0 -> edges.first().let { it.at(it.parameter(original)) }
                !closed && i == corners.lastIndex -> edges.last().let { it.at(it.parameter(original)) }
                else -> {
                    val before = edges[if (i == 0) edgeCount - 1 else i - 1]
                    val after = edges[i % edgeCount]
                    val meeting = before.intersect(after)
                    if (meeting == null ||
                        angleBetween(before.direction, after.direction).let { min(it, PI - it) } < MINIMUM_CORNER_TURN ||
                        meeting.distanceTo(original) > diagonal * MAXIMUM_CORNER_SHIFT
                    ) {
                        original
                    } else {
                        meeting
                    }
                }
            }
        }
    }

    private fun hasRightCorners(vertices: List<ShapePoint>): Boolean {
        val turns =
            vertices.indices.map { i ->
                val previous = vertices[(i + 3) % 4]
                val next = vertices[(i + 1) % 4]
                val incoming = vertices[i] - previous
                val outgoing = next - vertices[i]
                atan2(incoming.x * outgoing.y - incoming.y * outgoing.x, incoming.dot(outgoing))
            }
        val sameWay = turns.all { it > 0 } || turns.all { it < 0 }
        return sameWay && turns.all { abs(abs(it) - PI / 2) <= RIGHT_ANGLE_TOLERANCE }
    }

    private fun rectangleCorners(vertices: List<ShapePoint>): List<ShapePoint> {
        var sumX = 0.0
        var sumY = 0.0
        for (i in vertices.indices) {
            val side = vertices[(i + 1) % 4] - vertices[i]
            val angle = atan2(side.y, side.x)
            sumX += side.length() * cos(4 * angle)
            sumY += side.length() * sin(4 * angle)
        }
        val angle = atan2(sumY, sumX) / 4
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val local = vertices.map { ShapePoint(it.x * cosAngle + it.y * sinAngle, -it.x * sinAngle + it.y * cosAngle) }
        val xs = local.map { it.x }.sorted()
        val ys = local.map { it.y }.sorted()
        val left = (xs[0] + xs[1]) / 2
        val right = (xs[2] + xs[3]) / 2
        val top = (ys[0] + ys[1]) / 2
        val bottom = (ys[2] + ys[3]) / 2
        var corners =
            listOf(ShapePoint(left, top), ShapePoint(right, top), ShapePoint(right, bottom), ShapePoint(left, bottom))
                .map { ShapePoint(it.x * cosAngle - it.y * sinAngle, it.x * sinAngle + it.y * cosAngle) }
        if ((signedArea(corners) < 0) != (signedArea(vertices) < 0)) corners = corners.reversed()
        val first = corners.indices.minBy { corners[it].distanceTo(vertices[0]) }
        return corners.drop(first) + corners.take(first)
    }

    private fun curve(
        points: List<ShapePoint>,
        diagonal: Double,
        settings: QuickShapeSettings,
    ): ShapeCandidate? {
        val segments = ShapeFitting.bezierCurve(points, settings.curveTolerance * diagonal, settings.maximumCurveSegments) ?: return null
        val shape = CurveShape(segments)
        return ShapeCandidate(shape, ShapeFitting.meanDistance(points, shape.outline(diagonal / ERROR_OUTLINE_SEGMENTS)) / diagonal)
    }

    fun signedArea(points: List<ShapePoint>): Double {
        var area = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            area += a.x * b.y - b.x * a.y
        }
        return area
    }

    internal fun angleBetween(
        a: ShapePoint,
        b: ShapePoint,
    ): Double {
        val lengths = a.length() * b.length()
        if (lengths < 1e-12) return 0.0
        return acos((a.dot(b) / lengths).coerceIn(-1.0, 1.0))
    }

    const val TIE_FACTOR = 1.5

    const val TIE_SLACK = 0.003
    private const val RESAMPLED_POINTS = 256
    private const val MINIMUM_POINTS = 16
    private const val DUPLICATE_DISTANCE = 1e-6
    private const val CLOSURE_SEARCH = 0.3
    private const val MAXIMUM_ARC_RADIUS = 10.0
    private const val MAXIMUM_ELLIPSE_RADIUS = 3.0
    private const val ARC_TRAVEL_FACTOR = 1.25
    private const val ARC_TRAVEL_SLACK = 0.2
    private const val ERROR_OUTLINE_SEGMENTS = 200.0
    private const val MINIMUM_CORNER_TURN = 30 * PI / 180
    private const val CORNER_WINDOW = 0.025
    private const val MINIMUM_CORNER_WINDOW = 2
    private const val SHARP_FRACTION = 0.75
    private const val SIDE_MARGIN = 0.15
    private const val MAXIMUM_CORNER_SHIFT = 0.1
    private const val RIGHT_ANGLE_TOLERANCE = 15 * PI / 180
}

private fun segmentCount(
    length: Double,
    step: Double,
): Int = ceil(length / step).toInt().coerceIn(1, MAXIMUM_OUTLINE_SEGMENTS)

private fun subdivide(
    vertices: List<ShapePoint>,
    step: Double,
): List<ShapePoint> {
    val result = mutableListOf(vertices.first())
    for (i in 1 until vertices.size) {
        val a = vertices[i - 1]
        val b = vertices[i]
        val count = segmentCount(a.distanceTo(b), step)
        for (j in 1..count) result += a + (b - a) * (j.toDouble() / count)
    }
    return result
}

private const val MAXIMUM_OUTLINE_SEGMENTS = 100_000
