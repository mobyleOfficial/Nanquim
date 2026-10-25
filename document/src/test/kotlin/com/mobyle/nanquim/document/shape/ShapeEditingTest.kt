package com.mobyle.nanquim.document.shape

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ShapeEditingTest {
    private fun assertPoint(
        expected: ShapePoint,
        actual: ShapePoint,
        tolerance: Double = 1e-9,
    ) = assertTrue(expected.distanceTo(actual) <= tolerance, "expected $expected, was $actual")

    @Test
    fun lineEndsMove() {
        val line = LineShape(ShapePoint(10.0, 10.0), ShapePoint(100.0, 10.0))
        assertEquals(listOf(line.start, line.end), ShapeEditing.handles(line, 50.0).map { it.position })
        assertEquals(LineShape(ShapePoint(10.0, 10.0), ShapePoint(80.0, 60.0)), ShapeEditing.moved(line, 1, ShapePoint(80.0, 60.0)))
        assertSame(line, ShapeEditing.moved(line, 2, ShapePoint(0.0, 0.0)))
    }

    @Test
    fun anArcGoesThroughItsStartMiddleAndEnd() {
        val arc = ArcShape(ShapePoint(0.0, 0.0), 100.0, PI / 2, -PI / 2)
        val (start, middle, end) = ShapeEditing.handles(arc, 50.0).map { it.position }
        assertPoint(ShapePoint(0.0, 100.0), start)
        assertPoint(ShapePoint(100 * cos(PI / 4), 100 * sin(PI / 4)), middle)
        assertPoint(ShapePoint(100.0, 0.0), end)
        val same = ShapeEditing.moved(arc, 1, middle) as ArcShape
        assertPoint(arc.center, same.center, 1e-6)
        assertEquals(arc.radius, same.radius, 1e-6)
        assertEquals(arc.sweep, same.sweep, 1e-9)
        val flatter = ShapeEditing.moved(arc, 1, ShapePoint(60.0, 60.0)) as ArcShape
        val (newStart, _, newEnd) = ShapeEditing.handles(flatter, 50.0).map { it.position }
        assertPoint(start, newStart, 1e-6)
        assertPoint(end, newEnd, 1e-6)
        assertEquals(flatter.radius, ShapePoint(60.0, 60.0).distanceTo(flatter.center), 1e-6)
        assertTrue(flatter.sweep < 0 && flatter.sweep > -PI / 2, "${flatter.sweep}")
        val rounder = ShapeEditing.moved(arc, 1, ShapePoint(90.0, 90.0)) as ArcShape
        assertTrue(rounder.sweep < -PI / 2 && rounder.sweep > -PI, "${rounder.sweep}")
        val flipped = ShapeEditing.moved(arc, 1, ShapePoint(20.0, 20.0)) as ArcShape
        assertTrue(flipped.sweep > 0, "${flipped.sweep}")
        assertSame(arc, ShapeEditing.moved(arc, 1, ShapePoint(50.0, 50.0)))
    }

    @Test
    fun ellipseAxesKeepTheOppositeEndAndCirclesStayCircles() {
        val rotation = 0.3
        val ellipse = EllipseShape(FittedEllipse(ShapePoint(200.0, 200.0), 100.0, 50.0, rotation), 0.0, clockwise = false, circle = false)
        val handles = ShapeEditing.handles(ellipse, 40.0)
        assertEquals(List(4) { ShapeHandleKind.Point } + ShapeHandleKind.Rotate, handles.map { it.kind })
        val axis = ShapePoint(cos(rotation), sin(rotation))
        assertPoint(ellipse.ellipse.center + axis * 140.0, handles[4].position)
        val side = ShapePoint(-axis.y, axis.x)
        val wider = ShapeEditing.moved(ellipse, 0, handles[0].position + axis * 40.0 + side * 15.0) as EllipseShape
        assertEquals(120.0, wider.ellipse.radiusX, 1e-9)
        assertEquals(50.0, wider.ellipse.radiusY, 1e-9)
        assertPoint(handles[2].position, ShapeEditing.handles(wider, 40.0)[2].position)
        val flat = ShapeEditing.moved(ellipse, 3, handles[1].position + side * 30.0) as EllipseShape
        assertEquals(ShapeEditing.MINIMUM_EXTENT, flat.ellipse.radiusY, 1e-9)
        val turned = ShapeEditing.moved(ellipse, 4, ShapePoint(200.0, 400.0)) as EllipseShape
        assertEquals(PI / 2, turned.ellipse.rotation, 1e-12)
        assertEquals(ellipse.ellipse.radiusX, turned.ellipse.radiusX)
        val circle = EllipseShape(FittedEllipse(ShapePoint(0.0, 0.0), 50.0, 50.0, 0.0), 0.0, clockwise = true, circle = true)
        val bigger = ShapeEditing.moved(circle, 1, ShapePoint(0.0, 70.0)) as EllipseShape
        assertEquals(ShapeKind.Circle, bigger.kind)
        assertEquals(60.0, bigger.ellipse.radiusX, 1e-9)
        assertEquals(60.0, bigger.ellipse.radiusY, 1e-9)
        assertPoint(ShapePoint(0.0, 10.0), bigger.ellipse.center)
    }

    @Test
    fun polygonVerticesMoveAndRectanglesStayRectangles() {
        val triangle = PolygonShape(listOf(ShapePoint(0.0, 0.0), ShapePoint(100.0, 0.0), ShapePoint(50.0, 80.0)), closed = true)
        val moved = ShapeEditing.moved(triangle, 2, ShapePoint(60.0, 120.0)) as PolygonShape
        assertEquals(listOf(ShapePoint(0.0, 0.0), ShapePoint(100.0, 0.0), ShapePoint(60.0, 120.0)), moved.vertices)
        assertEquals(ShapeKind.Polygon, moved.kind)
        val turn = 0.2
        val along = ShapePoint(cos(turn), sin(turn))
        val across = ShapePoint(-sin(turn), cos(turn))
        val origin = ShapePoint(300.0, 300.0)
        val rectangle =
            PolygonShape(
                listOf(origin, origin + along * 200.0, origin + along * 200.0 + across * 100.0, origin + across * 100.0),
                closed = true,
                rectangle = true,
            )
        val target = origin + along * 250.0 + across * 160.0 + ShapePoint(0.0, 0.0)
        val resized = ShapeEditing.moved(rectangle, 2, target) as PolygonShape
        assertEquals(ShapeKind.Rectangle, resized.kind)
        assertPoint(origin, resized.vertices[0])
        assertPoint(origin + along * 250.0, resized.vertices[1])
        assertPoint(target, resized.vertices[2])
        assertPoint(origin + across * 160.0, resized.vertices[3])
        val other = ShapeEditing.moved(rectangle, 1, origin + along * 150.0 + across * -20.0) as PolygonShape
        assertPoint(rectangle.vertices[3], other.vertices[3])
        for (i in 0 until 4) {
            val a = other.vertices[(i + 1) % 4] - other.vertices[i]
            val b = other.vertices[(i + 2) % 4] - other.vertices[(i + 1) % 4]
            assertEquals(0.0, a.dot(b), 1e-6)
        }
    }

    @Test
    fun curveAnchorsCarryTheirControlPoints() {
        val first = CubicBezier(ShapePoint(0.0, 0.0), ShapePoint(10.0, 20.0), ShapePoint(30.0, 20.0), ShapePoint(40.0, 0.0))
        val second = CubicBezier(ShapePoint(40.0, 0.0), ShapePoint(50.0, -20.0), ShapePoint(70.0, -20.0), ShapePoint(80.0, 0.0))
        val curve = CurveShape(listOf(first, second))
        assertEquals(
            listOf(first.start, first.end, second.end),
            ShapeEditing.handles(curve, 10.0).map { it.position },
        )
        val moved = ShapeEditing.moved(curve, 1, ShapePoint(45.0, 10.0)) as CurveShape
        assertEquals(first.copy(control2 = ShapePoint(35.0, 30.0), end = ShapePoint(45.0, 10.0)), moved.segments[0])
        assertEquals(second.copy(start = ShapePoint(45.0, 10.0), control1 = ShapePoint(55.0, -10.0)), moved.segments[1])
        assertSame(curve, ShapeEditing.moved(curve, 3, ShapePoint(0.0, 0.0)))
    }
}
