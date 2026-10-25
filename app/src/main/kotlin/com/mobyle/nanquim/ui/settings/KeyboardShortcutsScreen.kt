package com.mobyle.nanquim.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.command.KeyboardCommandIds
import com.mobyle.nanquim.keyboard.KeyChord
import com.mobyle.nanquim.keyboard.KeyboardInput
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.keyboard.KeyboardShortcuts
import com.mobyle.nanquim.keyboard.ShortcutAction
import com.mobyle.nanquim.ui.common.ScreenHeader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardShortcutsScreen(
    settings: KeyboardShortcutSettings,
    describe: (ShortcutAction) -> String,
    onCommand: (String, JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    val shortcuts = settings.shortcuts
    val actions = (KeyboardShortcuts.DEFAULTS.bindings.values + shortcuts.bindings.values).distinct()
    var adding by remember { mutableStateOf<ShortcutAction?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.keyboard_shortcuts_title), onBack = onBack)
            HorizontalDivider()
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = CONTENT_WIDTH),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.keyboard_shortcuts_hint), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.keyboard_shortcuts_space_pan), modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.keyboard_shortcuts_space_pan_keys), style = MaterialTheme.typography.labelLarge)
                }
                for (action in actions) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(describe(action), modifier = Modifier.weight(1f))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (chord in shortcuts.chordsOf(action)) {
                                InputChip(
                                    selected = false,
                                    onClick = {
                                        onCommand(
                                            KeyboardCommandIds.REMOVE,
                                            JsonObject(mapOf("shortcut" to JsonPrimitive(chord.id))),
                                        )
                                    },
                                    label = { Text(chord.label) },
                                    trailingIcon = { Text("×") },
                                )
                            }
                            TextButton(onClick = { adding = action }) { Text(stringResource(R.string.keyboard_shortcuts_add)) }
                        }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { onCommand(KeyboardCommandIds.RESET, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.keyboard_shortcuts_reset))
                }
            }
        }
    }
    adding?.let { action ->
        ShortcutRecorder(
            title = describe(action),
            shortcuts = shortcuts,
            describe = describe,
            onSave = { chord ->
                onCommand(
                    KeyboardCommandIds.SET,
                    JsonObject(
                        mapOf(
                            "shortcut" to JsonPrimitive(chord.id),
                            "command" to JsonPrimitive(action.commandId),
                            "arguments" to action.arguments,
                        ),
                    ),
                )
                adding = null
            },
            onDismiss = { adding = null },
        )
    }
}

@Composable
private fun ShortcutRecorder(
    title: String,
    shortcuts: KeyboardShortcuts,
    describe: (ShortcutAction) -> String,
    onSave: (KeyChord) -> Unit,
    onDismiss: () -> Unit,
) {
    var chord by remember { mutableStateOf<KeyChord?>(null) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (event.type != KeyEventType.KeyDown ||
                            KeyEvent.isModifierKey(event.key.nativeKeyCode)
                        ) {
                            return@onPreviewKeyEvent false
                        }
                        chord = KeyboardInput.chordOf(native)
                        true
                    },
                contentAlignment = Alignment.Center,
            ) {
                val current = chord
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(current?.label ?: stringResource(R.string.keyboard_shortcuts_press), style = MaterialTheme.typography.titleLarge)
                    current?.let(shortcuts::actionFor)?.let { taken ->
                        Text(stringResource(R.string.keyboard_shortcuts_taken, describe(taken)), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(onClick = { chord?.let(onSave) }, enabled = chord != null) { Text(stringResource(R.string.keyboard_shortcuts_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private val CONTENT_WIDTH = 720.dp
