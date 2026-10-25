package com.mobyle.nanquim.formats.palette

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.interchange.ImportWarning
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

object PaletteInterchange {
    fun fromEngineJson(
        text: String,
        fallbackName: String,
    ): Pair<Palette, List<ImportWarning>> {
        val root =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw PaletteFormatException("Not palette JSON")
        val swatches =
            (root["swatches"] as? JsonArray ?: throw PaletteFormatException("No swatches"))
                .take(Palette.MAXIMUM_SWATCHES)
                .map { entry ->
                    val swatch = entry as? JsonObject ?: throw PaletteFormatException("Bad swatch")
                    val color =
                        (swatch["color"] as? JsonArray)
                            ?.map { (it as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) ?: 0f }
                            ?.takeIf { it.size == 4 }
                            ?: throw PaletteFormatException("Bad swatch color")
                    PaletteSwatch(RgbaColor(color[0], color[1], color[2], color[3]), swatch.text("name"))
                }
        val name = root.text("name").ifBlank { fallbackName }
        val columns = (root["columns"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..Palette.MAXIMUM_COLUMNS } ?: Palette.DEFAULT_COLUMNS
        val warnings =
            (root["warnings"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val warning = entry as? JsonObject ?: return@mapNotNull null
                ImportWarning(warning.text("code"), warning.text("detail"))
            }
        return Palette(name, swatches, columns) to warnings
    }

    fun writeGpl(palette: Palette): String =
        buildString {
            append("GIMP Palette\n")
            append("Name: ").append(palette.name.singleLine()).append('\n')
            append("Columns: ").append(palette.columns).append('\n')
            append("#\n")
            for (swatch in palette.swatches.filterNotNull()) {
                val (red, green, blue) = swatch.color.levels()
                append("%3d %3d %3d".format(red, green, blue))
                append('\t').append(swatch.name.singleLine().ifEmpty { "#%02X%02X%02X".format(red, green, blue) })
                append('\n')
            }
        }

    fun writeAse(palette: Palette): ByteArray {
        val colors = palette.swatches.filterNotNull()
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeBytes("ASEF")
            data.writeShort(1)
            data.writeShort(0)
            data.writeInt(colors.size + 2)
            writeBlock(data, GROUP_START) { block -> writeName(block, palette.name) }
            for (swatch in colors) {
                writeBlock(data, COLOR_ENTRY) { block ->
                    val (red, green, blue) = swatch.color.levels()
                    writeName(block, swatch.name.ifEmpty { "#%02X%02X%02X".format(red, green, blue) })
                    block.writeBytes("RGB ")
                    block.writeFloat(swatch.color.red)
                    block.writeFloat(swatch.color.green)
                    block.writeFloat(swatch.color.blue)
                    block.writeShort(NORMAL_COLOR)
                }
            }
            writeBlock(data, GROUP_END) {}
        }
        return output.toByteArray()
    }

    private fun writeBlock(
        data: DataOutputStream,
        type: Int,
        body: (DataOutputStream) -> Unit,
    ) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(body)
        data.writeShort(type)
        data.writeInt(bytes.size())
        data.write(bytes.toByteArray())
    }

    private fun writeName(
        data: DataOutputStream,
        name: String,
    ) {
        val units = name.take(MAXIMUM_NAME_UNITS)
        data.writeShort(units.length + 1)
        for (unit in units) data.writeChar(unit.code)
        data.writeShort(0)
    }

    private fun RgbaColor.levels(): Triple<Int, Int, Int> =
        Triple((red * 255f).roundToInt(), (green * 255f).roundToInt(), (blue * 255f).roundToInt())

    private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ').trim()

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    private const val GROUP_START = 0xC001
    private const val GROUP_END = 0xC002
    private const val COLOR_ENTRY = 0x0001
    private const val NORMAL_COLOR = 2
    private const val MAXIMUM_NAME_UNITS = 1024
}
