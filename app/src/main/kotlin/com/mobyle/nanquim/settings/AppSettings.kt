package com.mobyle.nanquim.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.engine.Renderer

enum class ThemeMode(
    val id: String,
) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}

class AppSettings(
    private val preferences: SharedPreferences,
) {
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.fromId(preferences.getString(KEY_THEME, null)) ?: ThemeMode.System)
        private set

    fun changeThemeMode(mode: ThemeMode) {
        themeMode = mode
        preferences.edit { putString(KEY_THEME, mode.id) }
    }

    val colors: ColorState
        get() = preferences.getString(KEY_COLORS, null)?.let(ColorState::fromJson) ?: ColorState()

    fun saveColors(state: ColorState) {
        preferences.edit { putString(KEY_COLORS, state.toJson()) }
    }

    var renderer: Renderer by mutableStateOf(rendererFromId(preferences.getString(KEY_RENDERER, null)) ?: Renderer.Gles)
        private set

    var activeRenderer: Renderer? by mutableStateOf(null)

    var startedRenderer: Renderer? = null

    val rendererFellBack: Boolean get() = activeRenderer != null && activeRenderer != startedRenderer

    var projectedCanvas: Boolean by mutableStateOf(preferences.getBoolean(KEY_PROJECTED_CANVAS, true))
        private set

    fun changeProjectedCanvas(enabled: Boolean) {
        projectedCanvas = enabled
        preferences.edit { putBoolean(KEY_PROJECTED_CANVAS, enabled) }
    }

    var recordTimelapse: Boolean by mutableStateOf(preferences.getBoolean(KEY_RECORD_TIMELAPSE, true))
        private set

    fun changeRecordTimelapse(enabled: Boolean) {
        recordTimelapse = enabled
        preferences.edit { putBoolean(KEY_RECORD_TIMELAPSE, enabled) }
    }

    fun changeRenderer(renderer: Renderer) {
        this.renderer = renderer
        preferences.edit { putString(KEY_RENDERER, rendererId(renderer)) }
    }

    companion object {
        const val PREFERENCES_NAME = "app"
        private const val KEY_THEME = "theme"
        private const val KEY_PROJECTED_CANVAS = "projectedCanvas"
        private const val KEY_COLORS = "colors"
        private const val KEY_RENDERER = "renderer"
        private const val KEY_RECORD_TIMELAPSE = "recordTimelapse"

        fun rendererId(renderer: Renderer): String =
            when (renderer) {
                Renderer.Gles -> "gles"
                Renderer.Vulkan -> "vulkan"
            }

        fun rendererFromId(id: String?): Renderer? = Renderer.entries.firstOrNull { rendererId(it) == id }
    }
}
