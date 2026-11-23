package com.mobyle.nanquim.presentation.settings

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class GestureSettingsViewModel
    @Inject
    constructor(
        val inputSettings: InputSettingsRepository,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        fun command(
            commandId: String,
            arguments: JsonObject,
        ) = dispatcher.dispatch(commandId, arguments)

        fun back() = navigator.back()
    }
