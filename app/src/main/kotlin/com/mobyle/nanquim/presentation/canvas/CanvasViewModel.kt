package com.mobyle.nanquim.presentation.canvas

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.data.macro.Macros
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.command.AppCommandIds
import com.mobyle.nanquim.domain.command.CanvasCommandIds
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.command.GalleryCommandIds
import com.mobyle.nanquim.domain.command.toCommandArguments
import com.mobyle.nanquim.domain.settings.repositories.QuickMenuRepository
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.presentation.brush.BrushUiState
import com.mobyle.nanquim.presentation.gallery.dispatchGalleryCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Only hands the canvas its application-wide objects and forwards its actions to the commands. The open artwork and the
 * engine outlive this screen (Settings and the brush editor open over it), and drawing is the path where nothing may be
 * collected or allocated per frame, so no canvas state is copied in here.
 */
@HiltViewModel
class CanvasViewModel
    @Inject
    constructor(
        val engine: Engine,
        val session: CanvasSession,
        val brushUi: BrushUiState,
        val quickMenu: QuickMenuRepository,
        val macros: Macros,
        private val dispatcher: CommandDispatcher,
    ) : ViewModel() {
        fun command(
            commandId: String,
            arguments: Map<String, Any?>,
            onResult: (CommandResult) -> Unit,
        ) = dispatcher.dispatch(commandId, arguments.toCommandArguments(), onResult)

        fun openSettings() = dispatcher.dispatch(AppCommandIds.SETTINGS_OPEN)

        fun toggleDebugPanel() = dispatcher.dispatch(CanvasCommandIds.DEBUG_PANEL)

        fun closeArtwork(onFailure: (String) -> Unit) = dispatcher.dispatchGalleryCommand(GalleryCommandIds.CLOSE, emptyMap(), onFailure)
    }
