package com.mobyle.nanquim.document.color

import kotlin.math.hypot

enum class ColorHarmony(
    val id: String,
    val hueOffsets: List<Float>,
) {
    Complementary("complementary", listOf(180f)),
    SplitComplementary("splitComplementary", listOf(150f, 210f)),
    Analogous("analogous", listOf(330f, 30f)),
    Triadic("triadic", listOf(120f, 240f)),
    Tetradic("tetradic", listOf(90f, 180f, 270f)),
    ;

    fun colors(base: ColorValue): List<ColorValue> =
        listOf(base) +
            hueOffsets.map { offset -> ColorValue.fromHsb(base.hue + offset, base.saturation, base.brightness, base.alpha) }

    companion object {
        fun fromId(id: String): ColorHarmony? = entries.firstOrNull { it.id == id }
    }
}

object HarmonyDisc {
    fun point(
        hue: Float,
        saturation: Float,
    ): DiscPoint {
        val ring = ColorDisc.ringPoint(hue)
        val radius = saturation.coerceIn(0f, 1f).toDouble()
        return DiscPoint(ring.x * radius, ring.y * radius)
    }

    fun hueSaturationAt(
        x: Double,
        y: Double,
        previousHue: Float,
    ): Pair<Float, Float> {
        val distance = hypot(x, y)
        val hue = if (distance < CENTER) previousHue else ColorDisc.hueAt(x, y)
        return hue to distance.coerceAtMost(1.0).toFloat()
    }

    fun markerAt(
        colors: List<ColorValue>,
        x: Double,
        y: Double,
        reach: Double,
    ): Int? {
        val here = DiscPoint(x, y)
        return colors.indices
            .map { index -> index to point(colors[index].hue, colors[index].saturation).distanceTo(here) }
            .filter { (_, distance) -> distance <= reach }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    private const val CENTER = 1e-6
}
