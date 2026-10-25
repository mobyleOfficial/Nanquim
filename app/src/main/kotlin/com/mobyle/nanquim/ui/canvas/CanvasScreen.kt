package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.CanvasSurfaceView
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.command.AnimationCommandIds
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.CanvasCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.ReferenceCommandIds
import com.mobyle.nanquim.command.SliderNames
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.document.DocumentPersistence
import com.mobyle.nanquim.document.SaveStatus
import com.mobyle.nanquim.macro.Macros
import com.mobyle.nanquim.settings.QuickMenuSettings
import com.mobyle.nanquim.ui.brush.BrushLibraryPanel
import com.mobyle.nanquim.ui.color.ColorPanel
import com.mobyle.nanquim.ui.common.LocalWindowLayout
import com.mobyle.nanquim.ui.theme.LocalDarkTheme
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineEvent
import com.mobyle.nanquim.engine.GpuInfo
import com.mobyle.nanquim.engine.ViewportStyle

@Composable
fun CanvasScreen(
    engine: Engine,
    session: CanvasSession,
    brushUi: BrushUiState,
    quickMenu: QuickMenuSettings,
    macros: Macros,
    onOpenSettings: () -> Unit,
    onToggleDebugPanel: () -> Unit,
    onOpenGallery: () -> Unit,
    onShare: () -> Unit,
    onAddImage: () -> Unit,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MAXIMUM_CANVAS_FONT_SCALE))) {
        Box(Modifier.fillMaxSize()) {
            CanvasScreenContent(
                engine,
                session,
                brushUi,
                quickMenu,
                macros,
                onOpenSettings,
                onToggleDebugPanel,
                onOpenGallery,
                onShare,
                onAddImage,
                onCommand,
            )
            ToolAnnouncement(session, brushUi)
        }
    }
}

@Composable
private fun ToolAnnouncement(
    session: CanvasSession,
    brushUi: BrushUiState,
) {
    val tool = stringResource(session.canvasTool.titleRes)
    val brush = brushUi.brushes[session.currentBrushTool]?.name
    val announcement =
        if (brush != null && session.canvasTool in BRUSH_TOOLS) stringResource(R.string.canvas_tool_announcement, tool, brush) else tool
    Box(
        Modifier
            .size(1.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            },
    )
}

private val CanvasTool.titleRes: Int
    get() =
        when (this) {
            CanvasTool.Brush -> R.string.tool_brush
            CanvasTool.Smudge -> R.string.tool_smudge
            CanvasTool.Eraser -> R.string.tool_eraser
            CanvasTool.Selection -> R.string.tool_selection
            CanvasTool.Transform -> R.string.tool_transform
            CanvasTool.Clone -> R.string.tool_clone
            CanvasTool.Crop -> R.string.tool_crop
            CanvasTool.Adjust -> R.string.tool_adjustments
            CanvasTool.Guide -> R.string.tool_guide
            CanvasTool.Text -> R.string.tool_text
        }

private val BRUSH_TOOLS = setOf(CanvasTool.Brush, CanvasTool.Smudge, CanvasTool.Eraser)

private const val MAXIMUM_CANVAS_FONT_SCALE = 1.3f

@Composable
private fun CanvasScreenContent(
    engine: Engine,
    session: CanvasSession,
    brushUi: BrushUiState,
    quickMenu: QuickMenuSettings,
    macros: Macros,
    onOpenSettings: () -> Unit,
    onToggleDebugPanel: () -> Unit,
    onOpenGallery: () -> Unit,
    onShare: () -> Unit,
    onAddImage: () -> Unit,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val unrecoverableError by engine.unrecoverableError.collectAsState()
    val darkTheme = LocalDarkTheme.current
    val displayMetrics = LocalContext.current.resources.displayMetrics
    LaunchedEffect(darkTheme) {
        val surround = if (darkTheme) DARK_SURROUND else LIGHT_SURROUND
        engine.setViewportStyle(ViewportStyle(surround, surround, surround))
    }
    DisplayColorEffect(engine, session)
    session.rasterizePrompt?.let {
        AlertDialog(
            onDismissRequest = { session.answerRasterizePrompt(false) },
            title = {
                Text(
                    stringResource(if (session.rasterizePromptIsVideo) R.string.video_rasterize_title else R.string.text_rasterize_title),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (session.rasterizePromptIsVideo) R.string.video_rasterize_message else R.string.text_rasterize_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { session.answerRasterizePrompt(true) },
                ) { Text(stringResource(R.string.text_rasterize_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { session.answerRasterizePrompt(false) },
                ) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
    if (session.missingFonts.isNotEmpty()) {
        MissingFontsDialog(session.missingFonts, onDismiss = session::dismissMissingFonts)
    }

    val bottomBarHeights = remember { mutableStateMapOf<Any, Int>() }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> CanvasSurfaceView(context, engine, session, onCommand) },
            modifier = Modifier.fillMaxSize(),
        )
        SelectionShapeOverlay(session)
        TransformOverlay(session)
        CloneMarker(session)
        PerspectivePuck(session)
        CropOverlay(session)
        GuideOverlay(session, onCommand)
        TextEditOverlay(session)
        TextKeyboard(session)
        ShapeEditOverlay(session)
        HoverPreview(session)
        ReferenceWindow(
            session = session,
            onCommand = onCommand,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = LIBRARY_PANEL_TOP),
        )
        unrecoverableError?.let { error -> EngineFailureMessage(error) }
        if (brushUi.libraryTool != null || session.layersPanelVisible || session.colorPanelVisible) {
            PanelDismissArea {
                if (brushUi.libraryTool != null) onCommand(BrushCommandIds.LIBRARY_CLOSE, emptyMap())
                if (session.layersPanelVisible) onCommand(DocumentCommandIds.LAYERS_PANEL, mapOf("visible" to false))
                if (session.colorPanelVisible) onCommand(ToolCommandIds.COLOR_PANEL_CLOSE, emptyMap())
            }
        }
        val fullScreen = session.fullScreen
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!fullScreen) {
                BarPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    CanvasBar(
                        session = session,
                        onCommand = onCommand,
                        onOpenGallery = onOpenGallery,
                        onShare = onShare,
                        onAddImage = onAddImage,
                        onOpenSettings = onOpenSettings,
                        onToggleDebugPanel = onToggleDebugPanel,
                    ) {
                        SaveStatusText(session.persistence)
                        ShapeEditButton(session, onCommand)
                    }
                }
            }
            if (session.memoryHudVisible) MemoryHud(engine, Modifier.padding(8.dp))
        }
        val tool = session.currentBrushTool
        val brushId = session.brushes[tool]?.brushId
        val sizePresets by produceState(emptyList<Float>(), brushId, tool, session.sliderPresetsVersion) {
            value = brushId?.let { brushUi.library.sliderPresets(it, tool, SliderNames.SIZE) }.orEmpty()
        }
        val opacityPresets by produceState(emptyList<Float>(), brushId, tool, session.sliderPresetsVersion) {
            value = brushId?.let { brushUi.library.sliderPresets(it, tool, SliderNames.OPACITY) }.orEmpty()
        }

        @Suppress("UNUSED_VARIABLE")
        val documentVersion = session.documentVersion
        val bottomBarsReserve =
            if (LocalWindowLayout.current.compact) {
                with(LocalDensity.current) { (bottomBarHeights.values.maxOrNull() ?: 0).toDp() }
            } else {
                0.dp
            }
        if (!fullScreen) {
            ToolSliders(
                settings = session.currentToolSettings,
                eyedropperArmed = session.eyedropperArmed,
                modifierHeld = session.modifierHeld,
                onModifierPress = session::pressModifier,
                onModifierTap = session::tapModifier,
                canUndo = session.documentSession?.history?.canUndo == true,
                canRedo = session.documentSession?.history?.canRedo == true,
                sizePresets = sizePresets,
                opacityPresets = opacityPresets,
                onCommand = onCommand,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 8.dp, top = LIBRARY_PANEL_TOP, bottom = 8.dp + bottomBarsReserve),
            )
        }
        var shownLibraryTool by remember { mutableStateOf(brushUi.libraryTool) }
        brushUi.libraryTool?.let { shownLibraryTool = it }
        val compact = LocalWindowLayout.current.compact
        SideEffect { session.panelsOneAtATime = compact }
        if (compact) {
            BottomSheet(visible = session.colorPanelVisible) {
                ColorPanel(session = session, compact = true, onCommand = onCommand, modifier = Modifier.fillMaxWidth())
            }
            BottomSheet(visible = brushUi.libraryTool != null && !fullScreen) {
                shownLibraryTool?.let { libraryTool ->
                    BrushLibraryPanel(
                        ui = brushUi,
                        tool = libraryTool,
                        onCommand = onCommand,
                        compact = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            BottomSheet(visible = session.layersPanelVisible) {
                LayersPanel(session = session, onCommand = onCommand, modifier = Modifier.fillMaxWidth())
            }
        } else {
            BoxWithConstraints(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = LIBRARY_PANEL_TOP, end = 8.dp),
            ) {
                val room = if (session.colorPanelVisible) maxWidth - COLOR_PANEL_WIDTH - PANEL_GAP else maxWidth
                val height = maxHeight - PANEL_BOTTOM_MARGIN
                Row(horizontalArrangement = Arrangement.spacedBy(PANEL_GAP)) {
                    TopEndPanel(visible = session.colorPanelVisible, maxHeight = height) {
                        ColorPanel(session = session, compact = false, onCommand = onCommand, modifier = Modifier.width(COLOR_PANEL_WIDTH))
                    }
                    TopEndPanel(visible = brushUi.libraryTool != null && !fullScreen, maxHeight = height) {
                        shownLibraryTool?.let { libraryTool ->
                            BrushLibraryPanel(
                                ui = brushUi,
                                tool = libraryTool,
                                onCommand = onCommand,
                                modifier = Modifier.width(LIBRARY_PANEL_WIDTH.coerceAtMost(room)),
                                maxHeight = height,
                            )
                        }
                    }
                    TopEndPanel(visible = session.layersPanelVisible, maxHeight = height) {
                        LayersPanel(
                            session = session,
                            onCommand = onCommand,
                            modifier = Modifier.width(LAYERS_PANEL_WIDTH.coerceAtMost(room)),
                        )
                    }
                }
            }
        }
        BottomBar(heights = bottomBarHeights, visible = session.canvasTool == CanvasTool.Selection && session.documentSession != null) {
            SelectionToolBar(session = session, onCommand = onCommand)
        }
        BottomBar(
            heights = bottomBarHeights,
            visible = session.canvasTool == CanvasTool.Transform && session.transformEditor != null,
            animateExit = false,
        ) {
            TransformToolBar(session = session, onCommand = onCommand)
        }
        if (session.canvasTool == CanvasTool.Adjust && session.adjustmentEditor != null) {
            AdjustmentBanner(
                session = session,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = ADJUSTMENT_BANNER_TOP),
            )
            AdjustmentMenu(session, onCommand)
        }
        BottomBar(
            heights = bottomBarHeights,
            visible = session.canvasTool == CanvasTool.Adjust && session.adjustmentEditor != null,
            animateExit = false,
        ) {
            AdjustmentPanel(session = session, onCommand = onCommand)
        }
        BottomBar(heights = bottomBarHeights, visible = session.canvasTool == CanvasTool.Clone && session.documentSession != null) {
            CloneToolBar(session = session, onCommand = onCommand)
        }
        BottomBar(heights = bottomBarHeights, visible = session.canvasTool == CanvasTool.Guide && session.documentSession != null) {
            GuideToolBar(session = session, onCommand = onCommand)
        }
        BottomBar(heights = bottomBarHeights, visible = session.canvasTool == CanvasTool.Text && session.documentSession != null) {
            TextToolBar(session = session, onCommand = onCommand)
        }
        BottomBar(heights = bottomBarHeights, visible = session.canvasTool == CanvasTool.Crop && session.documentSession != null) {
            CropToolBar(session = session, onCommand = onCommand)
        }
        VideoScrubber(
            session = session,
            onCommand = onCommand,
            modifier =
                Modifier
                    .align(
                        Alignment.TopCenter,
                    ).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = ADJUSTMENT_BANNER_TOP),
        )
        BottomBar(
            heights = bottomBarHeights,
            visible = session.document?.animation?.enabled == true && session.canvasTool in PAINTING_TOOLS && !fullScreen,
        ) {
            TimelineBar(session = session, onCommand = onCommand, modifier = Modifier.fillMaxWidth())
        }
        FillOverlay(session, onCommand)
        CopyPasteMenu(session, onCommand)
        QuickMenu(session, quickMenu, brushUi.brushes[BrushTool.Paint]?.let { it.brushId to it.name }, onCommand)
        CanvasInfoDialog(session, onCommand)
        MacrosPanel(macros, onCommand)
        MacroRecordingBar(
            macros,
            onCommand,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 16.dp),
        )
        if (fullScreen) {
            TextButton(
                onClick = { onCommand(CanvasCommandIds.VIEW_FULL_SCREEN, mapOf("enabled" to false)) },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(4.dp)
                        .alpha(FULL_SCREEN_EXIT_ALPHA),
            ) { Text(stringResource(R.string.canvas_exit_full_screen)) }
        }
        session.eyedropper?.let { loupe -> EyedropperLoupeView(loupe) }
        session.layerSelectToast?.let { toast ->
            LayerSelectToastView(
                toast,
                onTimeout = { session.dismissLayerSelectToast(toast.serial) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        session.historyToast?.let { toast ->
            HistoryToastView(
                toast,
                onTimeout = { session.dismissHistoryToast(toast.serial) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        session.brushes.lastError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            )
        }
        session.timelapseReplay?.let { segments -> TimelapseReplay(segments, onClose = { session.timelapseReplay = null }) }
        if (BuildConfig.DEVELOPER_TOOLS && session.debugPanelVisible) {
            DebugPanel(
                session = session,
                onCommand = onCommand,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            )
        }
    }
}

@Composable
private fun TopEndPanel(
    visible: Boolean,
    maxHeight: Dp,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(PANEL_MILLIS)) + scaleIn(tween(PANEL_MILLIS), PANEL_START_SCALE, TOP_END),
        exit = fadeOut(tween(PANEL_EXIT_MILLIS)) + scaleOut(tween(PANEL_EXIT_MILLIS), PANEL_START_SCALE, TOP_END),
    ) {
        Box(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun PanelDismissArea(onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                dismiss()
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        },
    )
}

@Composable
private fun BoxScope.BottomSheet(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing),
        enter = slideInVertically(tween(PANEL_MILLIS)) { it } + fadeIn(tween(PANEL_MILLIS)),
        exit = slideOutVertically(tween(PANEL_EXIT_MILLIS)) { it } + fadeOut(tween(PANEL_EXIT_MILLIS)),
    ) { content() }
}

@Composable
private fun BoxScope.BottomBar(
    visible: Boolean,
    heights: SnapshotStateMap<Any, Int>,
    animateExit: Boolean = true,
    content: @Composable () -> Unit,
) {
    val key = remember { Any() }
    DisposableEffect(key) { onDispose { heights.remove(key) } }
    AnimatedVisibility(
        visible = visible,
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onSizeChanged { heights[key] = it.height }
                .padding(12.dp),
        enter = slideInVertically(tween(PANEL_MILLIS)) { it / 2 } + fadeIn(tween(PANEL_MILLIS)),
        exit =
            if (animateExit) {
                slideOutVertically(tween(PANEL_EXIT_MILLIS)) { it / 2 } + fadeOut(tween(PANEL_EXIT_MILLIS))
            } else {
                ExitTransition.None
            },
    ) { content() }
}

private const val FULL_SCREEN_EXIT_ALPHA = 0.6f

private val PAINTING_TOOLS = setOf(CanvasTool.Brush, CanvasTool.Smudge, CanvasTool.Eraser)

private const val PANEL_MILLIS = 180
private const val PANEL_EXIT_MILLIS = 120
private const val PANEL_START_SCALE = 0.92f
private val TOP_END = TransformOrigin(1f, 0f)

@Composable
private fun EngineFailureMessage(error: EngineEvent.Error) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.engine_unrecoverable_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = error.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun canvasSurroundColor(darkTheme: Boolean): androidx.compose.ui.graphics.Color {
    val level = if (darkTheme) DARK_SURROUND else LIGHT_SURROUND
    return androidx.compose.ui.graphics
        .Color(level, level, level)
}

private const val DARK_SURROUND = 0.16f
private const val LIGHT_SURROUND = 0.82f

private val LIBRARY_PANEL_TOP = 60.dp
private val ADJUSTMENT_BANNER_TOP = 112.dp
private val LIBRARY_PANEL_WIDTH = 620.dp
private val LAYERS_PANEL_WIDTH = 400.dp
private val COLOR_PANEL_WIDTH = 340.dp
private val PANEL_GAP = 8.dp
private val PANEL_BOTTOM_MARGIN = 8.dp

@Composable
private fun SaveStatusText(persistence: DocumentPersistence) {
    if (!persistence.isAttached || persistence.status == SaveStatus.Saved) return
    val text =
        when (persistence.status) {
            SaveStatus.Saved -> stringResource(R.string.save_status_saved)
            SaveStatus.Pending -> stringResource(R.string.save_status_pending)
            SaveStatus.Saving -> stringResource(R.string.save_status_saving)
            SaveStatus.Failed -> stringResource(R.string.save_status_failed, persistence.lastError.orEmpty())
        }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 6.dp),
        color =
            if (persistence.status ==
                SaveStatus.Failed
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

private const val DISPLAY_P3_PASSTHROUGH = "EGL_EXT_gl_colorspace_display_p3_passthrough"

@Composable
private fun DisplayColorEffect(
    engine: Engine,
    session: CanvasSession,
) {
    val view = LocalView.current
    val gpuInfo by produceState<GpuInfo?>(null, engine) { engine.requestGpuInfo { value = it } }
    session.documentVersion
    val profile = session.colorProfile
    val cmykProfile = session.cmykProfile
    LaunchedEffect(profile, cmykProfile, gpuInfo) {
        val info = gpuInfo ?: return@LaunchedEffect
        val wideDisplay = view.display?.isWideColorGamut == true && DISPLAY_P3_PASSTHROUGH in info.eglExtensions
        val displayP3 = wideDisplay && profile != null
        setWindowColorMode(view, if (displayP3) ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT else ActivityInfo.COLOR_MODE_DEFAULT)
        session.updateDisplayColor(displayP3)
    }
    DisposableEffect(view) {
        onDispose { setWindowColorMode(view, ActivityInfo.COLOR_MODE_DEFAULT) }
    }
}

private fun setWindowColorMode(
    view: View,
    colorMode: Int,
) {
    val window = view.context.findActivity()?.window ?: return
    window.colorMode = colorMode
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

@Composable
private fun MissingFontsDialog(
    fontIds: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_missing_fonts_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.text_missing_fonts_message))
                fontIds.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) } },
    )
}

@Composable
private fun BarPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = BAR_PANEL_ALPHA),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.padding(horizontal = 4.dp)) { content() }
    }
}

private const val BAR_PANEL_ALPHA = 0.94f
