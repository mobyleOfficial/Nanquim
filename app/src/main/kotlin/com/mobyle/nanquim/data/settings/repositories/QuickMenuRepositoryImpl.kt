package com.mobyle.nanquim.data.settings.repositories

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.domain.settings.models.QuickMenuPreset
import com.mobyle.nanquim.domain.settings.models.QuickMenuSlot
import com.mobyle.nanquim.domain.settings.repositories.QuickMenuRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

class QuickMenuRepositoryImpl(
    private val preferences: SharedPreferences,
    private val defaultName: String,
) : QuickMenuRepository {
    override var presets: List<QuickMenuPreset> by mutableStateOf(load())
        private set

    override var activeIndex: Int by mutableIntStateOf(preferences.getInt(KEY_ACTIVE, 0).coerceIn(presets.indices))
        private set

    override fun setSlot(
        presetIndex: Int,
        slotIndex: Int,
        slot: QuickMenuSlot?,
    ) = save(presets.toMutableList().also { it[presetIndex] = it[presetIndex].withSlot(slotIndex, slot) })

    override fun addPreset(name: String): Int {
        save(presets + QuickMenuPreset(name, List(QuickMenuPreset.SLOTS) { null }))
        select(presets.lastIndex)
        return activeIndex
    }

    override fun renamePreset(
        index: Int,
        name: String,
    ) = save(presets.toMutableList().also { it[index] = it[index].copy(name = name) })

    override fun deletePreset(index: Int): Boolean {
        if (presets.size == 1) return false
        val shown = activeIndex
        save(presets.toMutableList().also { it.removeAt(index) })
        select(if (shown > index) shown - 1 else shown)
        return true
    }

    override fun select(index: Int) {
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
