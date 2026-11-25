package com.mobyle.nanquim.data.settings.repositories

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.domain.settings.models.Renderer
import com.mobyle.nanquim.domain.settings.models.ThemeMode
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository

class AppSettingsRepositoryImpl(
    private val preferences: SharedPreferences,
) : AppSettingsRepository {
    override var themeMode: ThemeMode by mutableStateOf(ThemeMode.fromId(preferences.getString(KEY_THEME, null)) ?: ThemeMode.System)
        private set

    override fun changeThemeMode(mode: ThemeMode) {
        themeMode = mode
        preferences.edit { putString(KEY_THEME, mode.id) }
    }

    override val colors: ColorState
        get() = preferences.getString(KEY_COLORS, null)?.let(ColorState::fromJson) ?: ColorState()

    override fun saveColors(state: ColorState) {
        preferences.edit { putString(KEY_COLORS, state.toJson()) }
    }

    override var renderer: Renderer by mutableStateOf(Renderer.fromId(preferences.getString(KEY_RENDERER, null)) ?: Renderer.Gles)
        private set

    override var activeRenderer: Renderer? by mutableStateOf(null)

    override var startedRenderer: Renderer? = null

    override var projectedCanvas: Boolean by mutableStateOf(preferences.getBoolean(KEY_PROJECTED_CANVAS, true))
        private set

    override fun changeProjectedCanvas(enabled: Boolean) {
        projectedCanvas = enabled
        preferences.edit { putBoolean(KEY_PROJECTED_CANVAS, enabled) }
    }

    override var recordTimelapse: Boolean by mutableStateOf(preferences.getBoolean(KEY_RECORD_TIMELAPSE, true))
        private set

    override fun changeRecordTimelapse(enabled: Boolean) {
        recordTimelapse = enabled
        preferences.edit { putBoolean(KEY_RECORD_TIMELAPSE, enabled) }
    }

    override var usageStatistics: Boolean by mutableStateOf(preferences.getBoolean(KEY_USAGE_STATISTICS, true))
        private set

    override fun changeUsageStatistics(enabled: Boolean) {
        usageStatistics = enabled
        preferences.edit { putBoolean(KEY_USAGE_STATISTICS, enabled) }
    }

    override var confirmExit: Boolean by mutableStateOf(preferences.getBoolean(KEY_CONFIRM_EXIT, true))
        private set

    override fun changeConfirmExit(enabled: Boolean) {
        confirmExit = enabled
        preferences.edit { putBoolean(KEY_CONFIRM_EXIT, enabled) }
    }

    override var galleryAds: Boolean by mutableStateOf(preferences.getBoolean(KEY_GALLERY_ADS, true))
        private set

    override val galleryAdsThisLaunch: Boolean = galleryAds

    override fun changeGalleryAds(enabled: Boolean) {
        galleryAds = enabled
        preferences.edit { putBoolean(KEY_GALLERY_ADS, enabled) }
    }

    override var adsNoticeSeen: Boolean by mutableStateOf(preferences.getBoolean(KEY_ADS_NOTICE_SEEN, false))
        private set

    override fun markAdsNoticeSeen() {
        adsNoticeSeen = true
        preferences.edit { putBoolean(KEY_ADS_NOTICE_SEEN, true) }
    }

    override fun changeRenderer(renderer: Renderer) {
        this.renderer = renderer
        preferences.edit { putString(KEY_RENDERER, renderer.id) }
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
    }
}
