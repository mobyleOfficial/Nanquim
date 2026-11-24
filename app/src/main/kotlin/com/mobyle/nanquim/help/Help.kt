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
    QuickShape,
    Animation,
    Share,
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

    /** The short tour of the canvas the first time an artwork opens; null when it is not showing. */
    var tourStep: Int? by mutableStateOf(null)
        private set

    /** Told how the introduction ended: completed (the last card's button) or skipped, and on which card. */
    var onWelcomeFinished: (completed: Boolean, card: Int) -> Unit = { _, _ -> }

    val welcomeSeen: Boolean get() = preferences.getBoolean(KEY_WELCOME_SEEN, false)

    fun showWelcomeIfFirstLaunch() {
        if (automatic && !welcomeSeen) showWelcome()
    }

    fun showWelcome() {
        welcomeCard = 0
        welcomeVisible = true
        tourStep = null
        preferences.edit { remove(KEY_TOUR_SEEN) }
    }

    fun finishWelcome(completed: Boolean = false) {
        val wasVisible = welcomeVisible
        welcomeVisible = false
        preferences.edit { putBoolean(KEY_WELCOME_SEEN, true) }
        if (wasVisible) onWelcomeFinished(completed, welcomeCard)
    }

    /** Called when the canvas shows: starts the tour once, after the introduction. */
    fun canvasShown() {
        if (!automatic || welcomeVisible || tourStep != null || preferences.getBoolean(KEY_TOUR_SEEN, false)) return
        tip = null
        tourStep = 0
    }

    fun nextTourStep() {
        val step = tourStep ?: return
        if (step + 1 < TOUR_STEPS) tourStep = step + 1 else finishTour()
    }

    fun finishTour() {
        tourStep = null
        preferences.edit { putBoolean(KEY_TOUR_SEEN, true) }
    }

    fun toolSelected(tool: CanvasTool) {
        if (tip != null && tip != tool) tip = null
        if (!automatic || tourStep != null || tool !in TIPS || tipSeen(tool)) return
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
        private const val KEY_TOUR_SEEN = "tourSeen"
        const val TOUR_STEPS = 3

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
