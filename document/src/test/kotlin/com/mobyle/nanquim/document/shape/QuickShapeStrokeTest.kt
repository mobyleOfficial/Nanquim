package com.mobyle.nanquim.document.shape

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class QuickShapeStrokeTest {
    private fun assertPoint(
        expected: ShapePoint,
        actual: ShapePoint,
        tolerance: Double = 1e-9,
    ) = assertTrue(expected.distanceTo(actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun theProfileFollowsNormalizedArcLength() {
        val stroke = listOf(ShapePoint(0.0, 0.0), ShapePoint(10.0, 0.0), ShapePoint(20.0, 0.0), ShapePoint(100.0, 0.0))
        val outline = (0..4).map { ShapePoint(it * 50.0, 10.0) }
        assertArrayEquals(doubleArrayOf(0.0, 2.0625, 2.375, 2.6875, 3.0), QuickShapeStroke.profile(stroke, outline), 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 0.0), QuickShapeStroke.profile(List(3) { ShapePoint(5.0, 5.0) }, outline.take(2)), 0.0)
    }

    @Test
    fun theMorphGoesFromTheStrokeToTheShape() {
        val stroke = (0..10).map { ShapePoint(it * 10.0, if (it % 2 == 0) 0.0 else 6.0) }
        val outline = LineShape(ShapePoint(0.0, 3.0), ShapePoint(100.0, 3.0)).outline(QuickShapeStroke.OUTLINE_STEP)
        val start = QuickShapeStroke.morph(stroke, outline, 0.0)
        assertEquals(outline.size, start.size)
        assertPoint(stroke.first(), start.first())
        assertPoint(stroke.last(), start.last())
        assertSame(outline, QuickShapeStroke.morph(stroke, outline, 1.0))
        val middle = QuickShapeStroke.morph(stroke, outline, 0.5)
        for (index in outline.indices) {
            assertPoint((start[index] + outline[index]) * 0.5, middle[index], 1e-9)
        }
        val resampled = QuickShapeStroke.resample(listOf(ShapePoint(0.0, 0.0), ShapePoint(30.0, 0.0), ShapePoint(30.0, 30.0)), 5)
        assertEquals(listOf(0.0, 15.0, 30.0, 30.0, 30.0), resampled.map { it.x })
        assertEquals(listOf(0.0, 0.0, 0.0, 15.0, 30.0), resampled.map { it.y })
    }

    @Test
    fun aFingerSnapsLinesTo15Degrees() {
        val line = LineShape(ShapePoint(100.0, 100.0), ShapePoint(100.0 + 200 * cos(0.3), 100.0 + 200 * sin(0.3)))
        val snapped = QuickShapeStroke.constrained(line) as LineShape
        assertPoint(line.start, snapped.start)
        val offset = snapped.end - snapped.start
        assertEquals(200.0, offset.length(), 1e-9)
        assertEquals(PI / 12, atan2(offset.y, offset.x), 1e-12)
    }

    @Test
    fun aFingerMakesCirclesSquaresAndRegularPolygons() {
        val ellipse = EllipseShape(FittedEllipse(ShapePoint(300.0, 200.0), 120.0, 80.0, 0.4), 1.0, clockwise = true, circle = false)
        val circle = QuickShapeStroke.constrained(ellipse) as EllipseShape
        assertEquals(ShapeKind.Circle, circle.kind)
        assertEquals(100.0, circle.ellipse.radiusX, 1e-12)
        assertEquals(100.0, circle.ellipse.radiusY, 1e-12)
        assertPoint(ellipse.ellipse.center, circle.ellipse.center)

        val turn = 0.2
        val corners =
            listOf(ShapePoint(0.0, 0.0), ShapePoint(0.0, 100.0), ShapePoint(160.0, 100.0), ShapePoint(160.0, 0.0))
                .map { ShapePoint(500 + it.x * cos(turn) - it.y * sin(turn), 300 + it.x * sin(turn) + it.y * cos(turn)) }
        val square = QuickShapeStroke.constrained(PolygonShape(corners, closed = true, rectangle = true)) as PolygonShape
        assertEquals(ShapeKind.Rectangle, square.kind)
        val sides = square.vertices.indices.map { square.vertices[it].distanceTo(square.vertices[(it + 1) % 4]) }
        sides.forEach { assertEquals(130.0, it, 1e-9) }
        assertPoint(ShapeFitting.centroid(corners), ShapeFitting.centroid(square.vertices), 1e-9)
        val firstSide = (square.vertices[1] - square.vertices[0]).normalized()
        assertPoint((corners[1] - corners[0]).normalized(), firstSide, 1e-9)
        assertEquals(QuickShape.signedArea(corners) > 0, QuickShape.signedArea(square.vertices) > 0)
        assertEquals(0.0, (square.vertices[1] - square.vertices[0]).dot(square.vertices[2] - square.vertices[1]), 1e-9)

        val triangle = listOf(ShapePoint(100.0, 100.0), ShapePoint(400.0, 140.0), ShapePoint(180.0, 380.0))
        val regular = QuickShapeStroke.constrained(PolygonShape(triangle, closed = true)) as PolygonShape
        val center = ShapeFitting.centroid(triangle)
        val radii = regular.vertices.map { it.distanceTo(center) }
        radii.forEach { assertEquals(radii.first(), it, 1e-9) }
        val regularSides = regular.vertices.indices.map { regular.vertices[it].distanceTo(regular.vertices[(it + 1) % 3]) }
        regularSides.forEach { assertEquals(regularSides.first(), it, 1e-9) }
        assertEquals(
            0.0,
            atan2(regular.vertices[0].y - center.y, regular.vertices[0].x - center.x) -
                atan2(triangle[0].y - center.y, triangle[0].x - center.x),
            1e-12,
        )
        assertEquals(QuickShape.signedArea(triangle) > 0, QuickShape.signedArea(regular.vertices) > 0)

        val polyline = PolygonShape(triangle, closed = false)
        assertSame(polyline, QuickShapeStroke.constrained(polyline))
        val arc = ArcShape(ShapePoint(0.0, 0.0), 50.0, 0.0, 1.0)
        assertSame(arc, QuickShapeStroke.constrained(arc))
    }

    @Test
    fun theEngineGetsAThinnedOutlineThatKeepsCornersSharp() {
        val rectangle =
            PolygonShape(
                listOf(ShapePoint(0.0, 0.0), ShapePoint(400.0, 0.0), ShapePoint(400.0, 250.0), ShapePoint(0.0, 250.0)),
                closed = true,
                rectangle = true,
            )
        val outline = rectangle.outline(QuickShapeStroke.OUTLINE_STEP)
        val thinned = QuickShapeStroke.strokeOutline(rectangle)
        assertTrue(thinned.size * 5 < outline.size, "${thinned.size} of ${outline.size}")
        assertEquals(outline.first(), thinned.first())
        assertEquals(outline.last(), thinned.last())
        for (corner in rectangle.vertices) assertTrue(thinned.any { it == corner }, "corner $corner")
        assertTrue(thinned.any { it == ShapePoint(398.0, 0.0) } && thinned.any { it == ShapePoint(400.0, 2.0) })
        assertTrue(thinned.zipWithNext().all { (a, b) -> a.distanceTo(b) <= QuickShapeStroke.THIN_MAXIMUM_STEP + 1e-9 })
        val circle = EllipseShape(FittedEllipse(ShapePoint(300.0, 300.0), 150.0, 150.0, 0.0), 0.0, clockwise = false, circle = true)
        val round = circle.outline(QuickShapeStroke.OUTLINE_STEP)
        val kept = QuickShapeStroke.thin(round)
        assertTrue(kept.size * 4 < round.size)
        assertTrue(round.all { ShapeFitting.polylineDistance(it, kept) <= QuickShapeStroke.THIN_TOLERANCE + 1e-9 })
        val stroke = listOf(ShapePoint(0.0, 10.0), ShapePoint(100.0, 10.0))
        val line = listOf(ShapePoint(0.0, 0.0), ShapePoint(10.0, 0.0), ShapePoint(100.0, 0.0))
        assertEquals(
            listOf(ShapePoint(0.0, 10.0), ShapePoint(10.0, 10.0), ShapePoint(100.0, 10.0)),
            QuickShapeStroke.matching(stroke, line),
        )
    }
}
