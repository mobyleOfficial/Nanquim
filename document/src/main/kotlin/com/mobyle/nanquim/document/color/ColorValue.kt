package com.mobyle.nanquim.document.color

import kotlin.math.roundToInt

data class ColorValue(
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float,
    val hue: Float,
    val saturation: Float,
    val brightness: Float,
) {
    fun toHex(): String = "#%02X%02X%02X".format(red.toByte255(), green.toByte255(), blue.toByte255())

    fun sameAs8Bit(other: ColorValue): Boolean =
        red.toByte255() == other.red.toByte255() &&
            green.toByte255() == other.green.toByte255() &&
            blue.toByte255() == other.blue.toByte255() &&
            alpha.toByte255() == other.alpha.toByte255()

    companion object {
        val BLACK = fromRgb(0f, 0f, 0f)
        val WHITE = fromRgb(1f, 1f, 1f)

        fun fromRgb(
            red: Float,
            green: Float,
            blue: Float,
            alpha: Float = 1f,
            previous: ColorValue? = null,
        ): ColorValue {
            val r = red.coerceIn(0f, 1f)
            val g = green.coerceIn(0f, 1f)
            val b = blue.coerceIn(0f, 1f)
            val maximum = maxOf(r, g, b)
            val minimum = minOf(r, g, b)
            val delta = maximum - minimum
            val brightness = maximum
            val saturation =
                when {
                    maximum <= 0f -> previous?.saturation ?: 0f
                    else -> delta / maximum
                }
            val hue =
                if (delta <= 0f) {
                    previous?.hue ?: 0f
                } else {
                    val sector =
                        when (maximum) {
                            r -> ((g - b) / delta).mod(6f)
                            g -> (b - r) / delta + 2f
                            else -> (r - g) / delta + 4f
                        }
                    (sector * 60f).normalizedHue()
                }
            return ColorValue(r, g, b, alpha.coerceIn(0f, 1f), hue, saturation, brightness)
        }

        fun fromHsb(
            hue: Float,
            saturation: Float,
            brightness: Float,
            alpha: Float = 1f,
        ): ColorValue {
            val h = hue.normalizedHue()
            val s = saturation.coerceIn(0f, 1f)
            val v = brightness.coerceIn(0f, 1f)
            val chroma = v * s
            val sector = h / 60f
            val second = chroma * (1f - kotlin.math.abs(sector.mod(2f) - 1f))
            val (r, g, b) =
                when (sector.toInt()) {
                    0 -> Triple(chroma, second, 0f)
                    1 -> Triple(second, chroma, 0f)
                    2 -> Triple(0f, chroma, second)
                    3 -> Triple(0f, second, chroma)
                    4 -> Triple(second, 0f, chroma)
                    else -> Triple(chroma, 0f, second)
                }
            val offset = v - chroma
            return ColorValue(
                (r + offset).coerceIn(0f, 1f),
                (g + offset).coerceIn(0f, 1f),
                (b + offset).coerceIn(0f, 1f),
                alpha.coerceIn(0f, 1f),
                h,
                s,
                v,
            )
        }

        fun parseHex(
            text: String,
            previous: ColorValue? = null,
        ): ColorValue? {
            val digits = text.trim().removePrefix("#")
            if (digits.length != 6 || digits.any { it.digitToIntOrNull(16) == null }) return null
            val value = digits.toInt(16)
            return fromRgb(
                ((value shr 16) and 0xFF) / 255f,
                ((value shr 8) and 0xFF) / 255f,
                (value and 0xFF) / 255f,
                previous?.alpha ?: 1f,
                previous,
            )
        }
    }
}

private fun Float.toByte255(): Int = (coerceIn(0f, 1f) * 255f).roundToInt()

private fun Float.normalizedHue(): Float {
    val wrapped = mod(360f)
    return if (wrapped >= 360f) 0f else wrapped
}
