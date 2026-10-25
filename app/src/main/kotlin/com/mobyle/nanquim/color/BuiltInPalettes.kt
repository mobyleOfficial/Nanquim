package com.mobyle.nanquim.color

import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteSwatch

fun builtInPalettes(name: (Int) -> String): List<Palette> =
    listOf(
        palette(
            name(R.string.palette_basics),
            (0..7).map { level -> gray(level / 7f) } +
                (0 until 12).map { step -> ColorValue.fromHsb(step * 30f, 0.9f, 0.95f).toHex() } +
                (0 until 10).map { step -> ColorValue.fromHsb(step * 36f, 0.8f, 0.5f).toHex() },
        ),
        palette(
            name(R.string.palette_earth),
            listOf(
                "#2B1D14",
                "#4A3021",
                "#6B4430",
                "#8C5A3C",
                "#A9744F",
                "#C49A6C",
                "#DDBE94",
                "#EFDDC0",
                "#3B3A24",
                "#5C5B33",
                "#7D7A45",
                "#A3A066",
                "#C7C38F",
                "#5A2E1F",
                "#8A4B2F",
                "#B5683F",
                "#D98F5B",
                "#7A6A58",
                "#9E8C76",
                "#C2B39E",
            ),
        ),
        palette(
            name(R.string.palette_pastel),
            listOf(
                "#F7C6C7",
                "#F9D9B5",
                "#FBF1B7",
                "#D4F0C0",
                "#BDE8E2",
                "#C3DDF7",
                "#D2CCF2",
                "#EBCDEE",
                "#F2F2F2",
                "#E0E0E0",
                "#F4A6A8",
                "#F6BE8C",
                "#F3E38C",
                "#B2DE9A",
                "#94D6CC",
                "#9CC5EE",
                "#B3A9E8",
                "#DCAAE0",
            ),
        ),
    )

private fun gray(level: Float): String = ColorValue.fromRgb(level, level, level).toHex()

private fun palette(
    name: String,
    hexColors: List<String>,
): Palette {
    val swatches =
        hexColors.map { hex ->
            val color = ColorValue.parseHex(hex) ?: error("Bad built-in color $hex")
            PaletteSwatch(RgbaColor(color.red, color.green, color.blue, 1f))
        }
    val slots = ((swatches.size + Palette.DEFAULT_COLUMNS - 1) / Palette.DEFAULT_COLUMNS) * Palette.DEFAULT_COLUMNS
    return Palette(name, swatches + List(slots - swatches.size) { null })
}
