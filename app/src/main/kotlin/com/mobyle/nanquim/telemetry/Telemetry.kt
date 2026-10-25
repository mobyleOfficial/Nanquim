package com.mobyle.nanquim.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.engine.EngineEvent

enum class ArtworkOpenSource(
    val id: String,
) {
    Gallery("gallery"),
    New("new"),
    Widget("widget"),

    Restore("restore"),
}

class Telemetry private constructor(
    private val analytics: FirebaseAnalytics?,
    private val crashlytics: FirebaseCrashlytics?,
) {
    fun screenShown(name: String) {
        crashlytics?.setCustomKey(KEY_SCREEN, name)
        analytics?.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, name) })
    }

    fun appStarted(
        sinceProcessMillis: Long,
        sinceActivityMillis: Long,
    ) = performance(EVENT_APP_START) {
        putLong(PARAM_DURATION, sinceProcessMillis)
        putLong(PARAM_ACTIVITY_DURATION, sinceActivityMillis)
    }

    fun artworkOpened(
        source: ArtworkOpenSource,
        state: DocumentState,
        durationMillis: Long,
        readMillis: Long,
    ) = performance(EVENT_ARTWORK_OPEN) {
        putString(PARAM_SOURCE, source.id)
        putLong(PARAM_DURATION, durationMillis)
        putLong(PARAM_READ_DURATION, readMillis)
        putDocument(state)
    }

    fun artworkOpenFailed(reason: String) {
        crashlytics?.log("Artwork open failed: $reason")
        analytics?.logEvent(EVENT_ARTWORK_OPEN_FAILED, Bundle().apply { putString(PARAM_REASON, reason) })
    }

    fun artworkClosed(
        state: DocumentState?,
        durationMillis: Long,
        saved: Boolean,
    ) = performance(EVENT_ARTWORK_CLOSE) {
        putLong(PARAM_DURATION, durationMillis)
        putLong(PARAM_SAVED, if (saved) 1 else 0)
        state?.let { putDocument(it) }
    }

    fun artworkSavedInBackground(
        state: DocumentState?,
        durationMillis: Long,
        saved: Boolean,
    ) = performance(EVENT_ARTWORK_BACKGROUND_SAVE) {
        putLong(PARAM_DURATION, durationMillis)
        putLong(PARAM_SAVED, if (saved) 1 else 0)
        state?.let { putDocument(it) }
    }

    fun artworkChanged(state: DocumentState?) {
        val crashlytics = crashlytics ?: return
        val description = state?.let { "${it.widthPx}x${it.heightPx} ${it.layerCount} layers ${it.pixelFormat.id}" }
        crashlytics.setCustomKey(KEY_ARTWORK, description ?: "none")
    }

    fun rendererChanged(
        started: String,
        active: String,
    ) {
        crashlytics?.setCustomKey(KEY_RENDERER, active)
        crashlytics?.setCustomKey(KEY_RENDERER_STARTED, started)
        analytics?.setUserProperty(USER_PROPERTY_RENDERER, active)
    }

    fun memoryBudgetChanged(bytes: Long) {
        crashlytics?.setCustomKey(KEY_MEMORY_BUDGET_MB, bytes / BYTES_PER_MEGABYTE)
    }

    fun engineFailed(error: EngineEvent.Error) {
        crashlytics?.recordException(EngineFailure("${error.code}: ${error.message}"))
        analytics?.logEvent(EVENT_ENGINE_FAILURE, Bundle().apply { putString(PARAM_REASON, error.code.name) })
    }

    private inline fun performance(
        event: String,
        parameters: Bundle.() -> Unit,
    ) {
        val analytics = analytics ?: return
        analytics.logEvent(event, Bundle().apply(parameters))
    }

    private fun Bundle.putDocument(state: DocumentState) {
        putLong(PARAM_WIDTH, state.widthPx.toLong())
        putLong(PARAM_HEIGHT, state.heightPx.toLong())
        putLong(PARAM_LAYERS, state.layerCount.toLong())
        putString(PARAM_PIXEL_FORMAT, state.pixelFormat.id)
    }

    class EngineFailure(
        message: String,
    ) : Exception(message)

    companion object {
        val Disabled = Telemetry(null, null)

        fun create(
            context: Context,
            analyticsEnabled: Boolean,
        ): Telemetry {
            if (FirebaseApp.getApps(context).isEmpty()) return Disabled
            if (!analyticsEnabled) {
                FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(false)
                return Disabled
            }
            return Telemetry(FirebaseAnalytics.getInstance(context), FirebaseCrashlytics.getInstance())
        }

        const val EVENT_APP_START = "app_start"
        const val EVENT_ARTWORK_OPEN = "artwork_open"
        const val EVENT_ARTWORK_OPEN_FAILED = "artwork_open_failed"
        const val EVENT_ARTWORK_CLOSE = "artwork_close"
        const val EVENT_ARTWORK_BACKGROUND_SAVE = "artwork_background_save"
        const val EVENT_ENGINE_FAILURE = "engine_failure"
        const val PARAM_DURATION = "duration_ms"
        const val PARAM_ACTIVITY_DURATION = "activity_duration_ms"
        const val PARAM_READ_DURATION = "read_ms"
        const val PARAM_SOURCE = "source"
        const val PARAM_REASON = "reason"
        const val PARAM_SAVED = "saved"
        const val PARAM_WIDTH = "width_px"
        const val PARAM_HEIGHT = "height_px"
        const val PARAM_LAYERS = "layers"
        const val PARAM_PIXEL_FORMAT = "pixel_format"
        const val USER_PROPERTY_RENDERER = "renderer"
        private const val KEY_SCREEN = "screen"
        private const val KEY_ARTWORK = "artwork"
        private const val KEY_RENDERER = "renderer"
        private const val KEY_RENDERER_STARTED = "renderer_started"
        private const val KEY_MEMORY_BUDGET_MB = "memory_budget_mb"
        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
    }
}
