package com.mobyle.nanquim.document.guide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class PerspectiveSymmetryGeometryTest {
    private val area = GuideRect(0.0, 0.0, 1000.0, 600.0)

    private fun angleOf(segment: GuideSegment): Double {
        val angle = atan2(segment.y1 - segment.y0, segment.x1 - segment.x0)
        return ((angle % PI) + PI) % PI
    }

    private fun distanceToLine(
        segment: GuideSegment,
        x: Double,
        y: Double,
    ): Double {
        val dx = segment.x1 - segment.x0
        val dy = segment.y1 - segment.y0
        return abs(dx * (y - segment.y0) - dy * (x - segment.x0)) / hypot(dx, dy)
    }

    @Test
    fun onePointHasHorizonRaysAndBothParallels() {
        val perspective = PerspectiveGuide(listOf(VanishingPoint(500.0, 250.0)))
        val lines = PerspectiveGeometry.lines(perspective, area, parallelSpacing = 100.0, rays = 12)
        val horizon = lines.single { it.emphasized }
        assertEquals(250.0, horizon.y0, 1e-9)
        assertEquals(250.0, horizon.y1, 1e-9)
        val rays = lines.filter { !it.emphasized && distanceToLine(it, 500.0, 250.0) < 1e-6 }
        assertEquals(14, rays.size)
        val vertical = lines.filter { abs(it.x0 - it.x1) < 1e-9 }.map { Math.round(it.x0 * 1e6) / 1e6 }.distinct().sorted()
        assertEquals((0..10).map { it * 100.0 }, vertical)
    }

    @Test
    fun twoPointsHorizonThroughBothAndVerticalsSquareToIt() {
        val perspective = PerspectiveGuide(listOf(VanishingPoint(-200.0, 300.0), VanishingPoint(1300.0, 100.0)))
        val horizonAngle = PerspectiveGeometry.horizonDirection(perspective)
        assertEquals(atan2(-200.0, 1500.0), horizonAngle, 1e-12)
        val lines = PerspectiveGeometry.lines(perspective, area, parallelSpacing = 80.0)
        val horizon = lines.single { it.emphasized }
        assertTrue(distanceToLine(horizon, -200.0, 300.0) < 1e-6)
        assertTrue(distanceToLine(horizon, 1300.0, 100.0) < 1e-6)
        val square = ((horizonAngle + PI / 2) % PI + PI) % PI
        val parallels = lines.filter { abs(angleOf(it) - square) < 1e-9 }
        assertTrue(parallels.size >= 10, "verticals: ${parallels.size}")
        assertTrue(lines.count { distanceToLine(it, 1300.0, 100.0) < 1e-6 } > 5)
    }

    @Test
    fun threePointsHaveNoParallels() {
        val perspective =
            PerspectiveGuide(listOf(VanishingPoint(100.0, 200.0), VanishingPoint(900.0, 200.0), VanishingPoint(500.0, 5000.0)))
        val lines = PerspectiveGeometry.lines(perspective, area, parallelSpacing = 50.0, rays = 8)
        val points = perspective.points
        assertTrue(lines.all { segment -> segment.emphasized || points.any { distanceToLine(segment, it.x, it.y) < 1e-6 } })
    }

    @Test
    fun pointsAreAddedMovedRemovedAndHit() {
        var perspective = PerspectiveGuide()
        repeat(3) { perspective = perspective.adding(VanishingPoint(it * 100.0, 0.0))!! }
        assertNull(perspective.adding(VanishingPoint(1.0, 1.0)))
        assertEquals(1, PerspectiveGeometry.pointAt(perspective, 110.0, 5.0, radius = 20.0))
        assertNull(PerspectiveGeometry.pointAt(perspective, 50.0, 0.0, radius = 20.0))
        perspective = perspective.moving(2, VanishingPoint(7.0, 8.0))!!
        assertEquals(VanishingPoint(7.0, 8.0), perspective.points[2])
        perspective = perspective.removing(0)!!
        assertEquals(listOf(VanishingPoint(100.0, 0.0), VanishingPoint(7.0, 8.0)), perspective.points)
        assertNull(perspective.removing(5))
    }

    private fun close(
        a: GuideTransform,
        b: GuideTransform,
    ) = listOf(a.a - b.a, a.b - b.b, a.c - b.c, a.d - b.d, a.e - b.e, a.f - b.f).all { abs(it) < 1e-6 }

    private fun allSymmetries(): List<SymmetryGuide> =
        SymmetryKind.entries.flatMap { kind ->
            listOf(false, true).flatMap { rotational ->
                val counts = if (kind == SymmetryKind.Radial) listOf(2, 3, 5, 8, 32) else listOf(8)
                counts.map { SymmetryGuide(kind, it, rotational, centerX = 400.0, centerY = 250.0, rotation = 0.3) }
            }
        }

    @Test
    fun symmetryCopiesFormAGroupAndKeepTheCenter() {
        for (symmetry in allSymmetries()) {
            val transforms = SymmetryGeometry.transforms(symmetry)
            assertEquals(GuideTransform.IDENTITY, transforms.first(), "$symmetry")
            for (t in transforms) {
                val (x, y) = t.apply(400.0, 250.0)
                assertEquals(400.0, x, 1e-6)
                assertEquals(250.0, y, 1e-6)
                for (u in transforms) assertTrue(transforms.any { close(it, t.after(u)) }, "$symmetry not closed")
            }
            assertEquals(transforms.size, transforms.distinctBy { listOf(it.a, it.b, it.c, it.d).map { v -> Math.round(v * 1e6) } }.size)
            if (symmetry.rotational) assertTrue(transforms.none { it.mirrored }, "$symmetry")
        }
    }

    @Test
    fun copyCountsPerKind() {
        fun count(
            kind: SymmetryKind,
            segments: Int = 8,
            rotational: Boolean = false,
        ) = SymmetryGeometry.transforms(SymmetryGuide(kind, segments, rotational)).size
        assertEquals(2, count(SymmetryKind.Vertical))
        assertEquals(2, count(SymmetryKind.Horizontal, rotational = true))
        assertEquals(4, count(SymmetryKind.Quadrant))
        assertEquals(8, count(SymmetryKind.Radial))
        assertEquals(5, count(SymmetryKind.Radial, 5, rotational = true))
        assertEquals(6, count(SymmetryKind.Radial, 5))
        assertEquals(32, count(SymmetryKind.Radial, 32))
        assertEquals(3, SymmetryGeometry.transforms(SymmetryGuide(SymmetryKind.Radial, 3, true)).count { !it.mirrored })
    }

    @Test
    fun verticalMirrorFlipsAcrossTheUprightAxis() {
        val symmetry = SymmetryGuide(SymmetryKind.Vertical, centerX = 500.0, centerY = 300.0)
        val mirror = SymmetryGeometry.transforms(symmetry)[1]
        assertTrue(mirror.mirrored)
        val (x, y) = mirror.apply(530.0, 100.0)
        assertEquals(470.0, x, 1e-9)
        assertEquals(100.0, y, 1e-9)
        val axis = SymmetryGeometry.lines(symmetry, area).single()
        assertEquals(500.0, axis.x0, 1e-9)
        assertEquals(500.0, axis.x1, 1e-9)
        val horizontal = SymmetryGeometry.transforms(symmetry.copy(kind = SymmetryKind.Horizontal))[1]
        val (hx, hy) = horizontal.apply(530.0, 280.0)
        assertEquals(530.0, hx, 1e-9)
        assertEquals(320.0, hy, 1e-9)
    }

    @Test
    fun radialMirrorMapsWedgeZeroOntoEachWedge() {
        val symmetry = SymmetryGuide(SymmetryKind.Radial, 6, centerX = 0.0, centerY = 0.0)
        val wedge = 2 * PI / 6
        val up = -PI / 2
        val inside = up + wedge * 0.3
        val transforms = SymmetryGeometry.transforms(symmetry)
        transforms.forEachIndexed { k, t ->
            val (x, y) = t.apply(100 * kotlin.math.cos(inside), 100 * kotlin.math.sin(inside))
            val angle = ((atan2(y, x) - up) % (2 * PI) + 2 * PI) % (2 * PI)
            assertTrue(angle > k * wedge && angle < (k + 1) * wedge, "copy $k at $angle")
            assertEquals(k % 2 == 1, t.mirrored)
        }
        assertEquals(6, SymmetryGeometry.lines(symmetry, GuideRect(-500.0, -500.0, 500.0, 500.0)).size)
    }

    @Test
    fun rotateNodeRoundTrips() {
        val symmetry = SymmetryGuide(centerX = 200.0, centerY = 200.0, rotation = 0.7)
        val (x, y) = SymmetryGeometry.rotateHandle(symmetry, 120.0)
        assertEquals(0.7, SymmetryGeometry.rotationToward(symmetry, x, y), 1e-9)
        val (ux, uy) = SymmetryGeometry.rotateHandle(symmetry.copy(rotation = 0.0), 120.0)
        assertEquals(200.0, ux, 1e-9)
        assertEquals(80.0, uy, 1e-9)
        assertFalse(GuideTransform.rotation(1.0, 3.0, 4.0).mirrored)
    }
}
