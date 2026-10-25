package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class HarmonyDiscTest {
    @Test
    fun angleIsHueAndDistanceIsSaturation() {
        val (hue, saturation) = HarmonyDisc.hueSaturationAt(0.5, 0.0, previousHue = 10f)
        assertEquals(90f, hue, 1e-4f)
        assertEquals(0.5f, saturation, 1e-6f)
        assertEquals(1f, HarmonyDisc.hueSaturationAt(0.0, -3.0, 10f).second)
        assertEquals(180f, HarmonyDisc.hueSaturationAt(0.0, -3.0, 10f).first, 1e-4f)
        assertEquals(10f to 0f, HarmonyDisc.hueSaturationAt(0.0, 0.0, 10f))
    }

    @Test
    fun pointsRoundTrip() {
        for (hue in 0 until 360 step 25) {
            for (saturation in listOf(0.2f, 0.7f, 1f)) {
                val point = HarmonyDisc.point(hue.toFloat(), saturation)
                assertEquals(saturation.toDouble(), hypot(point.x, point.y), 1e-6)
                val (h, s) = HarmonyDisc.hueSaturationAt(point.x, point.y, 0f)
                assertEquals(hue.toFloat(), h, 1e-3f)
                assertEquals(saturation, s, 1e-6f)
            }
        }
    }

    @Test
    fun markerAtFindsTheNearestWithinReach() {
        val colors = ColorHarmony.Triadic.colors(ColorValue.fromHsb(0f, 0.8f, 0.9f))
        val second = HarmonyDisc.point(120f, 0.8f)
        assertEquals(1, HarmonyDisc.markerAt(colors, second.x + 0.02, second.y, reach = 0.1))
        assertEquals(0, HarmonyDisc.markerAt(colors, 0.0, 0.79, reach = 0.1))
        assertNull(HarmonyDisc.markerAt(colors, 0.0, 0.0, reach = 0.1))
    }
}
