package com.mobyle.nanquim.presentation.brush

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.core.navigation.AppDestination
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.domain.brush.BrushEditorSession
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import kotlinx.serialization.json.JsonObject

/** The edit itself lives in [BrushUiState.editor], so the brush commands (and the canvas behind) see the same one. */
class BrushEditorViewModel(
    private val brushUi: BrushUiState,
    private val inputSettings: InputSettingsRepository,
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
