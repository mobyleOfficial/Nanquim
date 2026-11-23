package com.mobyle.nanquim.ui.diagnostics

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.Renderer
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        val collector: DiagnosticsCollector,
        val recorder: StylusObservationRecorder,
        private val appSettings: AppSettings,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        val selectedRenderer: Renderer get() = appSettings.renderer
        val activeRenderer: Renderer? get() = appSettings.activeRenderer
        val rendererFellBack: Boolean get() = appSettings.rendererFellBack

        fun selectRenderer(renderer: Renderer) =
            dispatcher.dispatch(AppCommandIds.SETTINGS_RENDERER, buildJsonObject { put("renderer", AppSettings.rendererId(renderer)) })

        fun export(
            uri: Uri,
            onResult: (CommandResult) -> Unit,
        ) = dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_EXPORT, buildJsonObject { put("uri", uri.toString()) }, onResult)

        fun openLatencyTest() = dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_LATENCY_TEST)

        fun openShapeTraces() = dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_OPEN)

        fun openTouchSessions() = dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_OPEN)

        fun back() = navigator.back()
    }

@HiltViewModel
class LatencyTestViewModel
    @Inject
    constructor(
        val engine: Engine,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        fun back() = navigator.back()
    }

@HiltViewModel
class ShapeTracesViewModel
    @Inject
    constructor(
        val recorder: ShapeTraceRecorder,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        fun removeLast() = dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_REMOVE_LAST)

        fun clear() = dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_CLEAR)

        fun export(
            uri: Uri,
            onResult: (CommandResult) -> Unit,
        ) = dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_EXPORT, buildJsonObject { put("uri", uri.toString()) }, onResult)

        fun back() = navigator.back()
    }

@HiltViewModel
class TouchSessionsViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        val recorder: TouchSessionRecorder,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        /** The screen's horizontal density, to turn the recorded touches into physical sizes. */
        val xdpi: Float = context.resources.displayMetrics.xdpi

        fun removeLast() = dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_REMOVE_LAST)

        fun clear() = dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_CLEAR)

        fun export(
            uri: Uri,
            onResult: (CommandResult) -> Unit,
        ) = dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_EXPORT, buildJsonObject { put("uri", uri.toString()) }, onResult)

        fun back() = navigator.back()
    }
