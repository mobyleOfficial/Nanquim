package com.mobyle.nanquim.ui.settings

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.keyboard.KeyboardInput
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.keyboard.ShortcutAction
import com.mobyle.nanquim.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

@HiltViewModel
class KeyboardShortcutsViewModel
    @Inject
    constructor(
        val settings: KeyboardShortcutSettings,
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
