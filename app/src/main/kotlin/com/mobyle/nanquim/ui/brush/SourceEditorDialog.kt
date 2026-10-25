package com.mobyle.nanquim.ui.brush

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushEditorSession
import com.mobyle.nanquim.brush.TextureSlot
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.document.brush.BrushDocument
import kotlin.math.roundToInt

private val PREVIEW_SIZE = 280.dp
private const val MINIMUM_GRAIN_SCALE = 0.05f
private const val MAXIMUM_GRAIN_SCALE = 16f
private const val TWO_FINGER_TAP_MILLIS = 250L

@Composable
fun SourceEditorDialog(
    slot: TextureSlot,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
    onDismiss: () -> Unit,
) {
    val slotName = slot.name.lowercase()
    val grain = slot == TextureSlot.Grain
    val bytes = editor.textureBytes(slot)
    val image = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val invertPath = if (grain) "grain.invert" else "shape.invert"
    val inverted = document.boolean(invertPath) == true
    val scale = (document.number("grain.scale") ?: 1.0).toFloat()
    val slotArgument = "slot" to JsonPrimitive(slotName)

    fun command(
        id: String,
        vararg arguments: Pair<String, JsonPrimitive>,
    ) = onCommand(id, JsonObject(mapOf(slotArgument, *arguments)))

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) command(BrushCommandIds.EDITOR_IMPORT_TEXTURE, "uri" to JsonPrimitive(uri.toString()))
        }
    var seamOverlap by remember { mutableFloatStateOf(0.25f) }
    var patchScale by remember { mutableFloatStateOf(0.25f) }
    var maskHardness by remember { mutableFloatStateOf(0.5f) }
    var mirroring by remember { mutableStateOf(false) }
    var ignoreLowBands by remember { mutableStateOf(false) }
    var pyramidBlending by remember { mutableStateOf(false) }
    var libraryOpen by remember { mutableStateOf(false) }
    val groups by editor.libraryGroups.collectAsState(initial = emptyList())
    val title = stringResource(R.string.brush_source_title)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Box(
                    Modifier
                        .size(PREVIEW_SIZE)
                        .background(Color.Black)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .semantics { contentDescription = title }
                        .pointerInput(slot, scale) {
                            detectTransformGestures { _, _, zoom, _ ->
                                if (grain && zoom != 1f) {
                                    val next = (scale * zoom).coerceIn(MINIMUM_GRAIN_SCALE, MAXIMUM_GRAIN_SCALE)
                                    onCommand(
                                        BrushCommandIds.EDITOR_SET_VALUE,
                                        JsonObject(mapOf("path" to JsonPrimitive("grain.scale"), "value" to JsonPrimitive(next))),
                                    )
                                }
                            }
                        }.pointerInput(slot, inverted) {
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                var most = 1
                                var end = first.uptimeMillis
                                while (true) {
                                    val event = awaitPointerEvent()
                                    most = maxOf(most, event.changes.count { it.pressed })
                                    end = event.changes.maxOf { it.uptimeMillis }
                                    if (event.changes.none { it.pressed }) break
                                }
                                val quick = end - first.uptimeMillis < TWO_FINGER_TAP_MILLIS
                                if (most == 2 && quick) {
                                    onCommand(
                                        BrushCommandIds.EDITOR_SET_VALUE,
                                        JsonObject(mapOf("path" to JsonPrimitive(invertPath), "value" to JsonPrimitive(!inverted))),
                                    )
                                }
                            }
                        },
                ) {
                    if (image != null) {
                        Canvas(Modifier.size(PREVIEW_SIZE)) {
                            val tile = if (grain) (size.width / 2 / scale).coerceAtLeast(4f) else size.width
                            clipRect {
                                var y = 0f
                                while (y < size.height) {
                                    var x = 0f
                                    while (x < size.width) {
                                        drawImage(
                                            image,
                                            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                                            dstSize = IntSize(tile.roundToInt() + 1, tile.roundToInt() + 1),
                                            filterQuality = FilterQuality.Low,
                                            colorFilter =
                                                if (inverted) {
                                                    androidx.compose.ui.graphics.ColorFilter
                                                        .colorMatrix(INVERT)
                                                } else {
                                                    null
                                                },
                                        )
                                        x += tile
                                    }
                                    y += tile
                                }
                            }
                        }
                    }
                }
                Text(stringResource(R.string.brush_source_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picker.launch("image/*") }) { Text(stringResource(R.string.brush_texture_import)) }
                    OutlinedButton(
                        onClick = { command(BrushCommandIds.EDITOR_PASTE_TEXTURE) },
                    ) { Text(stringResource(R.string.brush_source_paste)) }
                    Box {
                        OutlinedButton(onClick = { libraryOpen = true }) { Text(stringResource(R.string.brush_source_library)) }
                        DropdownMenu(expanded = libraryOpen, onDismissRequest = { libraryOpen = false }) {
                            for (entry in groups.flatMap { it.brushes }.filter { it.brushId != editor.brushId }) {
                                DropdownMenuItem(
                                    text = { Text(entry.name) },
                                    onClick = {
                                        libraryOpen = false
                                        command(BrushCommandIds.EDITOR_USE_LIBRARY_TEXTURE, "brushId" to JsonPrimitive(entry.brushId))
                                    },
                                )
                            }
                        }
                    }
                    TextButton(enabled = editor.canUndoTexture(slot), onClick = { command(BrushCommandIds.EDITOR_UNDO_TEXTURE) }) {
                        Text(stringResource(R.string.brush_source_undo))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.brush_field_invert), modifier = Modifier.weight(1f))
                    Switch(
                        checked = inverted,
                        onCheckedChange = {
                            onCommand(
                                BrushCommandIds.EDITOR_SET_VALUE,
                                JsonObject(mapOf("path" to JsonPrimitive(invertPath), "value" to JsonPrimitive(it))),
                            )
                        },
                    )
                }
                if (grain && bytes != null) {
                    Text(stringResource(R.string.brush_auto_repeat), style = MaterialTheme.typography.titleMedium)
                    SliderRow(R.string.brush_auto_repeat_seam_overlap, seamOverlap, 0f..0.5f) { seamOverlap = it }
                    SliderRow(R.string.brush_auto_repeat_patch_scale, patchScale, 0.05f..1f) { patchScale = it }
                    SliderRow(R.string.brush_auto_repeat_mask_hardness, maskHardness, 0f..1f) { maskHardness = it }
                    SwitchRow(R.string.brush_auto_repeat_mirroring, mirroring) { mirroring = it }
                    SwitchRow(R.string.brush_auto_repeat_ignore_low_bands, ignoreLowBands) { ignoreLowBands = it }
                    SwitchRow(R.string.brush_auto_repeat_pyramid, pyramidBlending) { pyramidBlending = it }
                    val progress = editor.autoRepeatProgress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_CANCEL_AUTO_REPEAT, JsonObject(emptyMap())) }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    } else {
                        OutlinedButton(onClick = {
                            command(
                                BrushCommandIds.EDITOR_AUTO_REPEAT,
                                "patchScale" to JsonPrimitive(patchScale),
                                "seamOverlap" to JsonPrimitive(seamOverlap),
                                "maskHardness" to JsonPrimitive(maskHardness),
                                "mirroring" to JsonPrimitive(mirroring),
                                "ignoreLowBands" to JsonPrimitive(ignoreLowBands),
                                "pyramidBlending" to JsonPrimitive(pyramidBlending),
                            )
                        }) { Text(stringResource(R.string.brush_auto_repeat_run)) }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

private val INVERT =
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f),
    )

@Composable
private fun SliderRow(
    labelRes: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val label = stringResource(labelRes)
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text("${(value * 100).roundToInt()}%")
        }
        Slider(value, onChange, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun SwitchRow(
    labelRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val label = stringResource(labelRes)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = label })
    }
}
