package com.mobyle.nanquim.command

import androidx.work.WorkManager

import android.content.ContentResolver
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.brush.library.BrushGroup
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.diagnostics.DiagnosticsExporter
import com.mobyle.nanquim.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.TransferReport
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.macro.Macros
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.QuickMenuSettings
import com.mobyle.nanquim.settings.StorageCleaner
import com.mobyle.nanquim.settings.ThemeMode
import com.mobyle.nanquim.text.FontLibrary
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.Renderer
import com.mobyle.nanquim.formats.interchange.ImportWarning
import java.io.File
import com.mobyle.nanquim.canvas.adjust.GradientLibrary
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.PaletteLibrary

object AppCommandIds {
    const val SETTINGS_OPEN = "settings.open"
    const val DIAGNOSTICS_OPEN = "settings.diagnostics.open"
    const val DIAGNOSTICS_LATENCY_TEST = "settings.diagnostics.latencyTest"
    const val DIAGNOSTICS_EXPORT = "settings.diagnostics.export"
    const val SHAPE_TRACES_OPEN = "settings.diagnostics.shapeTraces"
    const val SHAPE_TRACES_EXPORT = "settings.diagnostics.shapeTraces.export"
    const val SHAPE_TRACES_REMOVE_LAST = "settings.diagnostics.shapeTraces.removeLast"
    const val SHAPE_TRACES_CLEAR = "settings.diagnostics.shapeTraces.clear"
    const val TOUCH_SESSIONS_OPEN = "settings.diagnostics.touchSessions"
    const val TOUCH_SESSIONS_EXPORT = "settings.diagnostics.touchSessions.export"
    const val TOUCH_SESSIONS_REMOVE_LAST = "settings.diagnostics.touchSessions.removeLast"
    const val TOUCH_SESSIONS_CLEAR = "settings.diagnostics.touchSessions.clear"
    const val SETTINGS_THEME = "settings.theme"
    const val SETTINGS_RENDERER = "settings.diagnostics.renderer.set"
    const val SETTINGS_CLEAR_CACHE = "settings.clearCache"
    const val ABOUT_OPEN = "settings.about.open"
    const val SUPPORT_OPEN = "settings.support.open"
    const val SETTINGS_PROJECTED_CANVAS = "settings.projectedCanvas.set"
}

class NanquimAppState(
    private val canvasSession: CanvasSession,
) : AppState {
    override val hasOpenDocument: Boolean get() = canvasSession.document != null
}

fun createAppCommandRegistry(
    navigator: AppNavigator,
    canvasSession: CanvasSession,
    gallery: Gallery,
    transfer: ArtworkTransfer,
    cacheDirectory: File,
    shareSheet: ShareSheet,
    appSettings: AppSettings,
    storageCleaner: StorageCleaner,
    memoryBudgetBytes: () -> Long,
    brushUi: BrushUiState,
    quickMenu: QuickMenuSettings,
    keyboardShortcuts: KeyboardShortcutSettings,
    macros: Macros,
    gradients: GradientLibrary,
    palettes: PaletteLibrary,
    colorProfiles: ColorProfileLibrary,
    fonts: FontLibrary,
    contentResolver: ContentResolver,
    diagnosticsExporter: DiagnosticsExporter,
    shapeTraces: ShapeTraceRecorder,
    touchSessions: TouchSessionRecorder,
    brushGroupName: (BrushGroup) -> String,
    stampBrushName: () -> String,
    clipboardImage: () -> Uri? = { null },
    workManager: WorkManager? = null,
): CommandRegistry {
    val registry = CommandRegistry()
    canvasSession.closeBrushLibrary = {
        brushUi.libraryTool = null
        brushUi.previewBrushId = null
    }
    registerCanvasCommands(registry, canvasSession, developerTools = BuildConfig.DEVELOPER_TOOLS)
    registerDocumentCommands(registry, canvasSession)
    registerSelectionCommands(registry, canvasSession)
    registerTransformCommands(registry, canvasSession)
    registerAdjustmentCommands(registry, canvasSession)
    registerGradientCommands(registry, canvasSession, gradients, contentResolver, shareSheet, cacheDirectory)
    registerCanvasSizeCommands(registry, canvasSession)
    registerColorProfileCommands(registry, colorProfiles, canvasSession, contentResolver)
    registerGuideCommands(registry, canvasSession)
    registerQuickShapeCommands(registry, canvasSession, brushUi.inputSettings)
    registerGestureCommands(registry, brushUi.inputSettings)
    registerQuickMenuCommands(registry, canvasSession, quickMenu)
    registerKeyboardCommands(registry, keyboardShortcuts)
    registerCanvasInfoCommands(registry, canvasSession)
    registerMacroCommands(registry, macros)
    registerReferenceCommands(registry, canvasSession, contentResolver)
    registerFontCommands(registry, fonts, contentResolver)
    registerTextCommands(registry, canvasSession)
    registerAnimationCommands(registry, canvasSession)
    registerVideoCommands(registry, canvasSession, contentResolver)
    registerTimelapseCommands(registry, canvasSession, appSettings, workManager, cacheDirectory, contentResolver, shareSheet)
    registerToolCommands(registry, canvasSession)
    val brushImportReport: (List<ImportWarning>) -> Unit = { warnings -> transfer.report = TransferReport(export = false, warnings) }
    registerPaletteCommands(
        registry,
        palettes,
        contentResolver,
        shareSheet,
        cacheDirectory,
        onImportReport = brushImportReport,
        cmykProfile = { canvasSession.cmykProfile?.icc ?: canvasSession.colorProfiles.bytes(ColorProfileLibrary.NANQUIM_CMYK_ID) },
    )
    registerBrushCommands(
        registry = registry,
        session = canvasSession,
        ui = brushUi,
        navigator = navigator,
        contentResolver = contentResolver,
        shareSheet = shareSheet,
        cacheDirectory = cacheDirectory,
        groupName = brushGroupName,
        onBrushImportReport = brushImportReport,
        stampName = stampBrushName,
        clipboardImage = clipboardImage,
    )
    registerGalleryCommands(
        registry = registry,
        gallery = gallery,
        canvasSession = canvasSession,
        navigator = navigator,
        memoryBudgetBytes = memoryBudgetBytes,
        copyName = { name -> gallery.copyName(name) },
        stackName = { gallery.stackName() },
    )
    registerTransferCommands(registry, transfer, gallery, canvasSession, contentResolver, shareSheet, cacheDirectory, brushUi, brushImportReport)

    fun registerNavigation(
        id: String,
        titleRes: Int,
        destination: AppDestination,
    ) = registry.register(CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Settings, undoable = false)) { _, _ ->
        navigator.open(destination)
        CommandResult.Success()
    }

    registerNavigation(AppCommandIds.SETTINGS_OPEN, R.string.command_settings_open, AppDestination.Settings)
    registerNavigation(AppCommandIds.DIAGNOSTICS_OPEN, R.string.command_diagnostics_open, AppDestination.Diagnostics)
    registerNavigation(AppCommandIds.DIAGNOSTICS_LATENCY_TEST, R.string.command_diagnostics_latency_test, AppDestination.LatencyTest)
    registerNavigation(AppCommandIds.SHAPE_TRACES_OPEN, R.string.command_diagnostics_shape_traces, AppDestination.ShapeTraces)
    registerNavigation(AppCommandIds.TOUCH_SESSIONS_OPEN, R.string.command_diagnostics_touch_sessions, AppDestination.TouchSessions)
    registerNavigation(AppCommandIds.ABOUT_OPEN, R.string.command_settings_about, AppDestination.About)
    registerNavigation(AppCommandIds.SUPPORT_OPEN, R.string.command_settings_support, AppDestination.Support)
    registerNavigation(GestureCommandIds.OPEN, R.string.command_settings_gestures_open, AppDestination.GestureSettings)
    registerNavigation(KeyboardCommandIds.OPEN, R.string.command_keyboard_shortcuts_open, AppDestination.KeyboardShortcuts)

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_THEME,
            titleRes = R.string.command_settings_theme,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("mode", ParameterType.Enumeration, allowedValues = ThemeMode.entries.map { it.id })),
            undoable = false,
        ),
    ) { _, arguments ->
        appSettings.changeThemeMode(ThemeMode.fromId(arguments.getValue("mode").jsonPrimitive.content) ?: ThemeMode.System)
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_PROJECTED_CANVAS,
            titleRes = R.string.command_settings_projected_canvas,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        appSettings.changeProjectedCanvas(arguments.getValue("enabled").jsonPrimitive.boolean)
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_RENDERER,
            titleRes = R.string.command_settings_renderer,
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec("renderer", ParameterType.Enumeration, allowedValues = Renderer.entries.map(AppSettings::rendererId)),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        val renderer = AppSettings.rendererFromId(arguments.getValue("renderer").jsonPrimitive.content) ?: Renderer.Gles
        appSettings.changeRenderer(renderer)
        CommandResult.Success(
            buildJsonObject {
                put("renderer", AppSettings.rendererId(renderer))
                put("restartRequired", appSettings.startedRenderer != renderer)
            },
        )
    }

    registry.register(
        CommandSpec(id = AppCommandIds.SETTINGS_CLEAR_CACHE, titleRes = R.string.command_settings_clear_cache, category = CommandCategory.Settings, undoable = false),
    ) { _, _ ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storageCleaner.clearCaches() }
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.DIAGNOSTICS_EXPORT,
            titleRes = R.string.command_diagnostics_export,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != "content") {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            diagnosticsExporter.export(uri)
            CommandResult.Success()
        }
    }

    registerShapeTraceCommands(registry, shapeTraces, contentResolver)
    registerTouchSessionCommands(registry, touchSessions, contentResolver)

    return registry
}

private fun registerShapeTraceCommands(
    registry: CommandRegistry,
    shapeTraces: ShapeTraceRecorder,
    contentResolver: ContentResolver,
) {
    registry.register(
        CommandSpec(
            id = AppCommandIds.SHAPE_TRACES_EXPORT,
            titleRes = R.string.command_diagnostics_shape_traces_export,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != "content") {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            shapeTraces.load()
            shapeTraces.export(contentResolver, uri)
            CommandResult.Success(buildJsonObject { put("count", shapeTraces.traces.size) })
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.SHAPE_TRACES_REMOVE_LAST,
            titleRes = R.string.command_diagnostics_shape_traces_remove_last,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        shapeTraces.load()
        if (shapeTraces.removeLast()) {
            CommandResult.Success(buildJsonObject { put("count", shapeTraces.traces.size) })
        } else {
            CommandResult.Failure(CommandErrorCode.Unavailable, "No trace recorded")
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.SHAPE_TRACES_CLEAR,
            titleRes = R.string.command_diagnostics_shape_traces_clear,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        shapeTraces.load()
        shapeTraces.clear()
        CommandResult.Success()
    }
}

private fun registerTouchSessionCommands(
    registry: CommandRegistry,
    touchSessions: TouchSessionRecorder,
    contentResolver: ContentResolver,
) {
    registry.register(
        CommandSpec(
            id = AppCommandIds.TOUCH_SESSIONS_EXPORT,
            titleRes = R.string.command_diagnostics_touch_sessions_export,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != "content") {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            touchSessions.load()
            touchSessions.export(contentResolver, uri)
            CommandResult.Success(buildJsonObject { put("count", touchSessions.sessions.size) })
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.TOUCH_SESSIONS_REMOVE_LAST,
            titleRes = R.string.command_diagnostics_touch_sessions_remove_last,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        touchSessions.load()
        if (touchSessions.removeLast()) {
            CommandResult.Success(buildJsonObject { put("count", touchSessions.sessions.size) })
        } else {
            CommandResult.Failure(CommandErrorCode.Unavailable, "No session recorded")
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.TOUCH_SESSIONS_CLEAR,
            titleRes = R.string.command_diagnostics_touch_sessions_clear,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        touchSessions.load()
        touchSessions.clear()
        CommandResult.Success()
    }
}
