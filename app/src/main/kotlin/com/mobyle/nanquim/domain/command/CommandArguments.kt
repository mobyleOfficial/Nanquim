package com.mobyle.nanquim.domain.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The screens that build arguments as a map (gallery, new canvas, canvas) turn them into a command's JSON with this. */
fun Map<String, Any?>.toCommandArguments(): JsonObject = JsonObject(mapValues { (name, value) -> toJson(name, value) })

private fun toJson(
    name: String,
    value: Any?,
): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJson(name, it) })
        else -> error("Unsupported argument type for '$name': ${value::class.simpleName}")
    }
