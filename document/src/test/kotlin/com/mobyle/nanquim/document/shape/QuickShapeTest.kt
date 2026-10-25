package com.mobyle.nanquim.document.shape

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class QuickShapeTest {
    private fun path(
        count: Int,
        point: (Double) -> ShapePoint,
    ): List<ShapePoint> = (0..count).map { point(it.toDouble() / count) }

    private fun polygonPath(
        vertices: List<ShapePoint>,
        closed: Boolean,
    ): List<ShapePoint> {
        val corners = if (closed) vertices + vertices.first() else vertices
        return (1 until corners.size).flatMap { side ->
            val a = corners[side - 1]
            val b = corners[side]
            (0 until 50).map { a + (b - a) * (it / 50.0) }
        } + corners.last()
    }

    private fun assertPoint(
        expected: ShapePoint,
        actual: ShapePoint,
        tolerance: Double,
    ) = assertTrue(expected.distanceTo(actual) <= tolerance, "expected $expected, was $actual")

    private fun axisAngle(
        a: Double,
        b: Double,
    ): Double {
        val difference = abs((a - b) % PI)
        return minOf(difference, PI - difference)
    }

    @Test
    fun fitsRecoverLinesCirclesAndEllipses() {
        val line = ShapeFitting.line(path(20) { ShapePoint(100 + 300 * it, 50 + 400 * it) })
        assertEquals(0.0, axisAngle(kotlin.math.atan2(400.0, 300.0), kotlin.math.atan2(line.direction.y, line.direction.x)), 1e-9)

        val arc = path(60) { ShapePoint(400 + 250 * cos(it * PI / 3), 300 + 250 * sin(it * PI / 3)) }
        val circle = ShapeFitting.circle(arc)!!
        assertPoint(ShapePoint(400.0, 300.0), circle.center, 1e-6)
        assertEquals(250.0, circle.radius, 1e-6)
        assertNull(ShapeFitting.circle(path(10) { ShapePoint(it, 2 * it) }))

        val ellipse =
            ShapeFitting.ellipse(
                path(120) {
                    val t = 2 * PI * it
                    val x = 300 * cos(t)
                    val y = 120 * sin(t)
                    ShapePoint(500 + x * cos(0.4) - y * sin(0.4), 400 + x * sin(0.4) + y * cos(0.4))
                },
            )!!
        assertPoint(ShapePoint(500.0, 400.0), ellipse.center, 1e-6)
        val long = maxOf(ellipse.radiusX, ellipse.radiusY)
        assertEquals(300.0, long, 1e-6)
        assertEquals(120.0, minOf(ellipse.radiusX, ellipse.radiusY), 1e-6)
        val longAxis = if (ellipse.radiusX >= ellipse.radiusY) ellipse.rotation else ellipse.rotation + PI / 2
        assertEquals(0.0, axisAngle(0.4, longAxis), 1e-6)
    }

    @Test
    fun simplifyKeepsCornersAndCurvesStayUnderTolerance() {
        val zigzag = polygonPath(listOf(ShapePoint(0.0, 0.0), ShapePoint(100.0, 0.0), ShapePoint(100.0, 100.0)), closed = false)
        val kept = ShapeFitting.simplify(zigzag, 1.0)
        assertEquals(listOf(ShapePoint(0.0, 0.0), ShapePoint(100.0, 0.0), ShapePoint(100.0, 100.0)), kept.map { zigzag[it] })

        val wave = path(200) { ShapePoint(600 * it, 80 * sin(2 * PI * 1.5 * it)) }
        val segments = ShapeFitting.bezierCurve(wave, tolerance = 2.0, maximumSegments = 8)!!
        assertEquals(wave.first(), segments.first().start)
        assertEquals(wave.last(), segments.last().end)
        val outline = CurveShape(segments).outline(1.0)
        assertTrue(wave.all { ShapeFitting.polylineDistance(it, outline) <= 2.5 })
        assertNull(ShapeFitting.bezierCurve(wave, tolerance = 2.0, maximumSegments = 1))
    }

    @Test
    fun recognizesALineFromItsStartToItsEnd() {
        val stroke = path(100) { ShapePoint(200 + 600 * it, 300 + 200 * it + 2 * sin(it * 40)) }
        val shape = QuickShape.recognize(stroke).shape as LineShape
        assertPoint(ShapePoint(200.0, 300.0), shape.start, 3.0)
        assertPoint(ShapePoint(800.0, 500.0), shape.end, 3.0)
        val outline = shape.outline(1.0)
        assertTrue(outline.zipWithNext().all { (a, b) -> a.distanceTo(b) <= 1.0 + 1e-9 })
    }

    @Test
    fun recognizesAnArcTurningTheStrokesWay() {
        val stroke = path(150) { ShapePoint(500 + 300 * cos(1.0 - 2.5 * it), 500 + 300 * sin(1.0 - 2.5 * it)) }
        val shape = QuickShape.recognize(stroke).shape as ArcShape
        assertEquals(300.0, shape.radius, 1.0)
        assertEquals(-2.5, shape.sweep, 0.02)
        assertPoint(stroke.first(), shape.outline(1.0).first(), 1.0)
        assertPoint(stroke.last(), shape.outline(1.0).last(), 1.0)
    }

    @Test
    fun recognizesCirclesAndEllipsesFromTheStrokeStart() {
        val circle = path(300) { ShapePoint(800 + 200 * cos(-it * (2 * PI + PI / 6)), 600 + 200 * sin(-it * (2 * PI + PI / 6))) }
        val round = QuickShape.recognize(circle).shape as EllipseShape
        assertEquals(ShapeKind.Circle, round.kind)
        assertEquals(200.0, round.ellipse.radiusX, 1.0)
        val roundOutline = round.outline(1.0)
        val firstStep = roundOutline[5] - roundOutline[0]
        val strokeStep = circle[5] - circle[0]
        assertTrue(firstStep.dot(strokeStep) > 0)

        val oval = path(300) { ShapePoint(800 + 300 * cos(2 * PI * it + 1), 600 + 150 * sin(2 * PI * it + 1)) }
        val ellipse = QuickShape.recognize(oval).shape as EllipseShape
        assertEquals(ShapeKind.Ellipse, ellipse.kind)
        assertPoint(oval.first(), ellipse.outline(1.0).first(), 1.0)
    }

    @Test
    fun recognizesRectanglesPolygonsAndPolylines() {
        val turn = 0.3
        val corners =
            listOf(ShapePoint(0.0, 0.0), ShapePoint(400.0, 0.0), ShapePoint(400.0, 250.0), ShapePoint(0.0, 250.0))
                .map { ShapePoint(1000 + it.x * cos(turn) - it.y * sin(turn), 500 + it.x * sin(turn) + it.y * cos(turn)) }
        val loop = polygonPath(corners, closed = true)
        val started = loop.drop(25) + loop.take(26)
        val rectangle = QuickShape.recognize(started).shape as PolygonShape
        assertEquals(ShapeKind.Rectangle, rectangle.kind)
        assertEquals(4, rectangle.vertices.size)
        assertPoint(corners[1], rectangle.vertices[0], 1.0)
        for (corner in corners) assertTrue(rectangle.vertices.any { it.distanceTo(corner) <= 1.0 })

        val triangle = listOf(ShapePoint(100.0, 100.0), ShapePoint(600.0, 150.0), ShapePoint(300.0, 500.0))
        val polygon = QuickShape.recognize(polygonPath(triangle, closed = true)).shape as PolygonShape
        assertEquals(ShapeKind.Polygon, polygon.kind)
        assertEquals(3, polygon.vertices.size)

        val vee = listOf(ShapePoint(100.0, 100.0), ShapePoint(300.0, 500.0), ShapePoint(500.0, 120.0))
        val polyline = QuickShape.recognize(polygonPath(vee, closed = false)).shape as PolygonShape
        assertEquals(ShapeKind.Polyline, polyline.kind)
        assertPoint(vee[1], polyline.vertices[1], 1.0)
    }

    @Test
    fun aSmoothCurveIsTheFallbackAndTinyStrokesStay() {
        val wave = path(200) { ShapePoint(100 + 600 * it, 400 + 100 * sin(2 * PI * 1.5 * it)) }
        val recognition = QuickShape.recognize(wave)
        assertEquals(ShapeKind.Curve, recognition.shape?.kind)
        assertTrue(recognition.candidates.any { it.shape.kind == ShapeKind.Line })

        assertNull(QuickShape.recognize(path(10) { ShapePoint(100 + 5 * it, 100.0) }).shape)
        assertNull(QuickShape.recognize(listOf(ShapePoint(0.0, 0.0), ShapePoint(50.0, 0.0))).shape)
        val scribble = path(2000) { ShapePoint(300 + 200 * it + 60 * sin(90 * it), 300 + 60 * cos(70 * it)) }
        assertNull(QuickShape.recognize(scribble).shape)
    }

    @Test
    fun traceSetsSurviveJsonAndScoreCirclesAsEllipses() {
        val set = ShapeTraceSet(device = "test", traces = listOf(ShapeTrace("arc", "A", listOf(listOf(1.0, 2.0, 0.5, 0.0)))))
        val decoded = ShapeTraceSet.decode(ShapeTraceSet.encode(set))
        assertEquals(set, decoded)
        assertEquals(listOf(ShapePoint(1.0, 2.0)), decoded.traces.single().shapePoints())
        assertEquals("ellipse", ShapeTraceSet.labelOf(ShapeKind.Circle))
        assertNotNull(ShapeKind.fromLabel("polygon"))
        assertNull(ShapeTraceSet.labelOf(null))
    }
}
