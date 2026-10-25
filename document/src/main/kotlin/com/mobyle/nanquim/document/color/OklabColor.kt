package com.mobyle.nanquim.document.color

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

data class OklabColor(
    val lightness: Double,
    val a: Double,
    val b: Double,
) {
    fun distanceTo(other: OklabColor): Double {
        val dl = lightness - other.lightness
        val da = a - other.a
        val db = b - other.b
        return sqrt(dl * dl + da * da + db * db)
    }

    fun toSrgb(): Triple<Double, Double, Double> {
        val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
        val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
        val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
        val red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
        val green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
        val blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
        return Triple(encode(red), encode(green), encode(blue))
    }

    companion object {
        fun fromSrgb(
            red: Double,
            green: Double,
            blue: Double,
        ): OklabColor {
            val r = decode(red)
            val g = decode(green)
            val b = decode(blue)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            return OklabColor(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }

        fun fromColor(color: ColorValue): OklabColor = fromSrgb(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())

        private fun decode(value: Double): Double {
            val v = value.coerceIn(0.0, 1.0)
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        private fun encode(value: Double): Double {
            val v = value.coerceIn(0.0, 1.0)
            return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
        }
    }
}
