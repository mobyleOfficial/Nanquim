package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.command.MacroCommandIds
import com.mobyle.nanquim.macro.Macros

@Composable
fun MacrosPanel(
    macros: Macros,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    if (!macros.panelVisible) return
    var name by remember { mutableStateOf("") }
    val close = { onCommand(MacroCommandIds.PANEL, mapOf("visible" to false)) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.macros_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.macros_hint), style = MaterialTheme.typography.bodySmall)
                if (macros.macros.isEmpty()) Text(stringResource(R.string.macros_empty))
                for (macro in macros.macros) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(macro.name)
                            Text(
                                pluralStringResource(R.plurals.macros_steps, macro.steps.size, macro.steps.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = {
                            close()
                            onCommand(MacroCommandIds.RUN, mapOf("id" to macro.id))
                        }) { Text(stringResource(R.string.macros_run)) }
                        TextButton(onClick = { onCommand(MacroCommandIds.DELETE, mapOf("id" to macro.id)) }) {
                            Text(stringResource(R.string.macros_delete))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.macros_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onCommand(MacroCommandIds.RECORD_START, mapOf("name" to name.trim()))
                close()
            }, enabled = name.isNotBlank() && macros.recordingName == null) { Text(stringResource(R.string.macros_record)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.quick_menu_done)) } },
    )
}

@Composable
fun MacroRecordingBar(
    macros: Macros,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = macros.recordingName ?: return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.macros_recording, recording, macros.recordedSteps),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = { onCommand(MacroCommandIds.RECORD_STOP, emptyMap()) }) { Text(stringResource(R.string.macros_stop)) }
            TextButton(onClick = { onCommand(MacroCommandIds.RECORD_CANCEL, emptyMap()) }) { Text(stringResource(R.string.dialog_cancel)) }
        }
    }
}
