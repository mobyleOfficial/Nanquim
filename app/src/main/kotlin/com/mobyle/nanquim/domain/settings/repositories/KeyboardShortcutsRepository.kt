package com.mobyle.nanquim.domain.settings.repositories

import com.mobyle.nanquim.domain.keyboard.KeyboardShortcuts

/** The hardware keyboard shortcuts. Reads are snapshot state. */
interface KeyboardShortcutsRepository {
    val shortcuts: KeyboardShortcuts

    fun update(updated: KeyboardShortcuts)

    /** Back to [KeyboardShortcuts.DEFAULTS]. */
    fun reset()
}
