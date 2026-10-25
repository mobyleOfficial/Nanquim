package com.mobyle.nanquim.document.guide

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

class GridGeometryTest {
    private val area = GuideRect(0.0, 0.0, 1000.0, 600.0)

    @Test
    fun squareGridHasLinesEveryCellThroughTheOrigin() {
        val grid = GridGuide(size = 100.0, originX = 50.0, originY = 30.0)
        val lines = GridGeometry.lines(GuideType.Grid, grid, area)
        val vertical = lines.filter { abs(it.x0 - it.x1) < 1e-6 }.map { it.x0 }.sorted()
        val horizontal = lines.filter { abs(it.y0 - it.y1) < 1e-6 }.map { it.y0 }.sorted()
        assertEquals((0 until 10).map { 50.0 + it * 100 }, vertical.map { Math.round(it * 1e6) / 1e6 })
        assertEquals(listOf(30.0, 130.0, 230.0, 330.0, 430.0, 530.0), horizontal.map { Math.round(it * 1e6) / 1e6 })
        assertTrue(lines.all { hypot(it.x1 - it.x0, it.y1 - it.y0) >= 599.999 })
    }

    @Test
    fun linesAreClippedToTheArea() {
        val grid = GridGuide(size = 70.0, rotation = 0.4, originX = 13.0, originY = 77.0)
        for (segment in GridGeometry.lines(GuideType.Grid, grid, area)) {
            for ((x, y) in listOf(segment.x0 to segment.y0, segment.x1 to segment.y1)) {
                assertTrue(x in -1e-6..1000.000001 && y in -1e-6..600.000001, "$segment")
            }
        }
    }

    @Test
    fun isometricFamiliesAt30_90_150WithTriangleSpacing() {
        val grid = GridGuide(size = 120.0, originX = 500.0, originY = 300.0)
        val lines = GridGeometry.lines(GuideType.Isometric, grid, area)
        val angles =
            lines
                .map { segment ->
                    val degrees = Math.toDegrees(atan2(segment.y1 - segment.y0, segment.x1 - segment.x0))
                    Math.round(((degrees % 180) + 180) % 180)
                }.toSet()
        assertEquals(setOf(30L, 90L, 150L), angles)
        assertEquals(120.0 * sqrt(3.0) / 2, GridGeometry.spacing(GuideType.Isometric, 120.0), 1e-12)
        val vertical = lines.filter { abs(it.x0 - it.x1) < 1e-6 }.map { it.x0 }.sorted()
        assertEquals(120.0 * sqrt(3.0) / 2, vertical[1] - vertical[0], 1e-9)
    }

    @Test
    fun strideThinsAndTooDenseFamiliesAreLeftOut() {
        val grid = GridGuide(size = 10.0)
        val all = GridGeometry.lines(GuideType.Grid, grid, area)
        val thinned = GridGeometry.lines(GuideType.Grid, grid, area, stride = 5)
        assertTrue(thinned.size in all.size / 5 - 2..all.size / 5 + 2)
        assertEquals(0, GridGeometry.lines(GuideType.Grid, grid, area, maximumLines = 50).size)
    }

    @Test
    fun rotateHandleRoundTrips() {
        val grid = GridGuide(size = 80.0, rotation = 0.7, originX = 200.0, originY = 100.0)
        for (type in listOf(GuideType.Grid, GuideType.Isometric)) {
            val (x, y) = GridGeometry.rotateHandle(type, grid)
            assertEquals(0.7, GridGeometry.rotationToward(type, grid, x, y), 1e-9)
        }
        assertEquals(PI, GridGeometry.normalize(-PI), 1e-12)
        assertEquals(-PI / 2, GridGeometry.normalize(3 * PI / 2), 1e-12)
    }

    @Test
    fun rectIntersection() {
        assertEquals(GuideRect(10.0, 0.0, 1000.0, 50.0), area.intersect(GuideRect(10.0, -5.0, 2000.0, 50.0)))
        assertEquals(null, area.intersect(GuideRect(1001.0, 0.0, 1200.0, 10.0)))
    }
}
