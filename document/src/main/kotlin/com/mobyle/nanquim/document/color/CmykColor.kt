package com.mobyle.nanquim.document.color

import kotlin.math.abs
import kotlin.math.roundToInt

data class CmykInks(
    val cyan: Float,
    val magenta: Float,
    val yellow: Float,
    val black: Float,
)

enum class InkChannel(
    val id: String,
) {
    Cyan("cyan"),
    Magenta("magenta"),
    Yellow("yellow"),
    Black("black"),
    ;

    fun of(inks: CmykInks): Float =
        when (this) {
            Cyan -> inks.cyan
            Magenta -> inks.magenta
            Yellow -> inks.yellow
            Black -> inks.black
        }

    fun with(
        inks: CmykInks,
        value: Float,
    ): CmykInks {
        val fraction = value.coerceIn(0f, 1f)
        return when (this) {
            Cyan -> inks.copy(cyan = fraction)
            Magenta -> inks.copy(magenta = fraction)
            Yellow -> inks.copy(yellow = fraction)
            Black -> inks.copy(black = fraction)
        }
    }

    fun percent(inks: CmykInks): Int = (of(inks) * 100f).roundToInt().coerceIn(0, 100)

    fun parse(
        text: String,
        inks: CmykInks,
    ): CmykInks? {
        val number =
            text
                .trim()
                .removeSuffix("%")
                .trim()
                .replace(',', '.')
                .toFloatOrNull()
                ?.takeIf { it.isFinite() } ?: return null
        return with(inks, number.coerceIn(0f, 100f) / 100f)
    }
}

interface CmykConversion {
    fun inksOf(color: ColorValue): CmykInks

    fun colorOf(
        inks: CmykInks,
        alpha: Float,
        previous: ColorValue? = null,
    ): ColorValue

    fun printable(color: ColorValue): ColorValue {
        val printed = colorOf(inksOf(color), color.alpha, color)
        val unchanged =
            abs(printed.red - color.red) <= SAME_COLOR &&
                abs(printed.green - color.green) <= SAME_COLOR &&
                abs(printed.blue - color.blue) <= SAME_COLOR
        return if (unchanged) color else printed
    }

    companion object {
        const val SAME_COLOR = 1.5f / 255f
    }
}
