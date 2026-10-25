package com.mobyle.nanquim.gallery

import com.mobyle.nanquim.formats.nanquim.CanvasUnit
import kotlin.math.roundToInt

data class CanvasSize(
    val width: Double,
    val height: Double,
    val unit: CanvasUnit,
    val dpi: Double,
) {
    val widthPx: Int get() = toPixels(width, unit, dpi)
    val heightPx: Int get() = toPixels(height, unit, dpi)

    companion object {
        fun toPixels(
            value: Double,
            unit: CanvasUnit,
            dpi: Double,
        ): Int =
            when (unit) {
                CanvasUnit.Pixels -> value
                CanvasUnit.Inches -> value * dpi
                CanvasUnit.Centimeters -> value / 2.54 * dpi
                CanvasUnit.Millimeters -> value / 25.4 * dpi
            }.roundToInt().coerceAtLeast(1)

        fun fromPixels(
            pixels: Int,
            unit: CanvasUnit,
            dpi: Double,
        ): Double =
            when (unit) {
                CanvasUnit.Pixels -> pixels.toDouble()
                CanvasUnit.Inches -> pixels / dpi
                CanvasUnit.Centimeters -> pixels / dpi * 2.54
                CanvasUnit.Millimeters -> pixels / dpi * 25.4
            }
    }
}

data class CanvasPreset(
    val id: String,
    val name: String?,
    val builtInKey: String?,
    val size: CanvasSize,
) {
    companion object {
        fun from(entity: CanvasPresetEntity) =
            CanvasPreset(
                entity.id,
                entity.name,
                entity.builtInKey,
                CanvasSize(entity.width, entity.height, CanvasUnit.fromId(entity.unit) ?: CanvasUnit.Pixels, entity.dpi),
            )
    }
}

object BuiltInCanvasPresets {
    const val SCREEN = "screen"
    const val SQUARE = "square"
    const val UHD = "uhd"
    const val A4 = "a4"
    const val A3 = "a3"
    const val PHOTO = "photo"
    const val LETTER = "letter"
    const val COMIC = "comic"

    const val PIXEL_PRESET_DPI = 132.0

    fun all(
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): List<Pair<String, CanvasSize>> =
        listOf(
            SCREEN to CanvasSize(screenWidthPx.toDouble(), screenHeightPx.toDouble(), CanvasUnit.Pixels, PIXEL_PRESET_DPI),
            SQUARE to CanvasSize(2048.0, 2048.0, CanvasUnit.Pixels, PIXEL_PRESET_DPI),
            UHD to CanvasSize(3840.0, 2160.0, CanvasUnit.Pixels, PIXEL_PRESET_DPI),
            A4 to CanvasSize(210.0, 297.0, CanvasUnit.Millimeters, 300.0),
            A3 to CanvasSize(297.0, 420.0, CanvasUnit.Millimeters, 300.0),
            PHOTO to CanvasSize(6.0, 4.0, CanvasUnit.Inches, 300.0),
            LETTER to CanvasSize(8.5, 11.0, CanvasUnit.Inches, 300.0),
            COMIC to CanvasSize(6.625, 10.25, CanvasUnit.Inches, 600.0),
        )
}
