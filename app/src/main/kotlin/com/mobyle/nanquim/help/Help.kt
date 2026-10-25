package com.mobyle.nanquim.help

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.canvas.input.CanvasTool

enum class WelcomeCard {
    Navigation,
    Undo,
    Brushes,
    Layers,
    Color,
}

class Help(
    private val preferences: SharedPreferences,
) {
    var automatic: Boolean = true

    var welcomeVisible: Boolean by mutableStateOf(false)
        private set

    var welcomeCard: Int by mutableStateOf(0)

    var tip: CanvasTool? by mutableStateOf(null)
        private set

    val welcomeSeen: Boolean get() = preferences.getBoolean(KEY_WELCOME_SEEN, false)

    fun showWelcomeIfFirstLaunch() {
        if (automatic && !welcomeSeen) showWelcome()
    }

    fun showWelcome() {
        welcomeCard = 0
        welcomeVisible = true
    }

    fun finishWelcome() {
        welcomeVisible = false
        preferences.edit { putBoolean(KEY_WELCOME_SEEN, true) }
    }

    fun toolSelected(tool: CanvasTool) {
        if (tip != null && tip != tool) tip = null
        if (!automatic || tool !in TIPS || tipSeen(tool)) return
        showTip(tool)
    }

    fun showTip(tool: CanvasTool) {
        if (tool !in TIPS) return
        tip = tool
        preferences.edit { putStringSet(KEY_TIPS_SEEN, tipsSeen() + tool.name) }
    }

    fun dismissTip() {
        tip = null
    }

    fun resetTips() {
        tip = null
        preferences.edit { remove(KEY_TIPS_SEEN) }
    }

    fun tipSeen(tool: CanvasTool): Boolean = tool.name in tipsSeen()

    private fun tipsSeen(): Set<String> = preferences.getStringSet(KEY_TIPS_SEEN, null).orEmpty().toSet()

    companion object {
        const val PREFERENCES_NAME = "help"
        private const val KEY_WELCOME_SEEN = "welcomeSeen"
        private const val KEY_TIPS_SEEN = "tipsSeen"

        val TIPS =
            setOf(
                CanvasTool.Smudge,
                CanvasTool.Eraser,
                CanvasTool.Selection,
                CanvasTool.Transform,
                CanvasTool.Clone,
                CanvasTool.Crop,
                CanvasTool.Adjust,
                CanvasTool.Guide,
                CanvasTool.Text,
            )
    }
}
