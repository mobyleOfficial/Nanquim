package com.mobyle.nanquim.ui.settings

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.settings.InputSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class GestureSettingsViewModel
    @Inject
    constructor(
        val inputSettings: InputSettings,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        fun command(
            commandId: String,
            arguments: JsonObject,
        ) = dispatcher.dispatch(commandId, arguments)

        fun back() = navigator.back()
    }
