package com.mobyle.nanquim.ui.canvas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.command.CanvasInfoCommandIds
import com.mobyle.nanquim.command.CanvasSizeCommandIds
import com.mobyle.nanquim.command.MacroCommandIds
import com.mobyle.nanquim.command.ColorProfileCommandIds
import com.mobyle.nanquim.document.layer.CanvasAnchor
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import kotlin.math.roundToInt

@Composable
fun CropOverlay(session: CanvasSession) {
    if (session.canvasTool != CanvasTool.Crop) return
    val box = session.cropBox ?: return

    @Suppress("UNUSED_VARIABLE")
    val version = session.viewport.cameraVersion
    val transform = session.viewport.canvasToScreen() ?: return
    val corners = box.corners.map(transform::apply)
    Canvas(Modifier.fillMaxSize()) {
        fun Path.polygon(points: List<Point>) {
            moveTo(points[0].x.toFloat(), points[0].y.toFloat())
            for (point in points.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
            close()
        }
        val outside =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(
                    androidx.compose.ui.geometry
                        .Rect(0f, 0f, size.width, size.height),
                )
                polygon(corners)
            }
        drawPath(outside, Color.Black.copy(alpha = DIM))
        val outline = Path().apply { polygon(corners) }
        drawPath(outline, Color.White, style = Stroke(2.dp.toPx()))
        val handle = HANDLE.dp.toPx()
        for (corner in corners) {
            drawRect(Color.White, Offset(corner.x.toFloat() - handle, corner.y.toFloat() - handle), Size(handle * 2, handle * 2))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CropToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = session.document ?: return
    var sizeOpen by remember { mutableStateOf(false) }
    var depthOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    val box = session.cropBox
    Surface(modifier = modifier.widthIn(max = BAR_WIDTH), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (box != null) {
                Text(stringResource(R.string.canvas_crop_size, (box.right - box.left).roundToInt(), (box.bottom - box.top).roundToInt()))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                FilledTonalButton(onClick = {
                    val crop = session.cropBox ?: return@FilledTonalButton
                    onCommand(
                        CanvasSizeCommandIds.CROP,
                        mapOf(
                            "x" to crop.left.roundToInt(),
                            "y" to crop.top.roundToInt(),
                            "width" to (crop.right - crop.left).roundToInt(),
                            "height" to (crop.bottom - crop.top).roundToInt(),
                        ),
                    )
                }) { Text(stringResource(R.string.canvas_crop)) }
                TextButton(onClick = { sizeOpen = true }) { Text(stringResource(R.string.canvas_size)) }
                if (session.artwork != null) {
                    TextButton(onClick = { depthOpen = true }) { Text(stringResource(R.string.canvas_color_depth)) }
                    TextButton(onClick = { profileOpen = true }) { Text(stringResource(R.string.canvas_color_profile)) }
                }
                TextButton(onClick = {
                    onCommand(CanvasSizeCommandIds.ROTATE, mapOf("quarterTurns" to 3))
                }) { Text(stringResource(R.string.canvas_rotate_left)) }
                TextButton(onClick = {
                    onCommand(CanvasSizeCommandIds.ROTATE, mapOf("quarterTurns" to 1))
                }) { Text(stringResource(R.string.canvas_rotate_right)) }
                TextButton(onClick = {
                    onCommand(CanvasSizeCommandIds.FLIP_HORIZONTAL, emptyMap())
                }) { Text(stringResource(R.string.canvas_flip_horizontal)) }
                TextButton(
                    onClick = { onCommand(CanvasSizeCommandIds.FLIP_VERTICAL, emptyMap()) },
                ) { Text(stringResource(R.string.canvas_flip_vertical)) }
                if (session.artwork != null) {
                    TextButton(onClick = { onCommand(CanvasInfoCommandIds.SHOW, mapOf("visible" to true)) }) {
                        Text(stringResource(R.string.canvas_info_title))
                    }
                    TextButton(onClick = { onCommand(MacroCommandIds.PANEL, mapOf("visible" to true)) }) {
                        Text(stringResource(R.string.macros_title))
                    }
                }
            }
        }
    }
    if (sizeOpen) {
        CanvasSizeDialog(
            width = document.widthPx,
            height = document.heightPx,
            dpi = session.artwork?.info?.dpi,
            layerLimit = session::layerLimitFor,
            onDismiss = { sizeOpen = false },
        ) { arguments ->
            sizeOpen = false
            onCommand(CanvasSizeCommandIds.RESIZE, arguments)
        }
    }
    if (depthOpen) {
        ColorDepthDialog(
            current = document.pixelFormat,
            layerCount = document.layerCount,
            layerLimit = { format -> session.layerLimitFor(document.widthPx, document.heightPx, format) },
            onDismiss = { depthOpen = false },
        ) { format ->
            depthOpen = false
            onCommand(CanvasSizeCommandIds.COLOR_DEPTH, mapOf("bitsPerChannel" to format.bitsPerChannel))
        }
    }
    if (profileOpen) {
        ColorProfileDialog(
            session = session,
            onCommand = onCommand,
            onDismiss = { profileOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorProfileDialog(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentName = session.colorProfile?.name ?: NanquimManifest.SRGB_PROFILE_NAME
    val profiles = session.colorProfiles.documentProfiles
    val current = profiles.firstOrNull { it.name == currentName }
    var selectedId by remember { mutableStateOf(current?.id ?: ColorProfileLibrary.SRGB_ID) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onCommand(ColorProfileCommandIds.IMPORT, mapOf("uri" to uri.toString()))
        }
    val changed = selectedId != current?.id

    fun run(command: String) {
        onDismiss()
        onCommand(command, mapOf("profileId" to selectedId))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_color_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.canvas_color_profile_current, currentName))
                Column(Modifier.heightIn(max = PROFILE_LIST_HEIGHT).verticalScroll(rememberScrollState())) {
                    for (profile in profiles) {
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = selectedId == profile.id, onClick = { selectedId = profile.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == profile.id, onClick = null)
                            Text(profile.name, Modifier.padding(start = 8.dp))
                        }
                    }
                }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/vnd.iccprofile", "*/*")) }) {
                    Text(stringResource(R.string.canvas_color_profile_import))
                }
                Text(stringResource(R.string.canvas_color_profile_explanation))
                Text(stringResource(R.string.canvas_cmyk_simulation), style = MaterialTheme.typography.titleSmall)
                val cmykName = session.cmykProfile?.name
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = cmykName == null,
                        onClick = { onCommand(ColorProfileCommandIds.SET_CMYK, emptyMap()) },
                        label = { Text(stringResource(R.string.canvas_cmyk_off)) },
                    )
                    for (profile in session.colorProfiles.printProfiles) {
                        FilterChip(
                            selected = cmykName == profile.name,
                            onClick = { onCommand(ColorProfileCommandIds.SET_CMYK, mapOf("profileId" to profile.id)) },
                            label = { Text(profile.name) },
                        )
                    }
                }
                Text(stringResource(R.string.canvas_cmyk_explanation), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { run(ColorProfileCommandIds.ASSIGN) }, enabled = changed) {
                    Text(stringResource(R.string.canvas_assign_profile))
                }
                TextButton(onClick = { run(ColorProfileCommandIds.CONVERT) }, enabled = changed) {
                    Text(stringResource(R.string.canvas_convert_profile))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private val PROFILE_LIST_HEIGHT = 240.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorDepthDialog(
    current: PixelFormat,
    layerCount: Int,
    layerLimit: (PixelFormat) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (PixelFormat) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    val limit = layerLimit(selected)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_color_depth)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (format in PixelFormat.entries) {
                        FilterChip(
                            selected = selected == format,
                            onClick = { selected = format },
                            label = { Text(pluralStringResource(R.plurals.new_canvas_bits, format.bitsPerChannel, format.bitsPerChannel)) },
                        )
                    }
                }
                Text(stringResource(R.string.canvas_color_depth_explanation))
                if (selected != current) {
                    Text(
                        if (layerCount > limit) {
                            stringResource(R.string.canvas_color_depth_too_many_layers, layerCount, limit)
                        } else {
                            stringResource(R.string.canvas_color_depth_history)
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }, enabled = selected != current && layerCount <= limit) {
                Text(stringResource(R.string.canvas_color_depth_convert))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun CanvasSizeDialog(
    width: Int,
    height: Int,
    dpi: Double?,
    layerLimit: (Int, Int) -> Int,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit,
) {
    var widthText by remember { mutableStateOf(width.toString()) }
    var heightText by remember { mutableStateOf(height.toString()) }
    var dpiText by remember { mutableStateOf(dpi?.let { "%.0f".format(it) } ?: "") }
    var lockAspect by remember { mutableStateOf(true) }
    var resample by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(CanvasAnchor.Center) }
    val newWidth = widthText.toIntOrNull()
    val newHeight = heightText.toIntOrNull()
    val valid = newWidth != null && newHeight != null && newWidth in 1..MAXIMUM_SIDE && newHeight in 1..MAXIMUM_SIDE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.canvas_size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = widthText,
                    onValueChange = { text ->
                        widthText = text
                        val value = text.toIntOrNull()
                        if (lockAspect && value != null &&
                            width > 0
                        ) {
                            heightText = (value.toDouble() * height / width).roundToInt().coerceAtLeast(1).toString()
                        }
                    },
                    label = { Text(stringResource(R.string.canvas_size_width)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { text ->
                        heightText = text
                        val value = text.toIntOrNull()
                        if (lockAspect && value != null &&
                            height > 0
                        ) {
                            widthText = (value.toDouble() * width / height).roundToInt().coerceAtLeast(1).toString()
                        }
                    },
                    label = { Text(stringResource(R.string.canvas_size_height)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = dpiText,
                    onValueChange = { dpiText = it },
                    label = { Text(stringResource(R.string.canvas_size_dpi)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(lockAspect, { lockAspect = it })
                    Text(stringResource(R.string.canvas_size_lock_aspect))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(resample, { resample = it })
                    Text(stringResource(R.string.canvas_size_resample))
                }
                if (!resample) {
                    Text(stringResource(R.string.canvas_size_anchor))
                    for (row in 0..2) {
                        Row {
                            for (column in 0..2) {
                                val cell = CanvasAnchor.entries.first { it.horizontal == column && it.vertical == row }
                                val selected = cell == anchor
                                TextButton(
                                    onClick = { anchor = cell },
                                    modifier = Modifier.size(ANCHOR_CELL),
                                ) { Text(if (selected) "●" else "○") }
                            }
                        }
                    }
                }
                if (valid) Text(stringResource(R.string.canvas_size_layer_limit, layerLimit(newWidth!!, newHeight!!)))
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val arguments =
                    mutableMapOf<String, Any>(
                        "width" to newWidth!!,
                        "height" to newHeight!!,
                        "anchor" to anchor.name.replaceFirstChar { it.lowercase() },
                        "resample" to resample,
                    )
                dpiText.toDoubleOrNull()?.takeIf { it > 0 }?.let { arguments["dpi"] = it }
                onConfirm(arguments)
            }) { Text(stringResource(R.string.selection_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transform_cancel)) } },
    )
}

private const val DIM = 0.45f
private const val HANDLE = 7f
private const val MAXIMUM_SIDE = 16384
private val BAR_WIDTH = 760.dp
private val ANCHOR_CELL = 44.dp
