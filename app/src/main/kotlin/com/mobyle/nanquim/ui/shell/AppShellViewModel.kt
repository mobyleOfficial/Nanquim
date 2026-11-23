package com.mobyle.nanquim.ui.shell

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.LaunchState
import com.mobyle.nanquim.ads.GalleryAds
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.GalleryCommandIds
import com.mobyle.nanquim.command.HelpCommandIds
import com.mobyle.nanquim.command.PaletteCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.command.TransferCommandIds
import com.mobyle.nanquim.command.VideoCommandIds
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.ProfileColorSpace
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.ExportOptions
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.ImportTarget
import com.mobyle.nanquim.help.Help
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.ui.gallery.dispatchGalleryCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * The activity-wide part of the UI that every screen shares: the export sheet, files arriving from other apps, leaving
 * the app, the introduction and tips, and what the back button does. Scoped to the activity, not to a destination.
 */
@HiltViewModel
class AppShellViewModel
    @Inject
    constructor(
        val navigator: AppNavigator,
        val engine: Engine,
        val session: CanvasSession,
        val brushUi: BrushUiState,
        val gallery: Gallery,
        val transfer: ArtworkTransfer,
        val help: Help,
        val appSettings: AppSettings,
        val galleryAds: GalleryAds,
        private val colorProfiles: ColorProfileLibrary,
        private val launchState: LaunchState,
        private val dispatcher: CommandDispatcher,
    ) : ViewModel() {
        /** The artworks the export sheet is open for; empty means the open artwork, null means it is closed. */
        var exportIds by mutableStateOf<List<String>?>(null)
            private set

        /** The canvas asks for the export sheet by counting up; this is the last request already answered. */
        private var exportRequestsSeen by mutableIntStateOf(session.exportSheetRequests)

        /** An export waiting for the person to pick where to save it. */
        private var pendingSaveTo: Pair<List<String>, ExportOptions>? = null

        var confirmingExit by mutableStateOf(false)
            private set

        val incomingFile: Uri? get() = launchState.incomingFile

        val cmykProfiles: List<Pair<String, String>>
            get() = colorProfiles.entries.filter { it.info.colorSpace == ProfileColorSpace.Cmyk && it.info.convertsTo }.map { it.id to it.name }

        private val panelOpen: Boolean
            get() = brushUi.libraryTool != null || session.layersPanelVisible || session.colorPanelVisible

        /** At the gallery with nothing under it, where back leaves a selection or a stack, or the app. */
        val atGalleryRoot: Boolean get() = navigator.current == AppDestination.Gallery && !navigator.canGoBack

        val galleryBackEnabled: Boolean
            get() = atGalleryRoot && (gallery.selecting || gallery.openStackId != null || appSettings.confirmExit)

        val backEnabled: Boolean get() = navigator.canGoBack || session.debugPanelVisible || panelOpen

        val showAdsNotice: Boolean
            get() =
                atGalleryRoot && galleryAds.enabled && help.automatic && !help.welcomeVisible && !appSettings.adsNoticeSeen

        fun galleryBack() {
            when {
                gallery.selecting -> gallery.changeSelecting(false)
                gallery.openStackId != null -> gallery.openStackId = null
                else -> confirmingExit = true
            }
        }

        fun back(onFailure: (String) -> Unit) {
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
                navigator.current == AppDestination.Canvas -> dispatcher.dispatchGalleryCommand(GalleryCommandIds.CLOSE, emptyMap(), onFailure)
                else -> navigator.back()
            }
        }

        fun exit(dontAskAgain: Boolean) {
            confirmingExit = false
            if (dontAskAgain) dispatcher.dispatch(AppCommandIds.SETTINGS_CONFIRM_EXIT, buildJsonObject { put("enabled", false) })
        }

        fun dismissExit() {
            confirmingExit = false
        }

        fun markAdsNoticeSeen() = appSettings.markAdsNoticeSeen()

        /** Opens the export sheet when the canvas asked for it since last time. */
        fun onExportSheetRequested() {
            if (session.exportSheetRequests != exportRequestsSeen) {
                exportRequestsSeen = session.exportSheetRequests
                if (session.artwork != null) exportIds = emptyList()
            }
        }

        fun openExport(ids: List<String>) {
            exportIds = ids
        }

        fun closeExport() {
            exportIds = null
        }

        fun share(
            ids: List<String>,
            options: ExportOptions,
            onFailure: (String) -> Unit,
        ) {
            exportIds = null
            val arguments = mapOf<String, Any?>("ids" to ids.ifEmpty { null }) + options.commandArguments()
            dispatcher.dispatchGalleryCommand(TransferCommandIds.SHARE, arguments, onFailure)
        }

        /** Remembers the export and returns the file name to suggest in the system's save dialog. */
        suspend fun prepareSaveTo(
            ids: List<String>,
            options: ExportOptions,
        ): String {
            val targetIds = ids.ifEmpty { listOfNotNull(session.artwork?.id) }
            pendingSaveTo = targetIds to options
            return transfer.fileName(targetIds.first(), options)
        }

        fun saveTo(
            uri: Uri?,
            onFailure: (String) -> Unit,
        ) {
            val request = pendingSaveTo
            pendingSaveTo = null
            if (uri == null || request == null) return
            val (ids, options) = request
            val arguments =
                mapOf<String, Any?>("uri" to uri.toString(), "id" to ids.firstOrNull()) +
                    if (options.isPackage) emptyMap() else options.commandArguments()
            dispatcher.dispatchGalleryCommand(
                if (options.isPackage) TransferCommandIds.EXPORT_DOCUMENT else TransferCommandIds.EXPORT_IMAGE,
                arguments,
                onFailure,
            )
        }

        /** A file picked with the import button: a layer (or video) on the canvas, a new artwork in the gallery. */
        fun import(
            uri: Uri,
            mimeType: String?,
            onFailure: (String) -> Unit,
        ) {
            val canvas = navigator.current == AppDestination.Canvas
            if (canvas && mimeType?.startsWith("video/") == true) {
                dispatcher.dispatchGalleryCommand(VideoCommandIds.IMPORT, mapOf("uri" to uri.toString()), onFailure)
            } else {
                val target = if (canvas) ImportTarget.Layer else ImportTarget.NewArtwork
                importAs(uri, target, onFailure)
            }
        }

        fun importAs(
            uri: Uri,
            target: ImportTarget,
            onFailure: (String) -> Unit,
        ) = dispatcher.dispatchGalleryCommand(TransferCommandIds.IMPORT, mapOf("uri" to uri.toString(), "target" to target.id), onFailure)

        fun importIncoming(
            target: ImportTarget,
            onFailure: (String) -> Unit,
        ) {
            val uri = launchState.incomingFile ?: return
            launchState.incomingFile = null
            importAs(uri, target, onFailure)
        }

        fun importIncomingPalette(onFailure: (String) -> Unit) {
            val uri = launchState.incomingFile ?: return
            launchState.incomingFile = null
            dispatcher.dispatchGalleryCommand(PaletteCommandIds.IMPORT, mapOf("uri" to uri.toString()), onFailure)
        }

        fun dismissIncoming() {
            launchState.incomingFile = null
        }

        fun dismissTip() = dispatcher.dispatch(HelpCommandIds.TIP_DISMISS)

        fun finishWelcome() = dispatcher.dispatch(HelpCommandIds.WELCOME_FINISH)
    }
