package com.mobyle.nanquim.formats.palette

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.document.layer.RgbaColor

data class Palette(
    val name: String,
    val swatches: List<PaletteSwatch?>,
    val columns: Int = DEFAULT_COLUMNS,
) {
    init {
        require(swatches.size <= MAXIMUM_SWATCHES) { "A palette has at most $MAXIMUM_SWATCHES swatches" }
        require(columns in 1..MAXIMUM_COLUMNS) { "A palette has 1-$MAXIMUM_COLUMNS columns" }
    }

    fun toJson(id: String): String = PaletteFile.write(id, this)

    companion object {
        const val MAXIMUM_SWATCHES = 300
        const val DEFAULT_COLUMNS = 10
        const val MAXIMUM_COLUMNS = 30
    }
}

data class PaletteSwatch(
    val color: RgbaColor,
    val name: String = "",
)

class PaletteFormatException(
    message: String,
) : Exception(message)

data class StoredPalette(
    val id: String,
    val palette: Palette,
)

object PaletteFile {
    const val FORMAT = 1

    private val json = Json { prettyPrint = true }

    fun write(
        id: String,
        palette: Palette,
    ): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", FORMAT)
                put("id", id)
                put("name", palette.name)
                put("columns", palette.columns)
                putJsonArray("swatches") {
                    for (swatch in palette.swatches) {
                        if (swatch == null) {
                            add(JsonNull)
                        } else {
                            addJsonObject {
                                putJsonArray("color") {
                                    with(swatch.color) {
                                        add(JsonPrimitive(red))
                                        add(JsonPrimitive(green))
                                        add(JsonPrimitive(blue))
                                        add(JsonPrimitive(alpha))
                                    }
                                }
                                put("name", swatch.name)
                            }
                        }
                    }
                }
            },
        )

    fun read(text: String): StoredPalette {
        val root =
            runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw PaletteFormatException("A palette is a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.intOrNull
        if (format != FORMAT) throw PaletteFormatException("Unsupported palette format $format")
        val id = root.string("id") ?: throw PaletteFormatException("A palette needs an id")
        val name = root.string("name") ?: throw PaletteFormatException("A palette needs a name")
        val columns = root["columns"]?.let { (it as? JsonPrimitive)?.intOrNull ?: throw PaletteFormatException("Bad columns") }
        if (columns != null && columns !in 1..Palette.MAXIMUM_COLUMNS) throw PaletteFormatException("Bad columns $columns")
        val entries = root["swatches"] as? JsonArray ?: throw PaletteFormatException("A palette needs swatches")
        if (entries.size > Palette.MAXIMUM_SWATCHES) throw PaletteFormatException("More than ${Palette.MAXIMUM_SWATCHES} swatches")
        val swatches =
            entries.map { entry ->
                if (entry is JsonNull) return@map null
                val swatch = entry as? JsonObject ?: throw PaletteFormatException("A swatch is an object or null")
                val components =
                    (swatch["color"] as? JsonArray)
                        ?.map { (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.doubleOrNull }
                        ?.takeIf { it.size in 3..4 && it.all { value -> value != null && value.isFinite() } }
                        ?.map { it!!.toFloat().coerceIn(0f, 1f) }
                        ?: throw PaletteFormatException("A swatch color is [r, g, b] or [r, g, b, a]")
                PaletteSwatch(
                    RgbaColor(components[0], components[1], components[2], components.getOrElse(3) { 1f }),
                    swatch.string("name").orEmpty(),
                )
            }
        return StoredPalette(id, Palette(name, swatches, columns ?: Palette.DEFAULT_COLUMNS))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
