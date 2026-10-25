package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ColorComponentTest {
    private val color = ColorValue.fromHsb(210f, 0.6f, 0.8f, alpha = 0.5f)

    @Test
    fun displayValuesUseTheFieldUnits() {
        assertEquals(210, ColorComponent.Hue.displayValue(color))
        assertEquals(60, ColorComponent.Saturation.displayValue(color))
        assertEquals(80, ColorComponent.Brightness.displayValue(color))
        val red = ColorValue.parseHex("#FF8000")!!
        assertEquals(255, ColorComponent.Red.displayValue(red))
        assertEquals(128, ColorComponent.Green.displayValue(red))
        assertEquals(0, ColorComponent.Blue.displayValue(red))
    }

    @Test
    fun hsbComponentsKeepTheOtherTwoAndTheAlpha() {
        val darker = ColorComponent.Brightness.withFraction(color, 0.3f)
        assertEquals(210f, darker.hue, 1e-3f)
        assertEquals(0.6f, darker.saturation, 1e-6f)
        assertEquals(0.3f, darker.brightness, 1e-6f)
        assertEquals(0.5f, darker.alpha)
        val gray = ColorComponent.Saturation.withFraction(color, 0f)
        assertEquals(210f, ColorComponent.Brightness.withFraction(gray, 0f).hue, 1e-3f)
    }

    @Test
    fun rgbChannelsKeepTheOtherChannelsAndTheHueOfGrays() {
        val gray = ColorValue.fromRgb(0.5f, 0.5f, 0.5f, previous = color)
        val lighter = ColorComponent.Red.withFraction(gray, 0.5f)
        assertEquals(210f, lighter.hue, 1e-3f)
        val pinkish = ColorComponent.Red.withFraction(gray, 0.9f)
        assertEquals(0.9f, pinkish.red)
        assertEquals(0.5f, pinkish.green)
        assertEquals(0.5f, pinkish.blue)
    }

    @Test
    fun typedValuesAreParsedAndClamped() {
        assertEquals(90f, ColorComponent.Hue.parse("90", color)!!.hue, 1e-3f)
        assertEquals(90f, ColorComponent.Hue.parse(" 90° ", color)!!.hue, 1e-3f)
        assertEquals(0.25f, ColorComponent.Saturation.parse("25%", color)!!.saturation, 1e-6f)
        assertEquals(0.255f, ColorComponent.Brightness.parse("25,5", color)!!.brightness, 1e-6f)
        assertEquals(1f, ColorComponent.Saturation.parse("150", color)!!.saturation)
        assertEquals(0f, ColorComponent.Blue.parse("-20", color)!!.blue)
        assertEquals(128 / 255f, ColorComponent.Green.parse("128", color)!!.green, 1e-6f)
        assertEquals(0f, ColorComponent.Hue.parse("360", color)!!.hue)
    }

    @Test
    fun nonNumbersAreRejected() {
        for (text in listOf("", " ", "abc", "1e", "NaN", "Infinity", "#FF")) {
            assertNull(ColorComponent.Red.parse(text, color), text)
        }
    }

    @Test
    fun trackStopsAreTheColorsOfTheSliderEnds() {
        val stops = ColorComponent.Saturation.trackStops(color)
        assertEquals(ColorValue.fromHsb(210f, 0f, 0.8f).toHex(), stops.first().toHex())
        assertEquals(ColorValue.fromHsb(210f, 1f, 0.8f).toHex(), stops.last().toHex())
        assertEquals(1f, stops.first().alpha)
        assertEquals(7, ColorComponent.Hue.trackStops(color).size)
        assertEquals(
            "#FF0000",
            ColorComponent.Hue
                .trackStops(color)
                .last()
                .toHex(),
        )
        for (component in ColorComponent.entries - ColorComponent.Hue) {
            val (start, end) = component.trackStops(color)
            for (step in 0..10) {
                val t = step / 10f
                val expected = component.withFraction(color, t)
                assertEquals(expected.red, start.red + (end.red - start.red) * t, 1e-5f, "$component red at $t")
                assertEquals(expected.green, start.green + (end.green - start.green) * t, 1e-5f, "$component green at $t")
                assertEquals(expected.blue, start.blue + (end.blue - start.blue) * t, 1e-5f, "$component blue at $t")
            }
        }
    }
}
