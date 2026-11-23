package com.mobyle.nanquim.presentation.settings

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.keyboard.ShortcutAction
import com.mobyle.nanquim.domain.settings.repositories.KeyboardShortcutsRepository
import com.mobyle.nanquim.presentation.keyboard.KeyboardInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class KeyboardShortcutsViewModel
    @Inject
    constructor(
        val settings: KeyboardShortcutsRepository,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        /** The name of what a shortcut does: the title of its command, in the screen's language. */
        fun describe(
            action: ShortcutAction,
            resources: Resources,
        ): String = KeyboardInput.describe(action, resources, dispatcher.registry)

        fun command(
            commandId: String,
            arguments: JsonObject,
        ) = dispatcher.dispatch(commandId, arguments)

        fun back() = navigator.back()
    }
