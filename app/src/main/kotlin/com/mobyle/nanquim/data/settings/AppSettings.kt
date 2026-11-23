package com.mobyle.nanquim.data.settings

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

    /** Firebase Analytics and Crashlytics; the privacy policy promises this switch. */
    var usageStatistics: Boolean by mutableStateOf(preferences.getBoolean(KEY_USAGE_STATISTICS, true))
        private set

    fun changeUsageStatistics(enabled: Boolean) {
        usageStatistics = enabled
        preferences.edit { putBoolean(KEY_USAGE_STATISTICS, enabled) }
    }

    var confirmExit: Boolean by mutableStateOf(preferences.getBoolean(KEY_CONFIRM_EXIT, true))
        private set

    fun changeConfirmExit(enabled: Boolean) {
        confirmExit = enabled
        preferences.edit { putBoolean(KEY_CONFIRM_EXIT, enabled) }
    }

    /**
     * The gallery banner. A change is saved now but applies from the next launch ([galleryAdsThisLaunch]), so the banner
     * never disappears or appears halfway through a session.
     */
    var galleryAds: Boolean by mutableStateOf(preferences.getBoolean(KEY_GALLERY_ADS, true))
        private set

    val galleryAdsThisLaunch: Boolean = galleryAds

    fun changeGalleryAds(enabled: Boolean) {
        galleryAds = enabled
        preferences.edit { putBoolean(KEY_GALLERY_ADS, enabled) }
    }

    /** The one-time note explaining why the gallery has an ad and how to turn it off. */
    var adsNoticeSeen: Boolean by mutableStateOf(preferences.getBoolean(KEY_ADS_NOTICE_SEEN, false))
        private set

    fun markAdsNoticeSeen() {
        adsNoticeSeen = true
        preferences.edit { putBoolean(KEY_ADS_NOTICE_SEEN, true) }
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
        private const val KEY_USAGE_STATISTICS = "usageStatistics"
        private const val KEY_CONFIRM_EXIT = "confirmExit"
        private const val KEY_GALLERY_ADS = "galleryAds"
        private const val KEY_ADS_NOTICE_SEEN = "adsNoticeSeen"

        fun rendererId(renderer: Renderer): String =
            when (renderer) {
                Renderer.Gles -> "gles"
                Renderer.Vulkan -> "vulkan"
            }

        fun rendererFromId(id: String?): Renderer? = Renderer.entries.firstOrNull { rendererId(it) == id }
    }
}
