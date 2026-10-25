package com.mobyle.nanquim.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.CanvasInfoCommandIds
import com.mobyle.nanquim.formats.nanquim.ArtistInfo
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CanvasInfoDialog(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    if (!session.canvasInfoVisible) return
    val open = session.artwork ?: return
    val state = session.document ?: return
    val info = open.info
    var name by remember(open.id) { mutableStateOf(info.artist?.name.orEmpty()) }
    val signature = remember(open.id) { mutableStateListOf<List<Float>>().apply { addAll(info.artist?.signature.orEmpty()) } }
    val close = { onCommand(CanvasInfoCommandIds.SHOW, mapOf("visible" to false)) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.canvas_info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section(R.string.canvas_info_dimensions)
                InfoRow(R.string.canvas_info_size, stringResource(R.string.canvas_info_pixels, state.widthPx, state.heightPx))
                InfoRow(R.string.canvas_info_dpi, info.dpi.roundToInt().toString())
                val centimeters = { pixels: Int -> String.format(Locale.getDefault(), "%.1f", pixels * CENTIMETERS_PER_INCH / info.dpi) }
                InfoRow(
                    R.string.canvas_info_print_size,
                    stringResource(R.string.canvas_info_centimeters, centimeters(state.widthPx), centimeters(state.heightPx)),
                )
                Section(R.string.canvas_info_layers)
                InfoRow(R.string.canvas_info_layer_count, state.layerCount.toString())
                InfoRow(R.string.canvas_info_layer_maximum, session.layerLimitFor(state.widthPx, state.heightPx).toString())
                Section(R.string.canvas_info_color)
                InfoRow(R.string.canvas_info_profile, info.colorProfile?.name ?: "sRGB")
                InfoRow(R.string.canvas_info_depth, stringResource(R.string.canvas_info_bits, state.pixelFormat.bitsPerChannel))
                Section(R.string.canvas_info_video)
                val timelapse = info.timelapse
                InfoRow(
                    R.string.canvas_info_timelapse,
                    when {
                        timelapse == null || !timelapse.enabled -> stringResource(R.string.canvas_info_timelapse_off)
                        timelapse.resolution.longSidePixels == 0 ->
                            stringResource(
                                R.string.canvas_info_timelapse_canvas,
                                codec(timelapse.hevc),
                            )
                        else ->
                            stringResource(
                                R.string.canvas_info_timelapse_size,
                                timelapse.resolution.longSidePixels,
                                timelapse.resolution.shortSidePixels,
                                codec(timelapse.hevc),
                            )
                    },
                )
                Section(R.string.canvas_info_statistics)
                InfoRow(R.string.canvas_info_time, duration(info.timeSpentSeconds))
                InfoRow(R.string.canvas_info_strokes, info.strokeCount.toString())
                val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                InfoRow(R.string.canvas_info_created, dates.format(Date.from(info.createdAt)))
                InfoRow(R.string.canvas_info_modified, dates.format(Date.from(info.modifiedAt)))
                Section(R.string.canvas_info_artwork)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.canvas_info_artist)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    Text(stringResource(R.string.canvas_info_signature), modifier = Modifier.weight(1f))
                    TextButton(onClick = { signature.clear() }, enabled = signature.isNotEmpty()) {
                        Text(stringResource(R.string.canvas_info_signature_clear))
                    }
                }
                SignaturePad(signature)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val strokes = JsonArray(signature.map { stroke -> JsonArray(stroke.map { JsonPrimitive(it) }) })
                onCommand(CanvasInfoCommandIds.SET_ARTIST, mapOf("name" to name, "signature" to strokes))
                close()
            }) { Text(stringResource(R.string.quick_menu_done)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun Section(
    @StringRes titleRes: Int,
) {
    HorizontalDivider()
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun InfoRow(
    @StringRes labelRes: Int,
    value: String,
) {
    Row {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun codec(hevc: Boolean): String = if (hevc) "HEVC" else "H.264"

@Composable
private fun duration(seconds: Long): String =
    stringResource(
        R.string.canvas_info_duration,
        seconds / SECONDS_PER_HOUR,
        seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
        seconds % SECONDS_PER_MINUTE,
    )

@Composable
private fun SignaturePad(strokes: MutableList<List<Float>>) {
    val label = stringResource(R.string.canvas_info_signature)
    val ink = MaterialTheme.colorScheme.onSurface
    var current by remember { mutableStateOf<List<Float>>(emptyList()) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ArtistInfo.SIGNATURE_ASPECT)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                fun normalized(point: Offset) = listOf((point.x / size.width).coerceIn(0f, 1f), (point.y / size.height).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { current = normalized(it) },
                    onDrag = { change, _ -> current = current + normalized(change.position) },
                    onDragEnd = {
                        if (current.size >= 4) strokes += current
                        current = emptyList()
                    },
                    onDragCancel = { current = emptyList() },
                )
            },
    ) {
        for (stroke in strokes + listOf(current)) {
            if (stroke.size < 4) continue
            val path = Path()
            path.moveTo(stroke[0] * size.width, stroke[1] * size.height)
            for (index in 2 until stroke.size step 2) path.lineTo(stroke[index] * size.width, stroke[index + 1] * size.height)
            drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

private const val CENTIMETERS_PER_INCH = 2.54
private const val SECONDS_PER_HOUR = 3600L
private const val SECONDS_PER_MINUTE = 60L
