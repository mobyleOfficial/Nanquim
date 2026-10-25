package com.mobyle.nanquim.ui.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.color.ColorDisc
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscPoint
import com.mobyle.nanquim.document.color.DiscZoom
import kotlin.math.roundToInt

class ColorDiscPixelsTest {
    private val size = 64

    private fun argb(color: ColorValue): Int {
        fun channel(value: Float) = (value * 255f).roundToInt()
        return (0xFF shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }

    private fun point(
        column: Int,
        row: Int,
    ) = DiscPoint((column + 0.5 - size / 2.0) / (size / 2.0), (size / 2.0 - (row + 0.5)) / (size / 2.0))

    @Test
    fun discPixelsAreTheColorsUnderTheFinger() {
        val pixels = ColorDiscPixels.disc(size, 200f, DiscZoom())
        for ((column, row) in listOf(32 to 32, 50 to 20, 10 to 40, 30 to 60)) {
            val p = point(column, row)
            val (saturation, brightness) = ColorDisc.saturationBrightnessAt(p.x, p.y)
            assertEquals(argb(ColorValue.fromHsb(200f, saturation, brightness)), pixels[row * size + column], "pixel $column, $row")
        }
        assertEquals(0, pixels[0])
    }

    @Test
    fun zoomedDiscShowsTheZoomedPoint() {
        val zoom = DiscZoom(2.0, DiscPoint(0.3, 0.2))
        val pixels = ColorDiscPixels.disc(size, 10f, zoom)
        val p = zoom.toDisc(point(40, 24))
        val (saturation, brightness) = ColorDisc.saturationBrightnessAt(p.x, p.y)
        assertEquals(argb(ColorValue.fromHsb(10f, saturation, brightness)), pixels[24 * size + 40])
    }

    @Test
    fun ringHasTheHueByAngleAndAHole() {
        val pixels = ColorDiscPixels.ring(size, 0.84f)
        val top = point(32, 1)
        assertEquals(argb(ColorValue.fromHsb(ColorDisc.hueAt(top.x, top.y), 1f, 1f)), pixels[1 * size + 32])
        assertEquals(argb(ColorValue.fromHsb(ColorDisc.hueAt(point(62, 32).x, point(62, 32).y), 1f, 1f)), pixels[32 * size + 62])
        assertEquals(0, pixels[32 * size + 32])
    }
}
