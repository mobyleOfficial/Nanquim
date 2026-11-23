package com.mobyle.nanquim.domain.settings.repositories

import com.mobyle.nanquim.domain.settings.models.QuickMenuPreset
import com.mobyle.nanquim.domain.settings.models.QuickMenuSlot

/** The quick menu's presets, never empty. Reads are snapshot state. */
interface QuickMenuRepository {
    val presets: List<QuickMenuPreset>

    val activeIndex: Int

    val active: QuickMenuPreset get() = presets[activeIndex]

    fun setSlot(
        presetIndex: Int,
        slotIndex: Int,
        slot: QuickMenuSlot?,
    )

    /** Adds an empty preset, shows it and returns its index. */
    fun addPreset(name: String): Int

    fun renamePreset(
        index: Int,
        name: String,
    )

    /** False, and nothing changes, when it is the last preset. */
    fun deletePreset(index: Int): Boolean

    fun select(index: Int)
}
