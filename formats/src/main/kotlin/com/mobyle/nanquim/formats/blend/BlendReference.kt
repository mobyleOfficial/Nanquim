package com.mobyle.nanquim.formats.blend

import com.mobyle.nanquim.document.layer.BlendMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Rgba(
    val red: Double,
    val green: Double,
    val blue: Double,
    val alpha: Double,
) {
    fun premultiplied(): Rgba = Rgba(red * alpha, green * alpha, blue * alpha, alpha)

    fun unpremultiplied(): Rgba =
        if (alpha <= 0.0) TRANSPARENT else Rgba(red / alpha, green / alpha, blue / alpha, alpha)

    companion object {
        val TRANSPARENT = Rgba(0.0, 0.0, 0.0, 0.0)
    }
}

object BlendReference {
    const val HARD_MIX_THRESHOLD = 1 - 1.0 / 1024

    fun separable(
        mode: BlendMode,
        backdrop: Double,
        source: Double,
    ): Double =
        when (mode) {
            BlendMode.Normal, BlendMode.PassThrough -> source
            BlendMode.Multiply -> backdrop * source
            BlendMode.Screen -> screen(backdrop, source)
            BlendMode.Overlay -> hardLight(source = backdrop, backdrop = source)
            BlendMode.Darken -> min(backdrop, source)
            BlendMode.Lighten -> max(backdrop, source)
            BlendMode.ColorDodge -> colorDodge(backdrop, source)
            BlendMode.ColorBurn -> colorBurn(backdrop, source)
            BlendMode.HardLight -> hardLight(backdrop, source)
            BlendMode.SoftLight -> softLight(backdrop, source)
            BlendMode.Difference -> abs(backdrop - source)
            BlendMode.Exclusion -> backdrop + source - 2 * backdrop * source
            BlendMode.Add -> min(1.0, backdrop + source)
            BlendMode.Subtract -> max(0.0, backdrop - source)
            BlendMode.Divide ->
                when {
                    source == 0.0 -> if (backdrop == 0.0) 0.0 else 1.0
                    else -> min(1.0, backdrop / source)
                }
            BlendMode.LinearBurn -> max(0.0, backdrop + source - 1)
            BlendMode.VividLight -> if (source <= 0.5) colorBurn(backdrop, 2 * source) else colorDodge(backdrop, 2 * source - 1)
            BlendMode.LinearLight -> (backdrop + 2 * source - 1).coerceIn(0.0, 1.0)
            BlendMode.PinLight -> if (source <= 0.5) min(backdrop, 2 * source) else max(backdrop, 2 * source - 1)
            BlendMode.HardMix -> if (backdrop + source >= HARD_MIX_THRESHOLD) 1.0 else 0.0
            else -> throw IllegalArgumentException("${mode.id} is not separable")
        }

    fun blendColor(
        mode: BlendMode,
        backdrop: DoubleArray,
        source: DoubleArray,
    ): DoubleArray =
        when (mode) {
            BlendMode.Hue -> setLuminosity(setSaturation(source, saturation(backdrop)), luminosity(backdrop))
            BlendMode.Saturation -> setLuminosity(setSaturation(backdrop, saturation(source)), luminosity(backdrop))
            BlendMode.Color -> setLuminosity(source, luminosity(backdrop))
            BlendMode.Luminosity -> setLuminosity(backdrop, luminosity(source))
            BlendMode.DarkerColor -> if (luminosity(source) < luminosity(backdrop)) source.copyOf() else backdrop.copyOf()
            BlendMode.LighterColor -> if (luminosity(source) > luminosity(backdrop)) source.copyOf() else backdrop.copyOf()
            else -> DoubleArray(3) { channel -> separable(mode, backdrop[channel], source[channel]) }
        }

    fun composite(
        mode: BlendMode,
        backdrop: Rgba,
        source: Rgba,
    ): Rgba {
        val sourceAlpha = source.alpha
        val backdropAlpha = backdrop.alpha
        val cs = source.unpremultiplied()
        val cb = backdrop.unpremultiplied()
        val blended = blendColor(mode, doubleArrayOf(cb.red, cb.green, cb.blue), doubleArrayOf(cs.red, cs.green, cs.blue))
        fun channel(
            index: Int,
            sourceColor: Double,
            backdropColor: Double,
        ) = sourceAlpha * (1 - backdropAlpha) * sourceColor + sourceAlpha * backdropAlpha * blended[index] +
            (1 - sourceAlpha) * backdropAlpha * backdropColor
        return Rgba(
            channel(0, cs.red, cb.red),
            channel(1, cs.green, cb.green),
            channel(2, cs.blue, cb.blue),
            sourceAlpha + backdropAlpha * (1 - sourceAlpha),
        )
    }

    fun clipComposite(
        mode: BlendMode,
        backdrop: Rgba,
        source: Rgba,
    ): Rgba {
        val cs = source.unpremultiplied()
        val cb = backdrop.unpremultiplied()
        val blended = blendColor(mode, doubleArrayOf(cb.red, cb.green, cb.blue), doubleArrayOf(cs.red, cs.green, cs.blue))
        val sourceAlpha = source.alpha
        val backdropAlpha = backdrop.alpha
        fun channel(
            index: Int,
            backdropColor: Double,
        ) = sourceAlpha * backdropAlpha * blended[index] + (1 - sourceAlpha) * backdropAlpha * backdropColor
        return Rgba(channel(0, cb.red), channel(1, cb.green), channel(2, cb.blue), backdropAlpha)
    }

    fun behind(
        backdrop: Rgba,
        source: Rgba,
    ): Rgba =
        Rgba(
            backdrop.red + source.red * (1 - backdrop.alpha),
            backdrop.green + source.green * (1 - backdrop.alpha),
            backdrop.blue + source.blue * (1 - backdrop.alpha),
            backdrop.alpha + source.alpha * (1 - backdrop.alpha),
        )

    private fun screen(
        backdrop: Double,
        source: Double,
    ) = backdrop + source - backdrop * source

    private fun hardLight(
        backdrop: Double,
        source: Double,
    ) = if (source <= 0.5) backdrop * (2 * source) else screen(backdrop, 2 * source - 1)

    private fun colorDodge(
        backdrop: Double,
        source: Double,
    ) = when {
        backdrop == 0.0 -> 0.0
        source == 1.0 -> 1.0
        else -> min(1.0, backdrop / (1 - source))
    }

    private fun colorBurn(
        backdrop: Double,
        source: Double,
    ) = when {
        backdrop == 1.0 -> 1.0
        source == 0.0 -> 0.0
        else -> 1 - min(1.0, (1 - backdrop) / source)
    }

    private fun softLight(
        backdrop: Double,
        source: Double,
    ): Double {
        if (source <= 0.5) return backdrop - (1 - 2 * source) * backdrop * (1 - backdrop)
        val d = if (backdrop <= 0.25) ((16 * backdrop - 12) * backdrop + 4) * backdrop else sqrt(backdrop)
        return backdrop + (2 * source - 1) * (d - backdrop)
    }

    fun luminosity(color: DoubleArray): Double = 0.3 * color[0] + 0.59 * color[1] + 0.11 * color[2]

    private fun clipColor(color: DoubleArray): DoubleArray {
        val lum = luminosity(color)
        val lowest = minOf(color[0], color[1], color[2])
        val highest = maxOf(color[0], color[1], color[2])
        val result = color.copyOf()
        if (lowest < 0) {
            for (channel in 0..2) result[channel] = if (lum <= 0) 0.0 else lum + (result[channel] - lum) * lum / (lum - lowest)
        }
        if (highest > 1) {
            for (channel in 0..2) result[channel] = if (lum >= 1) 1.0 else lum + (result[channel] - lum) * (1 - lum) / (highest - lum)
        }
        return result
    }

    private fun setLuminosity(
        color: DoubleArray,
        lum: Double,
    ): DoubleArray {
        val delta = lum - luminosity(color)
        return clipColor(DoubleArray(3) { color[it] + delta })
    }

    private fun saturation(color: DoubleArray): Double = maxOf(color[0], color[1], color[2]) - minOf(color[0], color[1], color[2])

    private fun setSaturation(
        color: DoubleArray,
        saturation: Double,
    ): DoubleArray {
        val highest = maxOf(color[0], color[1], color[2])
        val lowest = minOf(color[0], color[1], color[2])
        if (highest <= lowest) return DoubleArray(3)
        return DoubleArray(3) { channel -> (color[channel] - lowest) * saturation / (highest - lowest) }
    }
}
