package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class ColorDiscTest {
    @Test
    fun ringStartsAtTheTopAndTurnsClockwise() {
        assertEquals(0f, ColorDisc.hueAt(0.0, 1.0), 1e-4f)
        assertEquals(90f, ColorDisc.hueAt(1.0, 0.0), 1e-4f)
        assertEquals(180f, ColorDisc.hueAt(0.0, -1.0), 1e-4f)
        assertEquals(270f, ColorDisc.hueAt(-1.0, 0.0), 1e-4f)
        assertEquals(ColorDisc.hueAt(0.3, 0.4), ColorDisc.hueAt(0.6, 0.8), 1e-4f)
        for (hue in 0 until 360 step 7) {
            val point = ColorDisc.ringPoint(hue.toFloat())
            assertEquals(1.0, hypot(point.x, point.y), 1e-9)
            assertEquals(hue.toFloat(), ColorDisc.hueAt(point.x, point.y), 1e-3f)
        }
    }

    @Test
    fun discCornersAreThePureValues() {
        val half = sqrt(0.5)
        assertSaturationBrightness(1f, 1f, half, half)
        assertSaturationBrightness(0f, 1f, -half, half)
        assertSaturationBrightness(0f, 0f, -half, -half)
        assertSaturationBrightness(0.5f, 0.5f, 0.0, 0.0)
        assertSaturationBrightness(1f, 0.5f, 1.0, 0.0)
        assertSaturationBrightness(0.5f, 1f, 0.0, 1.0)
    }

    @Test
    fun discMappingRoundTrips() {
        for (s in 0..10) {
            for (b in 0..10) {
                val point = ColorDisc.discPoint(s / 10f, b / 10f)
                assertTrue(hypot(point.x, point.y) <= 1.0 + 1e-9)
                val (saturation, brightness) = ColorDisc.saturationBrightnessAt(point.x, point.y)
                assertEquals(s / 10f, saturation, 1e-4f)
                assertEquals(b / 10f, brightness, 1e-4f)
            }
        }
    }

    @Test
    fun pointsOutsideTheDiscAreClampedToItsEdge() {
        assertEquals(ColorDisc.saturationBrightnessAt(1.0, 0.0), ColorDisc.saturationBrightnessAt(3.0, 0.0))
    }

    @Test
    fun doubleTapSnapsToPureValues() {
        assertEquals(120f, ColorDisc.snapHue(131f))
        assertEquals(0f, ColorDisc.snapHue(340f))
        assertEquals(1f to 1f, ColorDisc.snapSaturationBrightness(0.85f, 0.9f))
        assertEquals(0f to 1f, ColorDisc.snapSaturationBrightness(0.1f, 0.8f))
        assertEquals(0.4f to 0f, ColorDisc.snapSaturationBrightness(0.4f, 0.1f))
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val focus = DiscPoint(0.3, -0.2)
        val before = DiscZoom().toDisc(focus)
        val zoomed = DiscZoom().pinched(2.0, focus)
        val after = zoomed.toDisc(focus)
        assertEquals(before.x, after.x, 1e-9)
        assertEquals(before.y, after.y, 1e-9)
        assertEquals(focus.x, zoomed.toView(after).x, 1e-9)
    }

    @Test
    fun zoomStaysInRangeAndInsideTheDisc() {
        assertEquals(DiscZoom.MAXIMUM_SCALE, DiscZoom().pinched(10.0, DiscPoint(0.0, 0.0)).scale)
        assertEquals(1.0, DiscZoom().pinched(0.5, DiscPoint(0.0, 0.0)).scale)
        val panned = DiscZoom(2.0).pinched(1.0, DiscPoint(0.0, 0.0), pan = DiscPoint(-5.0, 0.0))
        assertTrue(hypot(panned.center.x, panned.center.y) <= 0.5 + 1e-9)
        assertEquals(DiscPoint(0.0, 0.0), DiscZoom(3.0, DiscPoint(0.5, 0.1)).pinched(0.1, DiscPoint(0.2, 0.2)).center)
    }

    private fun assertSaturationBrightness(
        saturation: Float,
        brightness: Float,
        x: Double,
        y: Double,
    ) {
        val (s, b) = ColorDisc.saturationBrightnessAt(x, y)
        assertEquals(saturation, s, 1e-4f, "saturation at $x, $y")
        assertEquals(brightness, b, 1e-4f, "brightness at $x, $y")
    }
}
