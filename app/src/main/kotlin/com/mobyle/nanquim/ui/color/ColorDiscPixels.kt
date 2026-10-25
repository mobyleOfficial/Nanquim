package com.mobyle.nanquim.ui.color

import com.mobyle.nanquim.document.color.ColorDisc
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscPoint
import com.mobyle.nanquim.document.color.DiscZoom
import kotlin.math.hypot
import kotlin.math.roundToInt

object ColorDiscPixels {
    fun ring(
        size: Int,
        innerFraction: Float,
    ): IntArray {
        val pixels = IntArray(size * size)
        val radius = size / 2.0
        for (row in 0 until size) {
            for (column in 0 until size) {
                val (x, y) = unit(column, row, radius)
                val distance = hypot(x, y)
                if (distance > 1.0 || distance < innerFraction) continue
                pixels[row * size + column] = argb(ColorValue.fromHsb(ColorDisc.hueAt(x, y), 1f, 1f))
            }
        }
        return pixels
    }

    fun disc(
        size: Int,
        hue: Float,
        zoom: DiscZoom,
    ): IntArray {
        val pixels = IntArray(size * size)
        val radius = size / 2.0
        for (row in 0 until size) {
            for (column in 0 until size) {
                val (x, y) = unit(column, row, radius)
                if (hypot(x, y) > 1.0) continue
                val point = zoom.toDisc(DiscPoint(x, y))
                val (saturation, brightness) = ColorDisc.saturationBrightnessAt(point.x, point.y)
                pixels[row * size + column] = argb(ColorValue.fromHsb(hue, saturation, brightness))
            }
        }
        return pixels
    }

    private fun unit(
        column: Int,
        row: Int,
        radius: Double,
    ): Pair<Double, Double> = (column + 0.5 - radius) / radius to (radius - (row + 0.5)) / radius

    private fun argb(color: ColorValue): Int {
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
    }
}
