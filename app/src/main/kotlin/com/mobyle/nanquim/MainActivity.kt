package com.mobyle.nanquim

import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.CanvasCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.GalleryCommandIds
import com.mobyle.nanquim.command.PaletteCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.command.TransferCommandIds
import com.mobyle.nanquim.command.VideoCommandIds
import com.mobyle.nanquim.display.ProjectedCanvas
import com.mobyle.nanquim.gallery.ExportOptions
import com.mobyle.nanquim.gallery.GalleryError
import com.mobyle.nanquim.gallery.ImportTarget
import com.mobyle.nanquim.gallery.InterchangeFormat
import com.mobyle.nanquim.keyboard.KeyboardInput
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.ThemeMode
import com.mobyle.nanquim.ui.brush.BrushEditorScreen
import com.mobyle.nanquim.ui.canvas.CanvasScreen
import com.mobyle.nanquim.ui.canvas.canvasSurroundColor
import com.mobyle.nanquim.ui.common.ProvideWindowLayout
import com.mobyle.nanquim.ui.diagnostics.DiagnosticsScreen
import com.mobyle.nanquim.ui.diagnostics.LatencyTestScreen
import com.mobyle.nanquim.ui.diagnostics.ShapeTraceScreen
import com.mobyle.nanquim.ui.diagnostics.TouchSessionScreen
import com.mobyle.nanquim.ui.gallery.ExportAnimation
import com.mobyle.nanquim.ui.help.ToolTipCard
import com.mobyle.nanquim.ui.help.WelcomeOverlay
import com.mobyle.nanquim.command.HelpCommandIds
import com.mobyle.nanquim.command.TimelapseCommandIds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.ui.gallery.ExportDialog
import com.mobyle.nanquim.ui.gallery.GalleryScreen
import com.mobyle.nanquim.ui.gallery.IncomingFileDialog
import com.mobyle.nanquim.ui.gallery.NewCanvasScreen
import com.mobyle.nanquim.ui.gallery.TransferReportDialog
import com.mobyle.nanquim.ui.settings.AboutScreen
import com.mobyle.nanquim.ui.settings.GestureSettingsScreen
import com.mobyle.nanquim.ui.settings.KeyboardShortcutsScreen
import com.mobyle.nanquim.ui.settings.SettingsScreen
import com.mobyle.nanquim.ui.settings.SupportScreen
import com.mobyle.nanquim.ui.theme.LocalDarkTheme
import com.mobyle.nanquim.ui.theme.NanquimTheme
import com.mobyle.nanquim.ui.transition.ArtworkCloseOverlay
import com.mobyle.nanquim.ui.transition.BlockingLoadingIndicator
import com.mobyle.nanquim.ui.transition.ArtworkClosingCover
import com.mobyle.nanquim.ui.transition.ArtworkLoadingIndicator
import com.mobyle.nanquim.ui.transition.ArtworkOpenOverlay
import com.mobyle.nanquim.document.animation.playedFrames
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.engine.EngineEvent
import com.mobyle.nanquim.formats.NanquimFileType

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Nanquim)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as NanquimApplication
        if (!application.appStartReported) {
            application.appStartReported = true
            if (savedInstanceState == null) measureAppStart(application, SystemClock.uptimeMillis())
        }
        setContent {
            val darkTheme =
                when (application.appSettings.themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            NanquimTheme(darkTheme = darkTheme) {
                ProvideWindowLayout { NanquimApp(application) }
            }
        }
        if (savedInstanceState == null && !openTestDocumentFromIntent(application, intent)) {
            lifecycleScope.launch {
                val (screenWidth, screenHeight) = displayResolution()
                application.gallery.seedPresets(screenWidth, screenHeight)
                application.gallery.sync()
                if (application.help.automatic) application.sampleArtworks.installOnce()
                val requested = takeArtworkToOpen(intent)
                if (requested != null) {
                    application.gallery.openFromOutside(requested)
                } else if (application.canvasSession.documentSession == null) {
                    application.gallery.restoreLastOpen()
                }
            }
        }
        val windowCallback = window.callback
        window.callback =
            object : Window.Callback by windowCallback {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val typing = currentFocus?.onCheckIsTextEditor() == true
                    return (onCanvas && !typing && keyboard.onKeyEvent(event)) || windowCallback.dispatchKeyEvent(event)
                }
            }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                snapshotFlow { application.navigator.current }.collect { destination ->
                    application.telemetry.screenShown(destination.name)
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { application.navigator.current == AppDestination.Canvas }.collect { immersive ->
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (immersive) {
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        application.startControlChannel(intent?.getStringExtra(EXTRA_CONTROL_TOKEN))
        if (savedInstanceState == null) application.help.showWelcomeIfFirstLaunch()
        if (savedInstanceState == null) incomingFileOf(intent)?.let { application.incomingFile = it }
    }

    private fun measureAppStart(
        application: NanquimApplication,
        activityCreatedAt: Long,
    ) {
        lifecycleScope.launch {
            val gallery = application.gallery
            val session = application.canvasSession
            snapshotFlow { gallery.loaded || session.documentShown }.first { it }
            suspendCancellableCoroutine { continuation ->
                Choreographer.getInstance().postFrameCallback { continuation.resumeWith(Result.success(Unit)) }
            }
            val now = SystemClock.uptimeMillis()
            application.telemetry.appStarted(now - Process.getStartUptimeMillis(), now - activityCreatedAt)
        }
    }

    private fun incomingFileOf(intent: Intent?): android.net.Uri? =
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            else -> null
        }?.takeIf { it.scheme == "content" || it.scheme == "file" }

    private fun displayResolution(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }

    private val keyboard by lazy {
        val application = application as NanquimApplication
        KeyboardInput(
            session = application.canvasSession,
            shortcuts = { application.keyboardShortcutSettings.shortcuts },
            dispatch = { commandId, arguments -> application.commandDispatcher.dispatch(commandId, arguments) },
        )
    }

    private val onCanvas: Boolean get() = (application as NanquimApplication).navigator.current == AppDestination.Canvas

    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val application = application as NanquimApplication
        val shortcuts = application.keyboardShortcutSettings.shortcuts
        data += KeyboardInput.shortcutGroup(shortcuts, resources, application.commandDispatcher.registry)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingFileOf(intent)?.let { (application as NanquimApplication).incomingFile = it }
        takeArtworkToOpen(intent)?.let { id -> lifecycleScope.launch { (application as NanquimApplication).gallery.openFromOutside(id) } }
    }

    private fun takeArtworkToOpen(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_OPEN_ARTWORK)?.also { intent.removeExtra(EXTRA_OPEN_ARTWORK) }

    private var projectedCanvas: ProjectedCanvas? = null

    override fun onStart() {
        super.onStart()
        val application = application as NanquimApplication
        projectedCanvas =
            ProjectedCanvas(
                activity = this,
                engine = application.engine,
                session = application.canvasSession,
                enabled = { application.appSettings.projectedCanvas },
                scope = lifecycleScope,
            ).also { it.start() }
    }

    override fun onStop() {
        projectedCanvas?.stop()
        projectedCanvas = null
        super.onStop()
        val application = application as NanquimApplication
        val session = application.canvasSession
        val state = session.artwork?.let { session.document }
        val startedAt = SystemClock.elapsedRealtime()
        session.persistence.saveNow(withThumbnail = true) { saved ->
            if (state != null) application.telemetry.artworkSavedInBackground(state, SystemClock.elapsedRealtime() - startedAt, saved)
        }
        session.timelapse.pause()
    }

    private fun openTestDocumentFromIntent(
        application: NanquimApplication,
        intent: Intent?,
    ): Boolean {
        if (!BuildConfig.DEVELOPER_TOOLS) return false
        val specification = intent?.getStringExtra(EXTRA_TEST_DOCUMENT) ?: return false
        val parts = specification.split('x').mapNotNull(String::toIntOrNull)
        if (parts.size != 3) return false
        application.navigator.showCanvas()
        val fill = intent.getIntExtra(EXTRA_TEST_FILL, CanvasSession.FILL_ALL_TILES)
        application.commandDispatcher.dispatch(
            CanvasCommandIds.DEBUG_CREATE_TEST_DOCUMENT,
            buildJsonObject {
                put("width", parts[0])
                put("height", parts[1])
                put("layers", parts[2])
                put("fillTiles", fill)
            },
        )
        return true
    }

    companion object {
        const val EXTRA_OPEN_ARTWORK = "com.mobyle.nanquim.extra.OPEN_ARTWORK"

        private const val EXTRA_TEST_DOCUMENT = "com.mobyle.nanquim.extra.TEST_DOCUMENT"

        private const val EXTRA_CONTROL_TOKEN = "com.mobyle.nanquim.extra.CONTROL_TOKEN"
        private const val EXTRA_TEST_FILL = "com.mobyle.nanquim.extra.TEST_FILL"
    }
}

@Composable
private fun NanquimApp(application: NanquimApplication) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = application.navigator
    val dispatcher = application.commandDispatcher
    val engine = application.engine
    val session = application.canvasSession

    LaunchedEffect(engine) {
        engine.events.collect { event ->
            if (event is EngineEvent.Error) {
                Toast.makeText(context, resources.getString(R.string.engine_error_notice, event.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    val brushUi = application.brushUi
    val galleryCommand: (String, Map<String, Any?>) -> Unit = { commandId, arguments ->
        dispatcher.dispatch(commandId, arguments.toJsonObject()) { result ->
            if (result is CommandResult.Failure) {
                Toast.makeText(context, galleryFailureMessage(resources, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var exportIds by remember { mutableStateOf<List<String>?>(null) }
    var exportRequestsSeen by remember { mutableIntStateOf(session.exportSheetRequests) }
    LaunchedEffect(session.exportSheetRequests) {
        if (session.exportSheetRequests != exportRequestsSeen) {
            exportRequestsSeen = session.exportSheetRequests
            if (session.artwork != null) exportIds = emptyList()
        }
    }
    var pendingSaveTo by remember { mutableStateOf<Pair<List<String>, ExportOptions>?>(null) }
    val saveToLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val request = pendingSaveTo
            pendingSaveTo = null
            if (uri != null && request != null) {
                val (ids, options) = request
                val arguments =
                    mapOf<String, Any?>("uri" to uri.toString(), "id" to ids.firstOrNull()) +
                        if (options.isPackage) emptyMap() else options.commandArguments()
                galleryCommand(if (options.isPackage) TransferCommandIds.EXPORT_DOCUMENT else TransferCommandIds.EXPORT_IMAGE, arguments)
            }
        }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val canvas = navigator.current == AppDestination.Canvas
                if (canvas && application.contentResolver.getType(uri)?.startsWith("video/") == true) {
                    galleryCommand(VideoCommandIds.IMPORT, mapOf("uri" to uri.toString()))
                } else {
                    val target = if (canvas) ImportTarget.Layer else ImportTarget.NewArtwork
                    galleryCommand(TransferCommandIds.IMPORT, mapOf("uri" to uri.toString(), "target" to target.id))
                }
            }
        }
    val photoLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri !=
                null
            ) {
                galleryCommand(TransferCommandIds.IMPORT, mapOf("uri" to uri.toString(), "target" to ImportTarget.NewArtwork.id))
            }
        }
    val importTypes =
        arrayOf(
            "image/*",
            NanquimFileType.Document.mimeType,
            "application/zip",
            "application/octet-stream",
            "application/x-photoshop",
            "application/photoshop",
        )
    exportIds?.let { ids ->
        val count = if (ids.isEmpty()) 1 else ids.size
        val cmykProfiles =
            application.colorProfileLibrary.entries
                .filter { it.info.colorSpace == com.mobyle.nanquim.engine.ProfileColorSpace.Cmyk && it.info.convertsTo }
                .map { it.id to it.name }
        val animationState = session.document?.takeIf { it.animation.enabled && (ids.isEmpty() || ids == listOfNotNull(session.artwork?.id)) }
        ExportDialog(
            artworkCount = count,
            cmykProfiles = cmykProfiles,
            documentCmyk = session.cmykProfile?.takeIf { ids.isEmpty() || ids == listOfNotNull(session.artwork?.id) }?.name,
            animation = animationState?.let { ExportAnimation(it.playedFrames.size, it.animation.framesPerSecond) },
            onShare = { options ->
                exportIds = null
                val arguments = mapOf<String, Any?>("ids" to ids.ifEmpty { null }) + options.commandArguments()
                galleryCommand(TransferCommandIds.SHARE, arguments)
            },
            onSaveTo = { options ->
                exportIds = null
                val targetIds = ids.ifEmpty { listOfNotNull(session.artwork?.id) }
                pendingSaveTo = targetIds to options
                coroutineScope.launch {
                    saveToLauncher.launch(application.transfer.fileName(targetIds.first(), options))
                }
            },
            onDismiss = { exportIds = null },
        )
    }
    application.incomingFile?.let { uri ->
        val wholeArtworkOnly =
            remember(uri) {
                val format = application.transfer.interchangeFormatOf(uri)
                application.transfer.isDocumentPackage(uri) || (format != null && format != InterchangeFormat.Tiff)
            }
        val isBrush by produceState(false, uri) { value = application.transfer.isBrushFile(uri) }
        val isPalette by produceState(false, uri) { value = application.transfer.isPaletteFile(uri) }
        IncomingFileDialog(
            name = application.transfer.displayName(uri) ?: uri.lastPathSegment.orEmpty(),
            isPackage = wholeArtworkOnly,
            isBrush = isBrush,
            isPalette = isPalette,
            canAddLayer = session.documentSession != null,
            onImport = { target ->
                application.incomingFile = null
                galleryCommand(TransferCommandIds.IMPORT, mapOf("uri" to uri.toString(), "target" to target.id))
            },
            onImportPalette = {
                application.incomingFile = null
                galleryCommand(PaletteCommandIds.IMPORT, mapOf("uri" to uri.toString()))
            },
            onDismiss = { application.incomingFile = null },
        )
    }
    application.transfer.report?.let { report ->
        TransferReportDialog(report = report, onDismiss = { application.transfer.report = null })
    }
    val panelOpen = brushUi.libraryTool != null || session.layersPanelVisible || session.colorPanelVisible
    BackHandler(enabled = navigator.canGoBack || session.debugPanelVisible || panelOpen) {
        when {
            navigator.current == AppDestination.BrushEditor -> dispatcher.dispatch(BrushCommandIds.EDITOR_CANCEL)
            panelOpen -> {
                if (brushUi.libraryTool != null) dispatcher.dispatch(BrushCommandIds.LIBRARY_CLOSE)
                if (session.layersPanelVisible) {
                    dispatcher.dispatch(DocumentCommandIds.LAYERS_PANEL, buildJsonObject { put("visible", false) })
                }
                if (session.colorPanelVisible) dispatcher.dispatch(ToolCommandIds.COLOR_PANEL_CLOSE)
            }
            session.debugPanelVisible -> session.debugPanelVisible = false
            navigator.current == AppDestination.Canvas -> galleryCommand(GalleryCommandIds.CLOSE, emptyMap())
            else -> navigator.back()
        }
    }

    AnimatedContent(
        targetState = navigator.current,
        transitionSpec = {
            if (initialState == AppDestination.Canvas || targetState == AppDestination.Canvas) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(SCREEN_FADE_IN_MILLIS, delayMillis = SCREEN_FADE_OUT_MILLIS)) togetherWith
                    fadeOut(tween(SCREEN_FADE_OUT_MILLIS))
            }
        },
        label = "screen",
    ) { destination ->
        when (destination) {
            AppDestination.Gallery ->
                GalleryScreen(
                    gallery = application.gallery,
                    onCommand = galleryCommand,
                    onImport = { importLauncher.launch(importTypes) },
                    onPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onShare = { ids -> exportIds = ids },
                    onOpenSettings = { dispatcher.dispatch(AppCommandIds.SETTINGS_OPEN) },
                )
            AppDestination.NewCanvas ->
                NewCanvasScreen(
                    gallery = application.gallery,
                    colorProfiles = application.colorProfileLibrary,
                    memoryBudgetBytes = application.memoryBudgetPolicy.maximumBytes,
                    onCommand = galleryCommand,
                    onBack = navigator::back,
                )
            AppDestination.Canvas ->
                CanvasScreen(
                    engine = engine,
                    session = session,
                    brushUi = application.brushUi,
                    quickMenu = application.quickMenuSettings,
                    macros = application.macros,
                    onOpenSettings = { dispatcher.dispatch(AppCommandIds.SETTINGS_OPEN) },
                    onToggleDebugPanel = { dispatcher.dispatch(CanvasCommandIds.DEBUG_PANEL) },
                    onOpenGallery = { galleryCommand(GalleryCommandIds.CLOSE, emptyMap()) },
                    onShare = { exportIds = emptyList() },
                    onAddImage = { importLauncher.launch(arrayOf("image/*", "video/*")) },
                    onCommand = { commandId, arguments ->
                        dispatcher.dispatch(commandId, arguments.toJsonObject()) { result ->
                            timelapseNotice(commandId, result)?.let { Toast.makeText(context, resources.getString(it), Toast.LENGTH_LONG).show() }
                        }
                    },
                )
            AppDestination.Settings ->
                SettingsScreen(
                    inputSettings = application.inputSettings,
                    appSettings = application.appSettings,
                    storage = application.storageCleaner,
                    onCommand = { commandId, arguments -> dispatcher.dispatch(commandId, arguments) },
                    onBack = navigator::back,
                    onOpenDiagnostics = { dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_OPEN) },
                )
            AppDestination.Diagnostics ->
                DiagnosticsScreen(
                    collector = application.diagnosticsCollector,
                    recorder = application.stylusObservationRecorder,
                    onBack = navigator::back,
                    onOpenLatencyTest = { dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_LATENCY_TEST) },
                    onOpenShapeTraces = { dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_OPEN) },
                    onOpenTouchSessions = { dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_OPEN) },
                    selectedRenderer = application.appSettings.renderer,
                    activeRenderer = application.appSettings.activeRenderer,
                    rendererFellBack = application.appSettings.rendererFellBack,
                    onSelectRenderer = { renderer ->
                        dispatcher.dispatch(
                            AppCommandIds.SETTINGS_RENDERER,
                            buildJsonObject { put("renderer", AppSettings.rendererId(renderer)) },
                        )
                    },
                    onExport = { uri ->
                        dispatcher.dispatch(AppCommandIds.DIAGNOSTICS_EXPORT, buildJsonObject { put("uri", uri.toString()) }) { result ->
                            val message =
                                when (result) {
                                    is CommandResult.Success -> resources.getString(R.string.diagnostics_export_done)
                                    is CommandResult.Failure -> resources.getString(R.string.diagnostics_export_failed, result.message)
                                }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            AppDestination.GestureSettings ->
                GestureSettingsScreen(
                    inputSettings = application.inputSettings,
                    onCommand = { commandId, arguments -> dispatcher.dispatch(commandId, arguments) },
                    onBack = navigator::back,
                )
            AppDestination.KeyboardShortcuts ->
                KeyboardShortcutsScreen(
                    settings = application.keyboardShortcutSettings,
                    describe = { action -> KeyboardInput.describe(action, resources, dispatcher.registry) },
                    onCommand = { commandId, arguments -> dispatcher.dispatch(commandId, arguments) },
                    onBack = navigator::back,
                )
            AppDestination.About -> AboutScreen(assets = context.assets, onBack = navigator::back)
            AppDestination.Support ->
                SupportScreen(
                    links = remember { application.supportLinks() },
                    onOpenLink = { index -> dispatcher.dispatch(HelpCommandIds.SUPPORT_OPEN_LINK, buildJsonObject { put("index", index) }) },
                    onBack = navigator::back,
                )
            AppDestination.LatencyTest -> LatencyTestScreen(engine = engine, onBack = navigator::back)
            AppDestination.ShapeTraces ->
                ShapeTraceScreen(
                    recorder = application.shapeTraceRecorder,
                    onBack = navigator::back,
                    onRemoveLast = { dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_REMOVE_LAST) },
                    onClear = { dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_CLEAR) },
                    onExport = { uri ->
                        dispatcher.dispatch(AppCommandIds.SHAPE_TRACES_EXPORT, buildJsonObject { put("uri", uri.toString()) }) { result ->
                            val message =
                                when (result) {
                                    is CommandResult.Success -> resources.getString(R.string.shape_traces_export_done)
                                    is CommandResult.Failure -> resources.getString(R.string.diagnostics_export_failed, result.message)
                                }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            AppDestination.TouchSessions ->
                TouchSessionScreen(
                    recorder = application.touchSessionRecorder,
                    xdpi = application.resources.displayMetrics.xdpi,
                    onBack = navigator::back,
                    onRemoveLast = { dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_REMOVE_LAST) },
                    onClear = { dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_CLEAR) },
                    onExport = { uri ->
                        dispatcher.dispatch(AppCommandIds.TOUCH_SESSIONS_EXPORT, buildJsonObject { put("uri", uri.toString()) }) { result ->
                            val message =
                                when (result) {
                                    is CommandResult.Success -> resources.getString(R.string.touch_sessions_export_done)
                                    is CommandResult.Failure -> resources.getString(R.string.diagnostics_export_failed, result.message)
                                }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            AppDestination.BrushEditor -> {
                val editor = application.brushUi.editor
                if (editor == null) {
                    LaunchedEffect(Unit) { if (navigator.current == AppDestination.BrushEditor) navigator.back() }
                } else {
                    BrushEditorScreen(
                        editor = editor,
                        onCommand = { commandId, arguments -> dispatcher.dispatch(commandId, arguments) },
                        stylusRollReported = application.inputSettings.stylusRollReported,
                    )
                }
            }
        }
    }
    val surround = canvasSurroundColor(LocalDarkTheme.current)
    when (navigator.current) {
        AppDestination.Canvas -> {
            ArtworkOpenOverlay(application.gallery.transitions, session, MaterialTheme.colorScheme.background, surround)
            ArtworkClosingCover(application.gallery.transitions, surround)
            ArtworkLoadingIndicator(session)
        }
        AppDestination.Gallery -> {
            ArtworkCloseOverlay(application.gallery.transitions, surround)
            BlockingLoadingIndicator(application.gallery.busy)
        }
        else -> Unit
    }
    val help = application.help
    LaunchedEffect(session.canvasTool) { help.toolSelected(session.canvasTool) }
    val tip = help.tip
    if (tip != null && navigator.current == AppDestination.Canvas) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = TIP_TOP_PADDING, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            ToolTipCard(tip, onDismiss = { dispatcher.dispatch(HelpCommandIds.TIP_DISMISS) })
        }
    }
    if (help.welcomeVisible) {
        WelcomeOverlay(help)
        BackHandler { dispatcher.dispatch(HelpCommandIds.WELCOME_FINISH) }
    }
}

private val TIP_TOP_PADDING = 64.dp

private fun timelapseNotice(
    commandId: String,
    result: CommandResult,
): Int? =
    when {
        commandId != TimelapseCommandIds.REPLAY && commandId != TimelapseCommandIds.EXPORT -> null
        result !is CommandResult.Failure -> null
        result.message.startsWith("No timelapse") -> R.string.timelapse_none
        else -> R.string.timelapse_export_failed
    }

private const val SCREEN_FADE_OUT_MILLIS = 90
private const val SCREEN_FADE_IN_MILLIS = 160

private fun galleryFailureMessage(
    resources: android.content.res.Resources,
    message: String,
): String {
    val error = GalleryError.entries.firstOrNull { message.startsWith(it.name + ":") }
    return when (error) {
        GalleryError.NotFound -> resources.getString(R.string.gallery_error_not_found)
        GalleryError.NeedsNewerApp -> resources.getString(R.string.gallery_error_newer_app)
        GalleryError.Unsupported -> resources.getString(R.string.gallery_error_unsupported)
        GalleryError.Unreadable -> resources.getString(R.string.gallery_error_unreadable)
        GalleryError.WriteFailed -> resources.getString(R.string.gallery_error_write)
        GalleryError.TooLarge -> resources.getString(R.string.gallery_error_too_large)
        null -> resources.getString(R.string.gallery_error_generic, message)
    }
}

private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(mapValues { (name, value) -> toJson(name, value) })

private fun toJson(
    name: String,
    value: Any?,
): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { toJson(name, it) })
        else -> error("Unsupported argument type for '$name': ${value::class.simpleName}")
    }
