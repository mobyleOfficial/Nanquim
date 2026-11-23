package com.mobyle.nanquim.domain.settings.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class QuickMenuSlot(
    val commandId: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val label: String? = null,
) {
    fun toJson(): JsonObject =
        JsonObject(
            buildMap {
                put("command", JsonPrimitive(commandId))
                put("arguments", arguments)
                label?.let { put("label", JsonPrimitive(it)) }
            },
        )

    companion object {
        fun fromJson(json: JsonObject): QuickMenuSlot? {
            val commandId = (json["command"] as? JsonPrimitive)?.contentOrNull ?: return null
            return QuickMenuSlot(
                commandId,
                json["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                (json["label"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

data class QuickMenuPreset(
    val name: String,
    val slots: List<QuickMenuSlot?>,
) {
    init {
        require(slots.size == SLOTS) { "A quick menu has $SLOTS slots" }
    }

    fun withSlot(
        index: Int,
        slot: QuickMenuSlot?,
    ): QuickMenuPreset = copy(slots = slots.toMutableList().also { it[index] = slot })

    fun toJson(): JsonObject =
        JsonObject(mapOf("name" to JsonPrimitive(name), "slots" to JsonArray(slots.map { it?.toJson() ?: JsonNull })))

    companion object {
        const val SLOTS = 6

        fun fromJson(json: JsonObject): QuickMenuPreset? {
            val name = (json["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            val stored = json["slots"] as? JsonArray ?: return null
            val slots = List(SLOTS) { index -> (stored.getOrNull(index) as? JsonObject)?.let(QuickMenuSlot::fromJson) }
            return QuickMenuPreset(name, slots)
        }

        fun defaults(name: String): QuickMenuPreset =
            QuickMenuPreset(
                name,
                listOf(
                    QuickMenuSlot("layer.new"),
                    QuickMenuSlot("canvas.flipHorizontal"),
                    QuickMenuSlot("layer.clear"),
                    QuickMenuSlot("layer.mergeDown"),
                    QuickMenuSlot("color.eyedropper.arm"),
                    QuickMenuSlot("tool.select", JsonObject(mapOf("tool" to JsonPrimitive("transform")))),
                ),
            )
    }
}
