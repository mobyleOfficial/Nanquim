package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.core.navigation.AppDestination
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.data.adjust.GradientLibrary
import com.mobyle.nanquim.data.brush.library.BrushGroup
import com.mobyle.nanquim.data.color.ColorProfileLibrary
import com.mobyle.nanquim.data.color.PaletteLibrary
import com.mobyle.nanquim.data.diagnostics.DiagnosticsExporter
import com.mobyle.nanquim.data.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.data.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.data.gallery.Gallery
import com.mobyle.nanquim.data.macro.Macros
import com.mobyle.nanquim.data.text.FontLibrary
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.data.transfer.TransferReport
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.command.TextKey
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.platform.ExternalFiles
import com.mobyle.nanquim.domain.platform.ShareSheet
import com.mobyle.nanquim.domain.platform.TimelapseExporter
import com.mobyle.nanquim.domain.settings.models.Renderer
import com.mobyle.nanquim.domain.settings.models.ThemeMode
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.KeyboardShortcutsRepository
import com.mobyle.nanquim.domain.settings.repositories.QuickMenuRepository
import com.mobyle.nanquim.domain.settings.repositories.StorageRepository
import com.mobyle.nanquim.formats.interchange.ImportWarning
import com.mobyle.nanquim.presentation.brush.BrushUiState
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

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
    const val SETTINGS_CONFIRM_EXIT = "settings.confirmExit.set"
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
    appSettings: AppSettingsRepository,
    storageCleaner: StorageRepository,
    memoryBudgetBytes: () -> Long,
    brushUi: BrushUiState,
    quickMenu: QuickMenuRepository,
    keyboardShortcuts: KeyboardShortcutsRepository,
    macros: Macros,
    gradients: GradientLibrary,
    palettes: PaletteLibrary,
    colorProfiles: ColorProfileLibrary,
    fonts: FontLibrary,
    files: ExternalFiles,
    diagnosticsExporter: DiagnosticsExporter,
    shapeTraces: ShapeTraceRecorder,
    touchSessions: TouchSessionRecorder,
    brushGroupName: (BrushGroup) -> String,
    stampBrushName: () -> String,
    clipboardImage: () -> String? = { null },
    timelapseExporter: TimelapseExporter? = null,
    developerTools: Boolean = false,
): CommandRegistry {
    val registry = CommandRegistry()
    canvasSession.closeBrushLibrary = {
        brushUi.libraryTool = null
        brushUi.previewBrushId = null
    }
    registerCanvasCommands(registry, canvasSession, developerTools = developerTools)
    registerDocumentCommands(registry, canvasSession)
    registerSelectionCommands(registry, canvasSession)
    registerTransformCommands(registry, canvasSession)
    registerAdjustmentCommands(registry, canvasSession)
    registerGradientCommands(registry, canvasSession, gradients, files, shareSheet, cacheDirectory)
    registerCanvasSizeCommands(registry, canvasSession)
    registerColorProfileCommands(registry, colorProfiles, canvasSession, files)
    registerGuideCommands(registry, canvasSession)
    registerQuickShapeCommands(registry, canvasSession, brushUi.inputSettings)
    registerGestureCommands(registry, brushUi.inputSettings)
    registerQuickMenuCommands(registry, canvasSession, quickMenu)
    registerKeyboardCommands(registry, keyboardShortcuts)
    registerCanvasInfoCommands(registry, canvasSession)
    registerMacroCommands(registry, macros)
    registerReferenceCommands(registry, canvasSession, files)
    registerFontCommands(registry, fonts, files)
    registerTextCommands(registry, canvasSession)
    registerAnimationCommands(registry, canvasSession)
    registerVideoCommands(registry, canvasSession, files)
    registerTimelapseCommands(registry, canvasSession, appSettings, timelapseExporter, cacheDirectory, files, shareSheet)
    registerToolCommands(registry, canvasSession)
    val brushImportReport: (List<ImportWarning>) -> Unit = { warnings -> transfer.report = TransferReport(export = false, warnings) }
    registerPaletteCommands(
        registry,
        palettes,
        files,
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
        files = files,
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
    registerTransferCommands(
        registry,
        transfer,
        gallery,
        canvasSession,
        files,
        shareSheet,
        cacheDirectory,
        brushUi,
        brushImportReport,
        developerTools,
    )

    fun registerNavigation(
        id: String,
        titleKey: TextKey,
        destination: AppDestination,
    ) = registry.register(CommandSpec(id = id, titleKey = titleKey, category = CommandCategory.Settings, undoable = false)) { _, _ ->
        navigator.open(destination)
        CommandResult.Success()
    }

    registerNavigation(AppCommandIds.SETTINGS_OPEN, TextKey("command_settings_open"), AppDestination.Settings)
    registerNavigation(AppCommandIds.DIAGNOSTICS_OPEN, TextKey("command_diagnostics_open"), AppDestination.Diagnostics)
    registerNavigation(AppCommandIds.DIAGNOSTICS_LATENCY_TEST, TextKey("command_diagnostics_latency_test"), AppDestination.LatencyTest)
    registerNavigation(AppCommandIds.SHAPE_TRACES_OPEN, TextKey("command_diagnostics_shape_traces"), AppDestination.ShapeTraces)
    registerNavigation(AppCommandIds.TOUCH_SESSIONS_OPEN, TextKey("command_diagnostics_touch_sessions"), AppDestination.TouchSessions)
    registerNavigation(AppCommandIds.ABOUT_OPEN, TextKey("command_settings_about"), AppDestination.About)
    registerNavigation(AppCommandIds.SUPPORT_OPEN, TextKey("command_settings_support"), AppDestination.Support)
    registerNavigation(GestureCommandIds.OPEN, TextKey("command_settings_gestures_open"), AppDestination.GestureSettings)
    registerNavigation(KeyboardCommandIds.OPEN, TextKey("command_keyboard_shortcuts_open"), AppDestination.KeyboardShortcuts)

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_THEME,
            titleKey = TextKey("command_settings_theme"),
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
            titleKey = TextKey("command_settings_projected_canvas"),
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
            id = AppCommandIds.SETTINGS_CONFIRM_EXIT,
            titleKey = TextKey("command_settings_confirm_exit"),
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        appSettings.changeConfirmExit(arguments.getValue("enabled").jsonPrimitive.boolean)
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_RENDERER,
            titleKey = TextKey("command_settings_renderer"),
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec("renderer", ParameterType.Enumeration, allowedValues = Renderer.entries.map(Renderer::id)),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        val renderer = Renderer.fromId(arguments.getValue("renderer").jsonPrimitive.content) ?: Renderer.Gles
        appSettings.changeRenderer(renderer)
        CommandResult.Success(
            buildJsonObject {
                put("renderer", renderer.id)
                put("restartRequired", appSettings.startedRenderer != renderer)
            },
        )
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.SETTINGS_CLEAR_CACHE,
            titleKey = TextKey("command_settings_clear_cache"),
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { storageCleaner.clearCaches() }
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = AppCommandIds.DIAGNOSTICS_EXPORT,
            titleKey = TextKey("command_diagnostics_export"),
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
        if (!files.accepts(uri)) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            diagnosticsExporter.export(uri)
            CommandResult.Success()
        }
    }

    registerShapeTraceCommands(registry, shapeTraces, files)
    registerTouchSessionCommands(registry, touchSessions, files)

    return registry
}

private fun registerShapeTraceCommands(
    registry: CommandRegistry,
    shapeTraces: ShapeTraceRecorder,
    files: ExternalFiles,
) {
    registry.register(
        CommandSpec(
            id = AppCommandIds.SHAPE_TRACES_EXPORT,
            titleKey = TextKey("command_diagnostics_shape_traces_export"),
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
        if (!files.accepts(uri)) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            shapeTraces.load()
            shapeTraces.export(files, uri)
            CommandResult.Success(buildJsonObject { put("count", shapeTraces.traces.size) })
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.SHAPE_TRACES_REMOVE_LAST,
            titleKey = TextKey("command_diagnostics_shape_traces_remove_last"),
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
            titleKey = TextKey("command_diagnostics_shape_traces_clear"),
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
    files: ExternalFiles,
) {
    registry.register(
        CommandSpec(
            id = AppCommandIds.TOUCH_SESSIONS_EXPORT,
            titleKey = TextKey("command_diagnostics_touch_sessions_export"),
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("uri", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
        if (!files.accepts(uri)) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        } else {
            touchSessions.load()
            touchSessions.export(files, uri)
            CommandResult.Success(buildJsonObject { put("count", touchSessions.sessions.size) })
        }
    }
    registry.register(
        CommandSpec(
            id = AppCommandIds.TOUCH_SESSIONS_REMOVE_LAST,
            titleKey = TextKey("command_diagnostics_touch_sessions_remove_last"),
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
            titleKey = TextKey("command_diagnostics_touch_sessions_clear"),
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        touchSessions.load()
        touchSessions.clear()
        CommandResult.Success()
    }
}
