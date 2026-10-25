package com.mobyle.nanquim.formats.gradient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class GradientStop(
    val position: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    init {
        require(listOf(position, red, green, blue).all { it in 0f..1f }) { "Gradient stop values must be 0-1" }
    }
}

data class GradientDefinition(
    val name: String,
    val stops: List<GradientStop>,
) {
    init {
        require(stops.size in 1..MAXIMUM_STOPS) { "A gradient has 1-$MAXIMUM_STOPS stops" }
        require(stops.zipWithNext().all { (first, second) -> first.position <= second.position }) {
            "Gradient stops must be in position order"
        }
    }

    fun tableData(): FloatArray =
        FloatArray(1 + stops.size * 4).also { data ->
            data[0] = stops.size.toFloat()
            stops.forEachIndexed { index, stop ->
                data[1 + index * 4] = stop.position
                data[2 + index * 4] = stop.red
                data[3 + index * 4] = stop.green
                data[4 + index * 4] = stop.blue
            }
        }

    fun colorAt(position: Float): FloatArray {
        val after = stops.indexOfFirst { it.position > position }
        return when (after) {
            0 -> stops.first().rgb()
            -1 -> stops.last().rgb()
            else -> {
                val from = stops[after - 1]
                val to = stops[after]
                val span = to.position - from.position
                val mix = if (span > 0f) (position - from.position) / span else 1f
                FloatArray(3) { channel -> from.rgb()[channel] + (to.rgb()[channel] - from.rgb()[channel]) * mix }
            }
        }
    }

    companion object {
        const val MAXIMUM_STOPS = 64
    }
}

private fun GradientStop.rgb() = floatArrayOf(red, green, blue)

class GradientFormatException(
    message: String,
) : Exception(message)

object GradientFile {
    const val FORMAT = 1

    private val json = Json { prettyPrint = true }

    fun write(gradient: GradientDefinition): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", FORMAT)
                put("name", gradient.name)
                put(
                    "stops",
                    buildJsonArray {
                        for (stop in gradient.stops) {
                            add(
                                buildJsonObject {
                                    put("position", stop.position)
                                    put(
                                        "color",
                                        buildJsonArray {
                                            add(JsonPrimitive(stop.red))
                                            add(JsonPrimitive(stop.green))
                                            add(JsonPrimitive(stop.blue))
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            },
        )

    fun read(text: String): GradientDefinition {
        try {
            val root = Json.parseToJsonElement(text).jsonObject
            val format = root["format"]?.jsonPrimitive?.intOrNull ?: throw GradientFormatException("Missing format")
            if (format > FORMAT) throw GradientFormatException("Unsupported gradient format $format")
            val name = root["name"]?.jsonPrimitive?.content ?: ""
            val stops =
                (root["stops"] as? JsonArray ?: throw GradientFormatException("Missing stops")).map { element ->
                    val stop = element.jsonObject
                    val position = stop["position"]?.jsonPrimitive?.floatOrNull ?: throw GradientFormatException("Stop without position")
                    val color = stop["color"]?.jsonArray?.map { it.jsonPrimitive.floatOrNull } ?: emptyList()
                    if (color.size != 3 || color.any { it == null }) throw GradientFormatException("Stop color must be [r, g, b]")
                    GradientStop(position, color[0]!!, color[1]!!, color[2]!!)
                }
            return GradientDefinition(name, stops.sortedBy { it.position })
        } catch (exception: IllegalArgumentException) {
            throw GradientFormatException(exception.message ?: "Invalid gradient")
        } catch (exception: IllegalStateException) {
            throw GradientFormatException(exception.message ?: "Invalid gradient")
        }
    }
}
