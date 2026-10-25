package com.mobyle.nanquim.keyboard

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

class KeyboardShortcutSettings(
    private val preferences: SharedPreferences,
) {
    var shortcuts: KeyboardShortcuts by mutableStateOf(
        preferences.getString(KEY_SHORTCUTS, null)?.let(KeyboardShortcuts::fromJson) ?: KeyboardShortcuts.DEFAULTS,
    )
        private set

    fun update(updated: KeyboardShortcuts) {
        shortcuts = updated
        preferences.edit { putString(KEY_SHORTCUTS, updated.toJson().toString()) }
    }

    fun reset() {
        shortcuts = KeyboardShortcuts.DEFAULTS
        preferences.edit { remove(KEY_SHORTCUTS) }
    }

    companion object {
        const val PREFERENCES_NAME = "keyboard"
        private const val KEY_SHORTCUTS = "shortcuts"
    }
}
