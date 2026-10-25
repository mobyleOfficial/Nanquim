package com.mobyle.nanquim.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

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

class QuickMenuSettings(
    private val preferences: SharedPreferences,
    private val defaultName: String,
) {
    var presets: List<QuickMenuPreset> by mutableStateOf(load())
        private set

    var activeIndex: Int by mutableIntStateOf(preferences.getInt(KEY_ACTIVE, 0).coerceIn(presets.indices))
        private set

    val active: QuickMenuPreset get() = presets[activeIndex]

    fun setSlot(
        presetIndex: Int,
        slotIndex: Int,
        slot: QuickMenuSlot?,
    ) = save(presets.toMutableList().also { it[presetIndex] = it[presetIndex].withSlot(slotIndex, slot) })

    fun addPreset(name: String): Int {
        save(presets + QuickMenuPreset(name, List(QuickMenuPreset.SLOTS) { null }))
        select(presets.lastIndex)
        return activeIndex
    }

    fun renamePreset(
        index: Int,
        name: String,
    ) = save(presets.toMutableList().also { it[index] = it[index].copy(name = name) })

    fun deletePreset(index: Int): Boolean {
        if (presets.size == 1) return false
        val shown = activeIndex
        save(presets.toMutableList().also { it.removeAt(index) })
        select(if (shown > index) shown - 1 else shown)
        return true
    }

    fun select(index: Int) {
        activeIndex = index.coerceIn(presets.indices)
        preferences.edit { putInt(KEY_ACTIVE, activeIndex) }
    }

    private fun save(updated: List<QuickMenuPreset>) {
        presets = updated
        preferences.edit { putString(KEY_PRESETS, encode(updated)) }
    }

    private fun load(): List<QuickMenuPreset> =
        preferences.getString(KEY_PRESETS, null)?.let(::decode)?.takeIf { it.isNotEmpty() } ?: listOf(QuickMenuPreset.defaults(defaultName))

    companion object {
        private const val KEY_PRESETS = "quickMenuPresets"
        private const val KEY_ACTIVE = "quickMenuActive"

        fun encode(presets: List<QuickMenuPreset>): String = JsonArray(presets.map { it.toJson() }).toString()

        fun decode(text: String): List<QuickMenuPreset>? =
            runCatching { Json.parseToJsonElement(text) as? JsonArray }
                .getOrNull()
                ?.mapNotNull { (it as? JsonObject)?.let(QuickMenuPreset::fromJson) }
    }
}
