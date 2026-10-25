package com.mobyle.nanquim.document.color

import kotlin.math.roundToInt

enum class ColorComponent(
    val id: String,
    val maximum: Int,
) {
    Hue("hue", 360),
    Saturation("saturation", 100),
    Brightness("brightness", 100),
    Red("red", 255),
    Green("green", 255),
    Blue("blue", 255),
    ;

    fun fraction(color: ColorValue): Float =
        when (this) {
            Hue -> color.hue / 360f
            Saturation -> color.saturation
            Brightness -> color.brightness
            Red -> color.red
            Green -> color.green
            Blue -> color.blue
        }

    fun withFraction(
        color: ColorValue,
        fraction: Float,
    ): ColorValue {
        val value = fraction.coerceIn(0f, 1f)
        return when (this) {
            Hue -> ColorValue.fromHsb(value * 360f, color.saturation, color.brightness, color.alpha)
            Saturation -> ColorValue.fromHsb(color.hue, value, color.brightness, color.alpha)
            Brightness -> ColorValue.fromHsb(color.hue, color.saturation, value, color.alpha)
            Red -> ColorValue.fromRgb(value, color.green, color.blue, color.alpha, color)
            Green -> ColorValue.fromRgb(color.red, value, color.blue, color.alpha, color)
            Blue -> ColorValue.fromRgb(color.red, color.green, value, color.alpha, color)
        }
    }

    fun displayValue(color: ColorValue): Int = (fraction(color) * maximum).roundToInt().coerceIn(0, maximum)

    fun parse(
        text: String,
        color: ColorValue,
    ): ColorValue? {
        val number =
            text
                .trim()
                .removeSuffix("%")
                .removeSuffix("°")
                .trim()
                .replace(',', '.')
                .toFloatOrNull()
                ?.takeIf { it.isFinite() } ?: return null
        return withFraction(color, number.coerceIn(0f, maximum.toFloat()) / maximum)
    }

    fun trackStops(color: ColorValue): List<ColorValue> =
        when (this) {
            Hue -> (0..6).map { ColorValue.fromHsb(it * 60f, 1f, 1f) }
            else -> listOf(withFraction(color, 0f), withFraction(color, 1f)).map { it.copy(alpha = 1f) }
        }

    companion object {
        val HSB = listOf(Hue, Saturation, Brightness)
        val RGB = listOf(Red, Green, Blue)
    }
}
