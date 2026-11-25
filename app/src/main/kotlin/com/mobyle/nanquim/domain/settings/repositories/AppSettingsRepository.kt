package com.mobyle.nanquim.domain.settings.repositories

import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.domain.settings.models.Renderer
import com.mobyle.nanquim.domain.settings.models.ThemeMode

/**
 * The app-wide preferences. Reads are snapshot state: whatever reads them in composition
 * recomposes when they change.
 */
interface AppSettingsRepository {
    val themeMode: ThemeMode

    fun changeThemeMode(mode: ThemeMode)

    /** The last colors picked, read once when the canvas session is created. */
    val colors: ColorState

    fun saveColors(state: ColorState)

    /** The renderer the next launch asks for. */
    val renderer: Renderer

    fun changeRenderer(renderer: Renderer)

    /** What the engine reported it runs on; null until it does. Not saved. */
    var activeRenderer: Renderer?

    /** What this launch asked the engine for. Not saved. */
    var startedRenderer: Renderer?

    val rendererFellBack: Boolean get() = activeRenderer != null && activeRenderer != startedRenderer

    val projectedCanvas: Boolean

    fun changeProjectedCanvas(enabled: Boolean)

    val recordTimelapse: Boolean

    fun changeRecordTimelapse(enabled: Boolean)

    /** Firebase Analytics and Crashlytics; the privacy policy promises this switch. */
    val usageStatistics: Boolean

    fun changeUsageStatistics(enabled: Boolean)

    val confirmExit: Boolean

    fun changeConfirmExit(enabled: Boolean)

    /**
     * The gallery banner. A change is saved now but applies from the next launch ([galleryAdsThisLaunch]), so the banner
     * never disappears or appears halfway through a session.
     */
    val galleryAds: Boolean

    val galleryAdsThisLaunch: Boolean

    fun changeGalleryAds(enabled: Boolean)

    /** The one-time note explaining why the gallery has an ad and how to turn it off. */
    val adsNoticeSeen: Boolean

    fun markAdsNoticeSeen()
}
