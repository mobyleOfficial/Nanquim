package com.mobyle.nanquim.document.guide

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal object GuideLines {
    fun family(
        direction: Double,
        originX: Double,
        originY: Double,
        step: Double,
        area: GuideRect,
        maximumLines: Int,
    ): List<GuideSegment> {
        val dx = cos(direction)
        val dy = sin(direction)
        val nx = -dy
        val ny = dx
        val base = nx * originX + ny * originY
        val values =
            listOf(area.left to area.top, area.right to area.top, area.left to area.bottom, area.right to area.bottom)
                .map { (x, y) -> nx * x + ny * y }
        val first = ceil((values.min() - base) / step).toLong()
        val last = floor((values.max() - base) / step).toLong()
        if (last - first + 1 > maximumLines) return emptyList()
        return (first..last).mapNotNull { index ->
            val c = base + index * step
            line(nx * c, ny * c, direction, area)
        }
    }

    fun line(
        x: Double,
        y: Double,
        direction: Double,
        area: GuideRect,
        emphasized: Boolean = false,
    ): GuideSegment? = clip(x, y, cos(direction), sin(direction), Double.NEGATIVE_INFINITY, area, emphasized)

    fun ray(
        x: Double,
        y: Double,
        direction: Double,
        area: GuideRect,
    ): GuideSegment? = clip(x, y, cos(direction), sin(direction), 0.0, area, false)

    private fun clip(
        px: Double,
        py: Double,
        dx: Double,
        dy: Double,
        start: Double,
        area: GuideRect,
        emphasized: Boolean,
    ): GuideSegment? {
        var low = start
        var high = Double.POSITIVE_INFINITY

        fun limit(
            delta: Double,
            from: Double,
            minimum: Double,
            maximum: Double,
        ): Boolean {
            if (abs(delta) < EPSILON) return from in minimum..maximum
            val a = (minimum - from) / delta
            val b = (maximum - from) / delta
            low = maxOf(low, minOf(a, b))
            high = minOf(high, maxOf(a, b))
            return low <= high
        }
        if (!limit(dx, px, area.left, area.right) || !limit(dy, py, area.top, area.bottom)) return null
        if (high - low < EPSILON) return null
        return GuideSegment(px + dx * low, py + dy * low, px + dx * high, py + dy * high, emphasized)
    }

    private const val EPSILON = 1e-9
}
