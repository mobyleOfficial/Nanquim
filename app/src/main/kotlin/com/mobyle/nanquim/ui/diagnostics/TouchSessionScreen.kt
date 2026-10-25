package com.mobyle.nanquim.ui.diagnostics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.TouchEvent
import com.mobyle.nanquim.canvas.input.TouchSessionSet
import com.mobyle.nanquim.diagnostics.TouchSessionRecorder
import com.mobyle.nanquim.diagnostics.TouchSessionView
import com.mobyle.nanquim.ui.common.ScreenHeader

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TouchSessionScreen(
    recorder: TouchSessionRecorder,
    xdpi: Float,
    onBack: () -> Unit,
    onRemoveLast: () -> Unit,
    onClear: () -> Unit,
    onExport: (Uri) -> Unit,
) {
    LaunchedEffect(recorder) { recorder.load() }
    var label by rememberSaveable { mutableStateOf(TouchSessionSet.LABEL_PALM) }
    var recordedBy by rememberSaveable { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    val events = remember { mutableListOf<TouchEvent>() }
    var eventCount by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) onExport(uri)
        }
    val currentRecording by rememberUpdatedState(recording)
    val counts = recorder.counts()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.touch_sessions_title), onBack = onBack)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.touch_sessions_hint), style = MaterialTheme.typography.bodyMedium)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    for (each in TouchSessionSet.LABELS) {
                        FilterChip(
                            selected = each == label,
                            enabled = !recording,
                            onClick = { label = each },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.touch_sessions_label_count,
                                        labelName(each),
                                        counts[each] ?: 0,
                                        TARGET_PER_LABEL,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag("touchSessionLabel:$each"),
                        )
                    }
                    OutlinedTextField(
                        value = recordedBy,
                        onValueChange = { recordedBy = it },
                        label = { Text(stringResource(R.string.shape_traces_recorded_by)) },
                        singleLine = true,
                        enabled = !recording,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    Button(
                        onClick = {
                            if (recording) {
                                recorder.add(label, recordedBy, events.toList())
                                events.clear()
                                eventCount = 0
                            }
                            recording = !recording
                        },
                        modifier = Modifier.testTag("touchSessionRecord"),
                    ) {
                        Text(
                            if (recording) {
                                pluralStringResource(R.plurals.touch_sessions_stop, eventCount, eventCount)
                            } else {
                                stringResource(R.string.touch_sessions_record)
                            },
                        )
                    }
                    OutlinedButton(onClick = onRemoveLast, enabled = !recording && recorder.sessions.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_remove_last))
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = !recording && recorder.sessions.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_clear))
                    }
                    Button(onClick = { exportLauncher.launch(EXPORT_FILE_NAME) }, enabled = !recording && recorder.sessions.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_export))
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(16.dp).border(1.dp, MaterialTheme.colorScheme.outline)) {
                AndroidView(
                    factory = { context ->
                        TouchSessionView(context, xdpi, recording = { currentRecording }) { event ->
                            events += event
                            eventCount = events.size
                        }
                    },
                    modifier = Modifier.fillMaxSize().testTag("touchSessionArea"),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.touch_sessions_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.touch_sessions_clear_message, recorder.sessions.size, recorder.sessions.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.shape_traces_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun labelName(label: String): String =
    stringResource(if (label == TouchSessionSet.LABEL_PALM) R.string.touch_sessions_label_palm else R.string.touch_sessions_label_gestures)

private const val TARGET_PER_LABEL = 20
private const val EXPORT_FILE_NAME = "nanquim-touch-sessions.json"
