package com.mobyle.nanquim.data.settings.repositories

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.domain.keyboard.KeyboardShortcuts
import com.mobyle.nanquim.domain.settings.repositories.KeyboardShortcutsRepository

class KeyboardShortcutsRepositoryImpl(
    private val preferences: SharedPreferences,
) : KeyboardShortcutsRepository {
    override var shortcuts: KeyboardShortcuts by mutableStateOf(
        preferences.getString(KEY_SHORTCUTS, null)?.let(KeyboardShortcuts::fromJson) ?: KeyboardShortcuts.DEFAULTS,
    )
        private set

    override fun update(updated: KeyboardShortcuts) {
        shortcuts = updated
        preferences.edit { putString(KEY_SHORTCUTS, updated.toJson().toString()) }
    }

    override fun reset() {
        shortcuts = KeyboardShortcuts.DEFAULTS
        preferences.edit { remove(KEY_SHORTCUTS) }
    }

    companion object {
        const val PREFERENCES_NAME = "keyboard"
        private const val KEY_SHORTCUTS = "shortcuts"
    }
}
