package com.mobyle.nanquim.document.color

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs

data class ColorState(
    val primary: ColorValue = ColorValue.BLACK,
    val secondary: ColorValue = ColorValue.WHITE,
    val history: List<ColorValue> = emptyList(),
) {
    fun withPrimary(color: ColorValue): ColorState = copy(primary = color)

    fun withSecondary(color: ColorValue): ColorState = copy(secondary = color)

    fun swapped(): ColorState = copy(primary = secondary, secondary = primary)

    fun recordUse(color: ColorValue = primary): ColorState {
        val rest = history.filterNot { it.sameAs8Bit(color) }
        return copy(history = (listOf(color) + rest).take(HISTORY_SIZE))
    }

    fun withoutHistory(): ColorState = copy(history = emptyList())

    fun toJson(): String =
        json.encodeToString(
            StoredColors.serializer(),
            StoredColors(primary.stored(), secondary.stored(), history.map { it.stored() }),
        )

    companion object {
        const val HISTORY_SIZE = 10

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): ColorState? {
            val stored =
                try {
                    json.decodeFromString(StoredColors.serializer(), text)
                } catch (exception: SerializationException) {
                    return null
                } catch (exception: IllegalArgumentException) {
                    return null
                }
            val primary = stored.primary.color() ?: return null
            val secondary = stored.secondary.color() ?: return null
            val history =
                stored.history
                    .mapNotNull { it.color() }
                    .fold(emptyList<ColorValue>()) { kept, color -> if (kept.any { it.sameAs8Bit(color) }) kept else kept + color }
                    .take(HISTORY_SIZE)
            return ColorState(primary, secondary, history)
        }
    }
}

@Serializable
private data class StoredColor(
    val rgba: List<Float>,
    val hsb: List<Float>,
)

@Serializable
private data class StoredColors(
    val primary: StoredColor,
    val secondary: StoredColor,
    val history: List<StoredColor> = emptyList(),
)

private fun ColorValue.stored() = StoredColor(listOf(red, green, blue, alpha), listOf(hue, saturation, brightness))

private fun StoredColor.color(): ColorValue? {
    if (rgba.size != 4 || hsb.size != 3 || (rgba + hsb).any { !it.isFinite() }) return null
    val remembered = ColorValue.fromHsb(hsb[0], hsb[1], hsb[2], rgba[3])
    val color = ColorValue.fromRgb(rgba[0], rgba[1], rgba[2], rgba[3], previous = remembered)
    val consistent =
        abs(remembered.red - color.red) <= HSB_MATCH &&
            abs(remembered.green - color.green) <= HSB_MATCH &&
            abs(remembered.blue - color.blue) <= HSB_MATCH
    if (!consistent) return color
    return color.copy(hue = remembered.hue, saturation = remembered.saturation, brightness = remembered.brightness)
}

private const val HSB_MATCH = 1e-4f
