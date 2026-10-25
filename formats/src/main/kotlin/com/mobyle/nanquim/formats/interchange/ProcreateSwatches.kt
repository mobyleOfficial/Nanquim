package com.mobyle.nanquim.formats.interchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import kotlin.math.floor

object ProcreateSwatches {
    const val ENTRY = "Swatches.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Pair<Palette, List<ImportWarning>> {
        val root =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw ImportJsonException("Swatches.json is not a JSON object")
        val warnings = mutableListOf<ImportWarning>()
        val profiles = root["colorProfiles"] as? JsonArray
        val nonSrgb =
            profiles
                ?.mapNotNull { (it as? JsonObject)?.get("iccName")?.let { name -> (name as? JsonPrimitive)?.contentOrNull } }
                ?.filterNot { "sRGB" in it }
                .orEmpty()
        nonSrgb.forEach { warnings += ImportWarning("colorProfileIgnored", it) }
        val entries = root["swatches"] as? JsonArray ?: throw ImportJsonException("No swatches")
        val swatches =
            entries.take(Palette.MAXIMUM_SWATCHES).map { element ->
                val swatch = element as? JsonObject ?: return@map null
                val hue = swatch.number("hue") ?: return@map null
                val saturation = swatch.number("saturation") ?: return@map null
                val brightness = swatch.number("brightness") ?: return@map null
                val alpha = swatch.number("alpha") ?: 1.0
                val (red, green, blue) = hsbToRgb(hue, saturation, brightness)
                PaletteSwatch(
                    RgbaColor(red.toFloat(), green.toFloat(), blue.toFloat(), alpha.coerceIn(0.0, 1.0).toFloat()),
                    (swatch["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                )
            }
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return Palette(name, swatches) to warnings
    }

    internal fun hsbToRgb(
        hue: Double,
        saturation: Double,
        brightness: Double,
    ): Triple<Double, Double, Double> {
        val h = (hue - floor(hue)) * 6
        val s = saturation.coerceIn(0.0, 1.0)
        val v = brightness.coerceIn(0.0, 1.0)
        val sector = floor(h).toInt() % 6
        val fraction = h - floor(h)
        val p = v * (1 - s)
        val q = v * (1 - s * fraction)
        val t = v * (1 - s * (1 - fraction))
        return when (sector) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
    }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
}
