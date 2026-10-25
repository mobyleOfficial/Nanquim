package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

class BrushDocument private constructor(
    val json: JsonObject,
) {
    val id: String get() = string("id").orEmpty()
    val name: String get() = string("name").orEmpty()
    val author: String? get() = string("author")
    val license: String? get() = string("license")
    val createdAt: String? get() = string("createdAt")
    val modifiedAt: String? get() = string("modifiedAt")

    val shapeTexture: String?
        get() = if (has("shape.texture")) string("shape.texture") else DEFAULT_SHAPE_TEXTURE

    val grainTexture: String? get() = string("grain.texture")

    operator fun get(path: String): JsonElement? {
        var current: JsonElement = json
        for (segment in segments(path)) {
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    fun has(path: String): Boolean = get(path) != null

    fun string(path: String): String? = (get(path) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun number(path: String): Double? = (get(path) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

    fun integer(path: String): Int? = (get(path) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

    fun boolean(path: String): Boolean? = (get(path) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    fun with(
        path: String,
        value: JsonElement,
    ): BrushDocument = BrushDocument(replaced(json, segments(path), value))

    fun with(
        path: String,
        value: Double,
    ): BrushDocument = with(path, JsonPrimitive(value))

    fun with(
        path: String,
        value: Boolean,
    ): BrushDocument = with(path, JsonPrimitive(value))

    fun with(
        path: String,
        value: String?,
    ): BrushDocument = with(path, value?.let(::JsonPrimitive) ?: JsonNull)

    fun without(path: String): BrushDocument {
        val parts = segments(path)
        return BrushDocument(removed(json, parts) ?: return this)
    }

    fun toJsonText(): String = PRETTY_JSON.encodeToString(JsonObject.serializer(), json)

    override fun equals(other: Any?): Boolean = other is BrushDocument && other.json == json

    override fun hashCode(): Int = json.hashCode()

    override fun toString(): String = "BrushDocument($id, $name)"

    companion object {
        const val FORMAT = 1
        const val DEFAULT_SHAPE_TEXTURE = "shape.png"

        private val PRETTY_JSON =
            Json {
                prettyPrint = true
                prettyPrintIndent = "  "
            }

        fun parse(text: String): BrushDocument {
            val element =
                try {
                    Json.parseToJsonElement(text)
                } catch (exception: IllegalArgumentException) {
                    throw InvalidBrushException("brush.json is not valid JSON: ${exception.message}", exception)
                }
            val root = element as? JsonObject ?: throw InvalidBrushException("brush.json must be a JSON object")
            return from(root)
        }

        fun from(json: JsonObject): BrushDocument {
            val document = BrushDocument(json)
            if (document.integer("format") != FORMAT) throw InvalidBrushException("unsupported brush format")
            if (document.id.isBlank()) throw InvalidBrushException("a brush needs an id")
            if (document.name.isBlank()) throw InvalidBrushException("a brush needs a name")
            return document
        }

        private fun segments(path: String): List<String> {
            val parts = path.split('.')
            require(parts.isNotEmpty() && parts.none { it.isEmpty() }) { "Invalid field path: $path" }
            return parts
        }

        private fun replaced(
            node: JsonObject,
            parts: List<String>,
            value: JsonElement,
        ): JsonObject {
            val head = parts.first()
            if (parts.size == 1) return JsonObject(node + (head to value))
            val child = node[head] as? JsonObject ?: JsonObject(emptyMap())
            return JsonObject(node + (head to replaced(child, parts.drop(1), value)))
        }

        private fun removed(
            node: JsonObject,
            parts: List<String>,
        ): JsonObject? {
            val head = parts.first()
            if (head !in node) return null
            if (parts.size == 1) return JsonObject(node - head)
            val child = node[head] as? JsonObject ?: return null
            return JsonObject(node + (head to (removed(child, parts.drop(1)) ?: return null)))
        }
    }
}

class InvalidBrushException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
