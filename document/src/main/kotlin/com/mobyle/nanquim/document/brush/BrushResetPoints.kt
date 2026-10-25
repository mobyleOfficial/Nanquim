package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class BrushResetPoint(
    val name: String,
    val createdAt: String,
)

object BrushResetPoints {
    private const val FIELD = "resetPoints"

    fun list(document: BrushDocument): List<BrushResetPoint> =
        entries(document).map { entry ->
            BrushResetPoint(
                entry["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                entry["createdAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    fun create(
        document: BrushDocument,
        name: String,
        createdAt: String,
    ): BrushDocument {
        val point =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(name),
                    "createdAt" to JsonPrimitive(createdAt),
                    "snapshot" to JsonObject(document.json - FIELD),
                ),
            )
        return document.with(FIELD, JsonArray(entries(document) + point))
    }

    fun restore(
        document: BrushDocument,
        index: Int,
    ): BrushDocument? {
        val points = entries(document)
        val snapshot = points.getOrNull(index)?.get("snapshot") as? JsonObject ?: return null
        val restored = JsonObject(snapshot + (FIELD to JsonArray(points)) + ("id" to JsonPrimitive(document.id)))
        return try {
            BrushDocument.from(restored)
        } catch (exception: InvalidBrushException) {
            null
        }
    }

    fun restoreLatest(document: BrushDocument): BrushDocument? = restore(document, entries(document).size - 1)

    private fun entries(document: BrushDocument): List<JsonObject> =
        (document[FIELD] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
}
