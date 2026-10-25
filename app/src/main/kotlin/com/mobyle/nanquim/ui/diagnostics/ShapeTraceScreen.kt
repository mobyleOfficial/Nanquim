package com.mobyle.nanquim.ui.diagnostics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobyle.nanquim.R
import com.mobyle.nanquim.diagnostics.ShapeTraceRecorder
import com.mobyle.nanquim.diagnostics.ShapeTraceView
import com.mobyle.nanquim.ui.common.ScreenHeader
import com.mobyle.nanquim.document.shape.QuickShape
import com.mobyle.nanquim.document.shape.ShapeKind
import com.mobyle.nanquim.document.shape.ShapePoint
import com.mobyle.nanquim.document.shape.ShapeTraceSet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShapeTraceScreen(
    recorder: ShapeTraceRecorder,
    onBack: () -> Unit,
    onRemoveLast: () -> Unit,
    onClear: () -> Unit,
    onExport: (Uri) -> Unit,
) {
    LaunchedEffect(recorder) { recorder.load() }
    var label by rememberSaveable { mutableStateOf(ShapeTraceSet.LABELS.first()) }
    var recordedBy by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<ShapeKind?>(null) }
    var hasResult by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) onExport(uri)
        }
    val currentLabel by rememberUpdatedState(label)
    val currentRecordedBy by rememberUpdatedState(recordedBy)
    val counts = recorder.counts()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.shape_traces_title), onBack = onBack)
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.shape_traces_hint), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (each in ShapeTraceSet.LABELS) {
                        FilterChip(
                            selected = each == label,
                            onClick = { label = each },
                            label = { Text(stringResource(R.string.shape_traces_label_count, shapeName(each), counts[each] ?: 0)) },
                            modifier = Modifier.testTag("shapeTraceLabel:$each"),
                        )
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = recordedBy,
                        onValueChange = { recordedBy = it },
                        label = { Text(stringResource(R.string.shape_traces_recorded_by)) },
                        singleLine = true,
                        modifier = Modifier.widthIn(max = 240.dp),
                    )
                    Text(stringResource(R.string.shape_traces_total, recorder.traces.size, TARGET_COUNT))
                    OutlinedButton(onClick = onRemoveLast, enabled = recorder.traces.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_remove_last))
                    }
                    OutlinedButton(onClick = { confirmClear = true }, enabled = recorder.traces.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_clear))
                    }
                    Button(onClick = { exportLauncher.launch(EXPORT_FILE_NAME) }, enabled = recorder.traces.isNotEmpty()) {
                        Text(stringResource(R.string.shape_traces_export))
                    }
                }
                val resultText =
                    when {
                        !hasResult -> ""
                        result == null -> stringResource(R.string.shape_traces_result_none)
                        else -> stringResource(R.string.shape_traces_result, kindName(result!!))
                    }
                val matches = hasResult && ShapeTraceSet.labelOf(result) == label
                Text(
                    resultText,
                    color = if (matches) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("shapeTraceResult"),
                )
            }
            val inkColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val shapeColor = MaterialTheme.colorScheme.error.toArgb()
            Box(Modifier.fillMaxSize().padding(16.dp).border(1.dp, MaterialTheme.colorScheme.outline)) {
                AndroidView(
                    factory = { context ->
                        ShapeTraceView(context, inkColor, shapeColor) { samples ->
                            recorder.add(currentLabel, currentRecordedBy, samples)
                            val recognized = QuickShape.recognize(samples.map { ShapePoint(it[0], it[1]) }).shape
                            result = recognized?.kind
                            hasResult = true
                            recognized?.outline(OUTLINE_STEP)
                        }
                    },
                    modifier = Modifier.fillMaxSize().testTag("shapeTraceArea"),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.shape_traces_clear_title)) },
            text = { Text(stringResource(R.string.shape_traces_clear_message, recorder.traces.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    hasResult = false
                    onClear()
                }) { Text(stringResource(R.string.shape_traces_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

@Composable
private fun shapeName(label: String): String = ShapeKind.fromLabel(label)?.let { kindName(it) } ?: label

@Composable
private fun kindName(kind: ShapeKind): String =
    stringResource(
        when (kind) {
            ShapeKind.Line -> R.string.shape_kind_line
            ShapeKind.Arc -> R.string.shape_kind_arc
            ShapeKind.Circle -> R.string.shape_kind_circle
            ShapeKind.Ellipse -> R.string.shape_kind_ellipse
            ShapeKind.Rectangle -> R.string.shape_kind_rectangle
            ShapeKind.Polygon -> R.string.shape_kind_polygon
            ShapeKind.Polyline -> R.string.shape_kind_polyline
            ShapeKind.Curve -> R.string.shape_kind_curve
        },
    )

private const val TARGET_COUNT = 500
private const val OUTLINE_STEP = 2.0
private const val EXPORT_FILE_NAME = "nanquim-quickshape-traces.json"
