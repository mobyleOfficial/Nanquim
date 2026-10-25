package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorValueTest {
    @Test
    fun primariesHaveTheirHues() {
        assertHsb(ColorValue.fromRgb(1f, 0f, 0f), 0f, 1f, 1f)
        assertHsb(ColorValue.fromRgb(0f, 1f, 0f), 120f, 1f, 1f)
        assertHsb(ColorValue.fromRgb(0f, 0f, 1f), 240f, 1f, 1f)
        assertHsb(ColorValue.fromRgb(1f, 0f, 1f), 300f, 1f, 1f)
        assertHsb(ColorValue.fromRgb(0.5f, 0.25f, 0.25f), 0f, 0.5f, 0.5f)
    }

    @Test
    fun hsbAndRgbRoundTrip() {
        for (hue in 0 until 360 step 15) {
            for (saturation in listOf(0.1f, 0.5f, 1f)) {
                for (brightness in listOf(0.2f, 0.7f, 1f)) {
                    val color = ColorValue.fromHsb(hue.toFloat(), saturation, brightness)
                    val back = ColorValue.fromRgb(color.red, color.green, color.blue)
                    assertHsb(back, hue.toFloat(), saturation, brightness)
                }
            }
        }
        for (step in 0..20) {
            val red = step / 20f
            val green = (20 - step) / 40f
            val blue = (step % 7) / 6f
            val color = ColorValue.fromRgb(red, green, blue)
            val back = ColorValue.fromHsb(color.hue, color.saturation, color.brightness)
            assertEquals(red, back.red, TOLERANCE)
            assertEquals(green, back.green, TOLERANCE)
            assertEquals(blue, back.blue, TOLERANCE)
        }
    }

    @Test
    fun hueSurvivesGrayAndBlack() {
        val orange = ColorValue.fromHsb(30f, 0.8f, 0.9f)
        val gray = ColorValue.fromRgb(0.5f, 0.5f, 0.5f, previous = orange)
        assertHsb(gray, 30f, 0f, 0.5f)
        val black = ColorValue.fromRgb(0f, 0f, 0f, previous = orange)
        assertHsb(black, 30f, 0.8f, 0f)
        assertEquals(30f, ColorValue.fromHsb(orange.hue, 0f, orange.brightness).hue, TOLERANCE)
        assertEquals(0f, ColorValue.fromRgb(0.3f, 0.3f, 0.3f).hue)
    }

    @Test
    fun hueWrapsAround() {
        assertEquals(10f, ColorValue.fromHsb(370f, 1f, 1f).hue, TOLERANCE)
        assertEquals(350f, ColorValue.fromHsb(-10f, 1f, 1f).hue, TOLERANCE)
        assertEquals(0f, ColorValue.fromHsb(360f, 1f, 1f).hue)
    }

    @Test
    fun componentsAreClamped() {
        val color = ColorValue.fromRgb(1.5f, -0.2f, 0.5f, alpha = 2f)
        assertEquals(1f, color.red)
        assertEquals(0f, color.green)
        assertEquals(1f, color.alpha)
    }

    @Test
    fun hexWithAndWithoutHash() {
        val color = ColorValue.parseHex("#FF8000")!!
        assertEquals(1f, color.red)
        assertEquals(128 / 255f, color.green, TOLERANCE)
        assertEquals(0f, color.blue)
        assertEquals(color, ColorValue.parseHex("ff8000"))
        assertEquals(color, ColorValue.parseHex("  #Ff8000 "))
        assertEquals("#FF8000", color.toHex())
        assertEquals("#000000", ColorValue.BLACK.toHex())
        assertEquals("#FFFFFF", ColorValue.WHITE.toHex())
    }

    @Test
    fun invalidHexIsRejected() {
        for (text in listOf("", "#", "#FFF", "FF80001", "#GG8000", "##FF8000", "FF 800")) {
            assertNull(ColorValue.parseHex(text), text)
        }
    }

    @Test
    fun hexKeepsAlphaAndHueOfThePreviousColor() {
        val previous = ColorValue.fromHsb(200f, 0.6f, 0.6f, alpha = 0.5f)
        val gray = ColorValue.parseHex("808080", previous)!!
        assertEquals(0.5f, gray.alpha)
        assertEquals(200f, gray.hue, TOLERANCE)
    }

    @Test
    fun hexRoundTripsEvery8BitLevel() {
        for (level in 0..255) {
            val hex = "#%02X%02X%02X".format(level, 255 - level, level / 2)
            assertEquals(hex, ColorValue.parseHex(hex)!!.toHex())
        }
    }

    @Test
    fun oklabMatchesReferenceValues() {
        val white = OklabColor.fromSrgb(1.0, 1.0, 1.0)
        assertEquals(1.0, white.lightness, 1e-4)
        assertEquals(0.0, white.a, 1e-4)
        assertEquals(0.0, white.b, 1e-4)
        val red = OklabColor.fromSrgb(1.0, 0.0, 0.0)
        assertEquals(0.6280, red.lightness, 1e-3)
        assertEquals(0.2249, red.a, 1e-3)
        assertEquals(0.1258, red.b, 1e-3)
        val black = OklabColor.fromSrgb(0.0, 0.0, 0.0)
        assertEquals(0.0, black.lightness, 1e-9)
        assertEquals(1.0, white.distanceTo(black), 1e-4)
    }

    @Test
    fun oklabRoundTrips() {
        for (step in 0..10) {
            val red = step / 10.0
            val green = 1.0 - step / 10.0
            val blue = (step % 3) / 2.0
            val (r, g, b) = OklabColor.fromSrgb(red, green, blue).toSrgb()
            assertEquals(red, r, 1e-6)
            assertEquals(green, g, 1e-6)
            assertEquals(blue, b, 1e-6)
        }
    }

    @Test
    fun harmoniesPlaceMarkersAroundTheBaseHue() {
        val base = ColorValue.fromHsb(30f, 0.7f, 0.8f)
        assertEquals(listOf(30f, 210f), ColorHarmony.Complementary.colors(base).map { it.hue })
        assertEquals(listOf(30f, 180f, 240f), ColorHarmony.SplitComplementary.colors(base).map { it.hue })
        assertEquals(listOf(30f, 0f, 60f), ColorHarmony.Analogous.colors(base).map { it.hue })
        assertEquals(listOf(30f, 150f, 270f), ColorHarmony.Triadic.colors(base).map { it.hue })
        assertEquals(listOf(30f, 120f, 210f, 300f), ColorHarmony.Tetradic.colors(base).map { it.hue })
        assertTrue(ColorHarmony.Triadic.colors(base).all { it.saturation == 0.7f && it.brightness == 0.8f })
        assertEquals(ColorHarmony.Triadic, ColorHarmony.fromId("triadic"))
    }

    private fun assertHsb(
        color: ColorValue,
        hue: Float,
        saturation: Float,
        brightness: Float,
    ) {
        assertEquals(hue, color.hue, HUE_TOLERANCE, "hue of $color")
        assertEquals(saturation, color.saturation, TOLERANCE, "saturation of $color")
        assertEquals(brightness, color.brightness, TOLERANCE, "brightness of $color")
    }

    private companion object {
        const val TOLERANCE = 1e-5f
        const val HUE_TOLERANCE = 1e-3f
    }
}
