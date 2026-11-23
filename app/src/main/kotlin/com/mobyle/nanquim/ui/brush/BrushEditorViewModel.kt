package com.mobyle.nanquim.ui.brush

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.brush.BrushEditorSession
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.InputSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/** The edit itself lives in [BrushUiState.editor], so the brush commands (and the canvas behind) see the same one. */
@HiltViewModel
class BrushEditorViewModel
    @Inject
    constructor(
        private val brushUi: BrushUiState,
        private val inputSettings: InputSettings,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        val editor: BrushEditorSession? get() = brushUi.editor

        val stylusRollReported: Boolean get() = inputSettings.stylusRollReported

        fun command(
            commandId: String,
            arguments: JsonObject,
        ) = dispatcher.dispatch(commandId, arguments)

        /** Nothing to edit (the edit ended elsewhere): leave the screen. */
        fun leave() {
            if (navigator.current == AppDestination.BrushEditor) navigator.back()
        }
    }
