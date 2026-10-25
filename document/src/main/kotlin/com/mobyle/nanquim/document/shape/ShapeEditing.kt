package com.mobyle.nanquim.document.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class ShapeHandleKind {
    Point,

    Rotate,
}

data class ShapeHandle(
    val position: ShapePoint,
    val kind: ShapeHandleKind,
)

object ShapeEditing {
    const val MINIMUM_EXTENT = 1.0

    fun handles(
        shape: RecognizedShape,
        rotateDistance: Double,
    ): List<ShapeHandle> =
        when (shape) {
            is LineShape -> points(shape.start, shape.end)
            is ArcShape -> points(*arcPoints(shape).toTypedArray())
            is EllipseShape -> {
                val axis = ShapePoint(cos(shape.ellipse.rotation), sin(shape.ellipse.rotation))
                points(*ellipseEnds(shape).toTypedArray()) +
                    ShapeHandle(shape.ellipse.center + axis * (shape.ellipse.radiusX + rotateDistance), ShapeHandleKind.Rotate)
            }
            is PolygonShape -> points(*shape.vertices.toTypedArray())
            is CurveShape -> points(*(listOf(shape.segments.first().start) + shape.segments.map { it.end }).toTypedArray())
        }

    fun moved(
        shape: RecognizedShape,
        index: Int,
        to: ShapePoint,
    ): RecognizedShape =
        when (shape) {
            is LineShape ->
                when (index) {
                    0 -> shape.copy(start = to)
                    1 -> shape.copy(end = to)
                    else -> shape
                }
            is ArcShape -> movedArc(shape, index, to)
            is EllipseShape -> movedEllipse(shape, index, to)
            is PolygonShape ->
                when {
                    index !in shape.vertices.indices -> shape
                    shape.rectangle && shape.vertices.size == 4 -> movedRectangle(shape, index, to)
                    else -> shape.copy(vertices = shape.vertices.mapIndexed { i, vertex -> if (i == index) to else vertex })
                }
            is CurveShape -> movedCurve(shape, index, to)
        }

    private fun points(vararg positions: ShapePoint) = positions.map { ShapeHandle(it, ShapeHandleKind.Point) }

    private fun arcPoints(arc: ArcShape): List<ShapePoint> =
        listOf(0.0, 0.5, 1.0).map { fraction ->
            val angle = arc.startAngle + arc.sweep * fraction
            ShapePoint(arc.center.x + arc.radius * cos(angle), arc.center.y + arc.radius * sin(angle))
        }

    private fun movedArc(
        arc: ArcShape,
        index: Int,
        to: ShapePoint,
    ): ArcShape {
        if (index !in 0..2) return arc
        val (start, middle, end) = arcPoints(arc).mapIndexed { i, point -> if (i == index) to else point }
        val center = circumcenter(start, middle, end) ?: return arc
        val startAngle = atan2(start.y - center.y, start.x - center.x)
        val counterclockwise = turn(atan2(end.y - center.y, end.x - center.x) - startAngle)
        val throughMiddle = turn(atan2(middle.y - center.y, middle.x - center.x) - startAngle)
        val sweep = if (throughMiddle <= counterclockwise) counterclockwise else counterclockwise - 2 * PI
        return ArcShape(center, start.distanceTo(center), startAngle, sweep)
    }

    private fun turn(angle: Double): Double {
        val wrapped = angle % (2 * PI)
        return if (wrapped < 0) wrapped + 2 * PI else wrapped
    }

    private fun circumcenter(
        a: ShapePoint,
        b: ShapePoint,
        c: ShapePoint,
    ): ShapePoint? {
        val ab = b - a
        val ac = c - a
        val cross = ab.x * ac.y - ab.y * ac.x
        if (abs(cross) < COLLINEAR * ab.length() * ac.length() || abs(cross) < 1e-9) return null
        val abSquared = ab.dot(ab)
        val acSquared = ac.dot(ac)
        val x = (ac.y * abSquared - ab.y * acSquared) / (2 * cross)
        val y = (ab.x * acSquared - ac.x * abSquared) / (2 * cross)
        return a + ShapePoint(x, y)
    }

    private fun ellipseEnds(shape: EllipseShape): List<ShapePoint> {
        val center = shape.ellipse.center
        val alongX = ShapePoint(cos(shape.ellipse.rotation), sin(shape.ellipse.rotation))
        val alongY = ShapePoint(-alongX.y, alongX.x)
        return listOf(
            center + alongX * shape.ellipse.radiusX,
            center + alongY * shape.ellipse.radiusY,
            center - alongX * shape.ellipse.radiusX,
            center - alongY * shape.ellipse.radiusY,
        )
    }

    private fun movedEllipse(
        shape: EllipseShape,
        index: Int,
        to: ShapePoint,
    ): EllipseShape {
        val ellipse = shape.ellipse
        if (index == ROTATE_HANDLE) {
            val offset = to - ellipse.center
            if (offset.length() < MINIMUM_EXTENT) return shape
            return shape.copy(ellipse = ellipse.copy(rotation = atan2(offset.y, offset.x)))
        }
        if (index !in 0..3) return shape
        val alongX = ShapePoint(cos(ellipse.rotation), sin(ellipse.rotation))
        val axis = if (index % 2 == 0) alongX else ShapePoint(-alongX.y, alongX.x)
        val outward = if (index < 2) axis else axis * -1.0
        val radius = if (index % 2 == 0) ellipse.radiusX else ellipse.radiusY
        val opposite = ellipse.center - outward * radius
        val extent = max(2 * MINIMUM_EXTENT, (to - opposite).dot(outward))
        val center = opposite + outward * (extent / 2)
        val resized =
            when {
                shape.circle -> ellipse.copy(center = center, radiusX = extent / 2, radiusY = extent / 2)
                index % 2 == 0 -> ellipse.copy(center = center, radiusX = extent / 2)
                else -> ellipse.copy(center = center, radiusY = extent / 2)
            }
        return shape.copy(ellipse = resized)
    }

    private fun movedRectangle(
        shape: PolygonShape,
        index: Int,
        to: ShapePoint,
    ): PolygonShape {
        val vertices = shape.vertices
        val opposite = vertices[(index + 2) % 4]
        val next = (index + 1) % 4
        val previous = (index + 3) % 4
        val towardNext = (vertices[next] - opposite).normalized()
        val towardPrevious = (vertices[previous] - opposite).normalized()
        if (towardNext.length() == 0.0 || towardPrevious.length() == 0.0) return shape
        val offset = to - opposite
        val alongNext = atLeast(offset.dot(towardNext))
        val alongPrevious = atLeast(offset.dot(towardPrevious))
        val moved = vertices.toMutableList()
        moved[next] = opposite + towardNext * alongNext
        moved[previous] = opposite + towardPrevious * alongPrevious
        moved[index] = opposite + towardNext * alongNext + towardPrevious * alongPrevious
        return shape.copy(vertices = moved)
    }

    private fun atLeast(length: Double) = if (abs(length) >= MINIMUM_EXTENT) length else MINIMUM_EXTENT

    private fun movedCurve(
        shape: CurveShape,
        index: Int,
        to: ShapePoint,
    ): CurveShape {
        val segments = shape.segments
        if (index !in 0..segments.size) return shape
        val anchor = if (index == 0) segments[0].start else segments[index - 1].end
        val offset = to - anchor
        val moved =
            segments.mapIndexed { i, segment ->
                when (i) {
                    index - 1 -> segment.copy(control2 = segment.control2 + offset, end = to)
                    index -> segment.copy(start = to, control1 = segment.control1 + offset)
                    else -> segment
                }
            }
        return CurveShape(moved)
    }

    private const val ROTATE_HANDLE = 4

    private const val COLLINEAR = 1e-3
}
