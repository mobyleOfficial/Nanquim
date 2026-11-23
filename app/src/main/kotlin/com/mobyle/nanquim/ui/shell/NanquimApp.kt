package com.mobyle.nanquim.ui.shell

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.animation.playedFrames
import com.mobyle.nanquim.engine.EngineEvent
import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.gallery.ImportTarget
import com.mobyle.nanquim.gallery.InterchangeFormat
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.ui.brush.BrushEditorRoute
import com.mobyle.nanquim.ui.canvas.CanvasRoute
import com.mobyle.nanquim.ui.canvas.canvasSurroundColor
import com.mobyle.nanquim.ui.common.AdsNoticeDialog
import com.mobyle.nanquim.ui.common.ExitDialog
import com.mobyle.nanquim.ui.common.rememberToast
import com.mobyle.nanquim.ui.diagnostics.DiagnosticsRoute
import com.mobyle.nanquim.ui.diagnostics.LatencyTestRoute
import com.mobyle.nanquim.ui.diagnostics.ShapeTracesRoute
import com.mobyle.nanquim.ui.diagnostics.TouchSessionsRoute
import com.mobyle.nanquim.ui.gallery.ExportAnimation
import com.mobyle.nanquim.ui.gallery.ExportDialog
import com.mobyle.nanquim.ui.gallery.GalleryRoute
import com.mobyle.nanquim.ui.gallery.IncomingFileDialog
import com.mobyle.nanquim.ui.gallery.NewCanvasRoute
import com.mobyle.nanquim.ui.gallery.TransferReportDialog
import com.mobyle.nanquim.ui.gallery.rememberGalleryFailureNotice
import com.mobyle.nanquim.ui.help.CanvasTourCard
import com.mobyle.nanquim.ui.help.ToolTipCard
import com.mobyle.nanquim.ui.help.WelcomeOverlay
import com.mobyle.nanquim.ui.settings.AboutRoute
import com.mobyle.nanquim.ui.settings.GestureSettingsRoute
import com.mobyle.nanquim.ui.settings.KeyboardShortcutsRoute
import com.mobyle.nanquim.ui.settings.SettingsRoute
import com.mobyle.nanquim.ui.settings.SupportRoute
import com.mobyle.nanquim.ui.theme.LocalDarkTheme
import com.mobyle.nanquim.ui.transition.ArtworkCloseOverlay
import com.mobyle.nanquim.ui.transition.ArtworkClosingCover
import com.mobyle.nanquim.ui.transition.ArtworkLoadingIndicator
import com.mobyle.nanquim.ui.transition.ArtworkOpenOverlay
import com.mobyle.nanquim.ui.transition.BlockingLoadingIndicator
import kotlinx.coroutines.launch

/** The activity's whole UI: the current screen, and the sheets, dialogs and help cards that can show over any of them. */
@Composable
fun NanquimApp(viewModel: AppShellViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toast = rememberToast()
    val failed = rememberGalleryFailureNotice()
    val navigator = viewModel.navigator
    val session = viewModel.session
    val transfer = viewModel.transfer
    val help = viewModel.help

    LaunchedEffect(viewModel.engine) {
        viewModel.engine.events.collect { event ->
            if (event is EngineEvent.Error) toast(resources.getString(R.string.engine_error_notice, event.message), true)
        }
    }
    LaunchedEffect(session.exportSheetRequests) { viewModel.onExportSheetRequested() }

    val coroutineScope = rememberCoroutineScope()
    val saveToLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> viewModel.saveTo(uri, failed) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.import(uri, context.contentResolver.getType(uri), failed)
        }
    val photoLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) viewModel.importAs(uri, ImportTarget.NewArtwork, failed)
        }
    viewModel.exportIds?.let { ids ->
        val count = if (ids.isEmpty()) 1 else ids.size
        val openArtworkOnly = ids.isEmpty() || ids == listOfNotNull(session.artwork?.id)
        val animationState = session.document?.takeIf { it.animation.enabled && openArtworkOnly }
        ExportDialog(
            artworkCount = count,
            cmykProfiles = viewModel.cmykProfiles,
            documentCmyk = session.cmykProfile?.takeIf { openArtworkOnly }?.name,
            animation = animationState?.let { ExportAnimation(it.playedFrames.size, it.animation.framesPerSecond) },
            onShare = { options -> viewModel.share(ids, options, failed) },
            onSaveTo = { options ->
                viewModel.closeExport()
                coroutineScope.launch { saveToLauncher.launch(viewModel.prepareSaveTo(ids, options)) }
            },
            onDismiss = viewModel::closeExport,
        )
    }
    viewModel.incomingFile?.let { uri ->
        val wholeArtworkOnly =
            remember(uri) {
                val format = transfer.interchangeFormatOf(uri)
                transfer.isDocumentPackage(uri) || (format != null && format != InterchangeFormat.Tiff)
            }
        val isBrush by produceState(false, uri) { value = transfer.isBrushFile(uri) }
        val isPalette by produceState(false, uri) { value = transfer.isPaletteFile(uri) }
        IncomingFileDialog(
            name = transfer.displayName(uri) ?: uri.lastPathSegment.orEmpty(),
            isPackage = wholeArtworkOnly,
            isBrush = isBrush,
            isPalette = isPalette,
            canAddLayer = session.documentSession != null,
            onImport = { target -> viewModel.importIncoming(target, failed) },
            onImportPalette = { viewModel.importIncomingPalette(failed) },
            onDismiss = viewModel::dismissIncoming,
        )
    }
    transfer.report?.let { report ->
        TransferReportDialog(report = report, onDismiss = { transfer.report = null })
    }
    BackHandler(enabled = viewModel.galleryBackEnabled, onBack = viewModel::galleryBack)
    if (viewModel.showAdsNotice) AdsNoticeDialog(onDismiss = viewModel::markAdsNoticeSeen)
    if (viewModel.confirmingExit) {
        ExitDialog(
            onExit = { dontAskAgain ->
                viewModel.exit(dontAskAgain)
                (context as? Activity)?.finish()
            },
            onDismiss = viewModel::dismissExit,
        )
    }
    BackHandler(enabled = viewModel.backEnabled) { viewModel.back(failed) }

    AppNavDisplay(
        navigator = navigator,
        // The fade passes through transparency between screens; without this the black window background flashes through.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) { destination ->
        when (destination) {
            AppDestination.Gallery ->
                GalleryRoute(
                    onImport = { importLauncher.launch(IMPORT_TYPES) },
                    onPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onShare = viewModel::openExport,
                )
            AppDestination.NewCanvas -> NewCanvasRoute()
            AppDestination.Canvas ->
                CanvasRoute(
                    onShare = { viewModel.openExport(emptyList()) },
                    onAddImage = { importLauncher.launch(arrayOf("image/*", "video/*")) },
                )
            AppDestination.Settings -> SettingsRoute()
            AppDestination.Diagnostics -> DiagnosticsRoute()
            AppDestination.GestureSettings -> GestureSettingsRoute()
            AppDestination.KeyboardShortcuts -> KeyboardShortcutsRoute()
            AppDestination.About -> AboutRoute()
            AppDestination.Support -> SupportRoute()
            AppDestination.LatencyTest -> LatencyTestRoute()
            AppDestination.ShapeTraces -> ShapeTracesRoute()
            AppDestination.TouchSessions -> TouchSessionsRoute()
            AppDestination.BrushEditor -> BrushEditorRoute()
        }
    }
    val surround = canvasSurroundColor(LocalDarkTheme.current)
    when (navigator.current) {
        AppDestination.Canvas -> {
            ArtworkOpenOverlay(viewModel.gallery.transitions, session, MaterialTheme.colorScheme.background, surround)
            ArtworkClosingCover(viewModel.gallery.transitions, surround)
            ArtworkLoadingIndicator(session)
        }
        AppDestination.Gallery -> {
            ArtworkCloseOverlay(viewModel.gallery.transitions, surround)
            BlockingLoadingIndicator(viewModel.gallery.busy)
        }
        else -> Unit
    }
    LaunchedEffect(session.canvasTool) { help.toolSelected(session.canvasTool) }
    val tip = help.tip
    if (tip != null && navigator.current == AppDestination.Canvas) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = TIP_TOP_PADDING, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            ToolTipCard(tip, onDismiss = viewModel::dismissTip)
        }
    }
    LaunchedEffect(navigator.current, help.welcomeVisible, session.documentShown) {
        if (navigator.current == AppDestination.Canvas && session.documentShown) help.canvasShown()
    }
    val tourStep = help.tourStep
    if (tourStep != null && navigator.current == AppDestination.Canvas) {
        CanvasTourCard(tourStep, onNext = help::nextTourStep, onSkip = help::finishTour)
    }
    if (help.welcomeVisible) {
        // From the gallery the last card opens a new canvas; when replayed from Settings it just closes.
        WelcomeOverlay(
            help,
            onStartDrawing = if (navigator.current == AppDestination.Gallery) ({ navigator.open(AppDestination.NewCanvas) }) else null,
        )
        BackHandler(onBack = viewModel::finishWelcome)
    }
}

private val TIP_TOP_PADDING = 64.dp

private val IMPORT_TYPES =
    arrayOf(
        "image/*",
        NanquimFileType.Document.mimeType,
        "application/zip",
        "application/octet-stream",
        "application/x-photoshop",
        "application/photoshop",
    )
