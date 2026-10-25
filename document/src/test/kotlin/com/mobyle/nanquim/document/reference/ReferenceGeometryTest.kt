package com.mobyle.nanquim.document.reference

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReferenceGeometryTest {
    private val density = 2.0

    @Test
    fun aDockedWindowSitsInItsCornerAndAFreeOneWhereItWasLeft() {
        val docked = ReferenceSettings(docked = ReferenceCorner.TopEnd)
        assertEquals(ReferenceRect(2000.0 - 640 - 24, 24.0, 640.0, 480.0), ReferenceGeometry.window(docked, 2000.0, 1200.0, density))
        val bottomStart = docked.copy(docked = ReferenceCorner.BottomStart)
        assertEquals(ReferenceRect(24.0, 1200.0 - 480 - 24, 640.0, 480.0), ReferenceGeometry.window(bottomStart, 2000.0, 1200.0, density))
        val free = ReferenceGeometry.moved(docked, 680.0, 360.0, 2000.0, 1200.0, density)
        assertNull(free.docked)
        assertEquals(0.5, free.x, 1e-12)
        assertEquals(0.5, free.y, 1e-12)
        assertEquals(ReferenceRect(680.0, 360.0, 640.0, 480.0), ReferenceGeometry.window(free, 2000.0, 1200.0, density))
        assertEquals(ReferenceRect(280.0, 760.0, 640.0, 480.0), ReferenceGeometry.window(free, 1200.0, 2000.0, density))
    }

    @Test
    fun lettingGoNearACornerDocks() {
        val settings = ReferenceSettings(docked = null, x = 0.5, y = 0.5)
        assertEquals(ReferenceCorner.BottomEnd, ReferenceGeometry.moved(settings, 1300.0, 700.0, 2000.0, 1200.0, density).docked)
        assertEquals(ReferenceCorner.TopStart, ReferenceGeometry.moved(settings, -50.0, 100.0, 2000.0, 1200.0, density).docked)
        assertNull(ReferenceGeometry.moved(settings, 600.0, 10.0, 2000.0, 1200.0, density).docked)
        val small = ReferenceGeometry.resized(settings, 100.0, 1000.0, density)
        assertEquals(ReferenceSettings.MINIMUM_SIDE, small.width)
        assertEquals(500.0, small.height)
    }

    @Test
    fun theContentFitsAndZoomsAroundTheFingers() {
        val fit = ReferenceView()
        assertEquals(0.1, ReferenceGeometry.scale(fit, 4000.0, 2000.0, 400.0, 400.0), 1e-12)
        assertEquals(Pair(2000.0, 1000.0), ReferenceGeometry.contentPoint(fit, 4000.0, 2000.0, 400.0, 400.0, 200.0, 200.0))
        assertEquals(Pair(0.0, 1000.0), ReferenceGeometry.contentPoint(fit, 4000.0, 2000.0, 400.0, 400.0, 0.0, 200.0))
        val zoomed = ReferenceGeometry.transformed(fit, 4000.0, 2000.0, 400.0, 400.0, 0.0, 0.0, 4.0, 100.0, 200.0)
        assertEquals(4.0, zoomed.zoom)
        val (x, y) = ReferenceGeometry.contentPoint(zoomed, 4000.0, 2000.0, 400.0, 400.0, 100.0, 200.0)
        assertEquals(1000.0, x, 1e-9)
        assertEquals(1000.0, y, 1e-9)
        val panned = ReferenceGeometry.transformed(zoomed, 4000.0, 2000.0, 400.0, 400.0, 40.0, 0.0, 1.0, 0.0, 0.0)
        assertEquals(zoomed.centerX * 4000 - 100, panned.centerX * 4000, 1e-9)
        val out = ReferenceGeometry.transformed(fit, 4000.0, 2000.0, 400.0, 400.0, 99999.0, 0.0, 0.1, 200.0, 200.0)
        assertEquals(1.0, out.zoom)
        assertEquals(0.0, out.centerX)
    }
}
