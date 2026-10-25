package com.mobyle.nanquim.document.color

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object ColorDisc {
    fun hueAt(
        x: Double,
        y: Double,
    ): Float {
        if (x == 0.0 && y == 0.0) return 0f
        val degrees = atan2(x, y) * 180.0 / PI
        val wrapped = ((degrees % 360.0) + 360.0) % 360.0
        return if (wrapped >= 360.0) 0f else wrapped.toFloat()
    }

    fun ringPoint(hue: Float): DiscPoint {
        val radians = hue * PI / 180.0
        return DiscPoint(sin(radians), cos(radians))
    }

    fun saturationBrightnessAt(
        x: Double,
        y: Double,
    ): Pair<Float, Float> {
        val (u, v) = clampToDisc(x, y)
        val squareX = discToSquare(u, v)
        val squareY = discToSquare(v, u)
        return ((squareX + 1.0) / 2.0).toFloat().coerceIn(0f, 1f) to ((squareY + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)
    }

    fun discPoint(
        saturation: Float,
        brightness: Float,
    ): DiscPoint {
        val x = saturation.coerceIn(0f, 1f) * 2.0 - 1.0
        val y = brightness.coerceIn(0f, 1f) * 2.0 - 1.0
        return DiscPoint(x * sqrt(1.0 - y * y / 2.0), y * sqrt(1.0 - x * x / 2.0))
    }

    fun snapHue(hue: Float): Float = (((hue / PURE_HUE_STEP).roundToInt() * PURE_HUE_STEP) % 360f)

    fun snapSaturationBrightness(
        saturation: Float,
        brightness: Float,
    ): Pair<Float, Float> {
        val here = discPoint(saturation, brightness)
        val candidates = listOf(0f to 1f, 1f to 1f, saturation to 0f)
        return candidates.minBy { (s, b) -> discPoint(s, b).distanceTo(here) }
    }

    private fun clampToDisc(
        x: Double,
        y: Double,
    ): Pair<Double, Double> {
        val length = hypot(x, y)
        return if (length <= 1.0) x to y else (x / length) to (y / length)
    }

    private fun discToSquare(
        along: Double,
        across: Double,
    ): Double {
        val common = 2.0 + along * along - across * across
        val twoRootTwo = 2.0 * sqrt(2.0) * along
        val result = 0.5 * sqrt((common + twoRootTwo).coerceAtLeast(0.0)) - 0.5 * sqrt((common - twoRootTwo).coerceAtLeast(0.0))
        return result.coerceIn(-1.0, 1.0)
    }

    private const val PURE_HUE_STEP = 60f
}

data class DiscPoint(
    val x: Double,
    val y: Double,
) {
    fun distanceTo(other: DiscPoint): Double = hypot(x - other.x, y - other.y)
}

data class DiscZoom(
    val scale: Double = 1.0,
    val center: DiscPoint = DiscPoint(0.0, 0.0),
) {
    fun toDisc(view: DiscPoint): DiscPoint = DiscPoint(center.x + view.x / scale, center.y + view.y / scale)

    fun toView(disc: DiscPoint): DiscPoint = DiscPoint((disc.x - center.x) * scale, (disc.y - center.y) * scale)

    fun pinched(
        factor: Double,
        focus: DiscPoint,
        pan: DiscPoint = DiscPoint(0.0, 0.0),
    ): DiscZoom {
        val newScale = (scale * factor).coerceIn(1.0, MAXIMUM_SCALE)
        val anchored = toDisc(focus)
        val centerX = anchored.x - focus.x / newScale - pan.x / newScale
        val centerY = anchored.y - focus.y / newScale - pan.y / newScale
        val limit = 1.0 - 1.0 / newScale
        if (limit <= 0.0) return DiscZoom()
        val length = hypot(centerX, centerY)
        val center = if (length <= limit) DiscPoint(centerX, centerY) else DiscPoint(centerX / length * limit, centerY / length * limit)
        return DiscZoom(newScale, center)
    }

    companion object {
        const val MAXIMUM_SCALE = 4.0
    }
}
