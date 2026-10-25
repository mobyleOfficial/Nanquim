package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

data class ArtistInfo(
    val name: String = "",
    val signature: List<List<Float>> = emptyList(),
) {
    val isEmpty: Boolean get() = name.isBlank() && signature.isEmpty()

    companion object {
        const val SIGNATURE_ASPECT = 3f

        const val MAXIMUM_POINTS = 4096

        fun read(value: JsonElement?): ArtistInfo? {
            val json = value as? JsonObject ?: return null
            val name = (json["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            var budget = MAXIMUM_POINTS * 2
            val strokes =
                (json["signature"] as? JsonArray).orEmpty().mapNotNull { stroke ->
                    val numbers =
                        (stroke as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, 1f) }
                    val even = numbers.take(minOf(numbers.size / 2 * 2, budget))
                    budget -= even.size
                    even.takeIf { it.size >= 2 }
                }
            return ArtistInfo(name, strokes).takeUnless { it.isEmpty }
        }

        fun write(
            artist: ArtistInfo,
            previous: JsonObject?,
        ): JsonObject {
            val fields = previous.orEmpty() - "signature" + ("name" to JsonPrimitive(artist.name))
            if (artist.signature.isEmpty()) return JsonObject(fields)
            val signature = JsonArray(artist.signature.map { stroke -> JsonArray(stroke.map { JsonPrimitive(it) }) })
            return JsonObject(fields + ("signature" to signature))
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
    }
}
