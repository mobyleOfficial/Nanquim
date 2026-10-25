package com.mobyle.nanquim.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.canvas.transform.TransformHandle
import com.mobyle.nanquim.canvas.transform.TransformMode
import com.mobyle.nanquim.command.TransformCommandIds
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.TransformFilter
import java.util.Locale

@Composable
fun CloneMarker(session: CanvasSession) {
    if (session.canvasTool != CanvasTool.Clone) return
    val source = session.cloneSourcePoint ?: return

    @Suppress("UNUSED_VARIABLE")
    val version = session.viewport.cameraVersion
    val point = session.viewport.canvasToScreen()?.apply(source) ?: return
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(point.x.toFloat(), point.y.toFloat())
        val radius = CLONE_MARKER.dp.toPx()
        val width = 2.dp.toPx()
        drawCircle(Color.White, radius, center, style = Stroke(width * 2))
        drawCircle(Color.Black, radius, center, style = Stroke(width))
        drawLine(Color.Black, Offset(center.x - radius * 0.6f, center.y), Offset(center.x + radius * 0.6f, center.y), width)
        drawLine(Color.Black, Offset(center.x, center.y - radius * 0.6f), Offset(center.x, center.y + radius * 0.6f), width)
    }
}

@Composable
fun CloneToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice(R.string.clone_source_composite, session.cloneFromComposite) {
                    onCommand(TransformCommandIds.CLONE_OPTIONS, mapOf("source" to "composite"))
                }
                Choice(R.string.clone_source_layer, !session.cloneFromComposite) {
                    onCommand(TransformCommandIds.CLONE_OPTIONS, mapOf("source" to "layer"))
                }
                Choice(R.string.clone_aligned, session.cloneAligned) {
                    onCommand(TransformCommandIds.CLONE_OPTIONS, mapOf("aligned" to !session.cloneAligned))
                }
            }
            if (session.cloneSourcePoint == null) Text(stringResource(R.string.clone_hint), Modifier.padding(4.dp))
        }
    }
}

@Composable
fun TransformOverlay(session: CanvasSession) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.transformVersion + session.viewport.cameraVersion
    val editor = session.transformEditor ?: return
    val state = editor.state ?: return
    val transform = session.viewport.canvasToScreen() ?: return
    val zoom = session.viewport.camera?.zoom ?: 1.0
    val handles = editor.handles(zoom)
    val grid = state.warpGrid?.map(transform::apply)
    val quad = state.targetQuad.map(transform::apply)
    Canvas(Modifier.fillMaxSize()) {
        val width = 1.5.dp.toPx()

        fun line(
            points: List<Point>,
            closed: Boolean,
        ) {
            if (points.size < 2) return
            val path =
                Path().apply {
                    moveTo(points[0].x.toFloat(), points[0].y.toFloat())
                    for (point in points.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
                    if (closed) close()
                }
            drawPath(path, Color.White, style = Stroke(width * 2))
            drawPath(path, Color(0xFF2962FF), style = Stroke(width))
        }
        if (grid != null) {
            val size = state.warpSize
            for (row in 0 until size) line((0 until size).map { grid[row * size + it] }, closed = false)
            for (column in 0 until size) line((0 until size).map { grid[it * size + column] }, closed = false)
        } else {
            line(quad, closed = true)
        }
        val radius = HANDLE.dp.toPx()
        for (handle in handles) {
            val point = transform.apply(handle.point)
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            when (handle) {
                is TransformHandle.Rotation -> {
                    val top =
                        transform.apply(
                            Point(
                                (state.targetQuad[0].x + state.targetQuad[1].x) / 2,
                                (state.targetQuad[0].y + state.targetQuad[1].y) / 2,
                            ),
                        )
                    drawLine(Color(0xFF2962FF), Offset(top.x.toFloat(), top.y.toFloat()), center, width)
                    drawCircle(Color(0xFF2962FF), radius, center)
                    drawCircle(Color.White, radius * 0.5f, center)
                }
                is TransformHandle.Edge -> {
                    drawCircle(Color.White, radius * 0.8f, center)
                    drawCircle(Color(0xFF2962FF), radius * 0.8f, center, style = Stroke(width))
                }
                else -> {
                    drawRect(Color.White, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
                    drawRect(
                        Color(0xFF2962FF),
                        Offset(center.x - radius, center.y - radius),
                        Size(radius * 2, radius * 2),
                        style = Stroke(width),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransformToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.transformVersion
    val editor = session.transformEditor ?: return
    var numericOpen by remember { mutableStateOf(false) }
    Surface(modifier = modifier.widthIn(max = BAR_WIDTH), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!session.transformReady) {
                Text(stringResource(R.string.transform_lifting), Modifier.padding(8.dp))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((mode, label) in MODE_LABELS) {
                    Choice(label, editor.mode == mode) {
                        onCommand(
                            TransformCommandIds.SET_MODE,
                            mapOf("mode" to mode.name.lowercase(Locale.ROOT)),
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                Action(R.string.transform_flip_horizontal) { onCommand(TransformCommandIds.FLIP_HORIZONTAL, emptyMap()) }
                Action(R.string.transform_flip_vertical) { onCommand(TransformCommandIds.FLIP_VERTICAL, emptyMap()) }
                Action(R.string.transform_rotate_45) { onCommand(TransformCommandIds.ROTATE_45, emptyMap()) }
                Action(R.string.transform_fit) { onCommand(TransformCommandIds.FIT, emptyMap()) }
                Action(R.string.transform_reset) { onCommand(TransformCommandIds.RESET, emptyMap()) }
                val filterLabel =
                    when (editor.filter) {
                        TransformFilter.Nearest -> R.string.transform_filter_nearest
                        TransformFilter.Bilinear -> R.string.transform_filter_bilinear
                        TransformFilter.Bicubic -> R.string.transform_filter_bicubic
                    }
                Action(filterLabel) {
                    val next = TransformFilter.entries[(editor.filter.ordinal + 1) % TransformFilter.entries.size]
                    onCommand(TransformCommandIds.SET_FILTER, mapOf("filter" to next.name.lowercase(Locale.ROOT)))
                }
                Choice(R.string.transform_magnetics, editor.magnetics) {
                    onCommand(TransformCommandIds.SET_MAGNETICS, mapOf("enabled" to !editor.magnetics))
                }
                if (editor.mode != TransformMode.Warp) {
                    Choice(R.string.transform_fitted_box, editor.fitted) {
                        onCommand(TransformCommandIds.SET_FITTED, mapOf("enabled" to !editor.fitted))
                    }
                    Action(R.string.transform_numeric) { numericOpen = true }
                } else {
                    val size = editor.state?.warpSize ?: 4
                    Action(R.string.transform_grid_smaller) { onCommand(TransformCommandIds.SET_WARP_SIZE, mapOf("size" to size - 1)) }
                    Text(stringResource(R.string.transform_grid_size, size), Modifier.align(Alignment.CenterVertically))
                    Action(R.string.transform_grid_larger) { onCommand(TransformCommandIds.SET_WARP_SIZE, mapOf("size" to size + 1)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onCommand(TransformCommandIds.CANCEL, emptyMap()) },
                ) { Text(stringResource(R.string.transform_cancel)) }
                FilledTonalButton(
                    onClick = { onCommand(TransformCommandIds.COMMIT, emptyMap()) },
                ) { Text(stringResource(R.string.transform_done)) }
            }
        }
    }
    if (numericOpen) {
        NumericDialog(editor.numeric(), onDismiss = { numericOpen = false }) { values ->
            numericOpen = false
            onCommand(
                TransformCommandIds.SET_NUMERIC,
                mapOf("x" to values[0], "y" to values[1], "width" to values[2], "height" to values[3], "rotation" to values[4]),
            )
        }
    }
}

@Composable
private fun NumericDialog(
    initial: DoubleArray?,
    onDismiss: () -> Unit,
    onConfirm: (List<Double>) -> Unit,
) {
    val labels =
        listOf(
            R.string.transform_numeric_x,
            R.string.transform_numeric_y,
            R.string.transform_numeric_width,
            R.string.transform_numeric_height,
            R.string.transform_numeric_rotation,
        )
    val fields =
        remember {
            mutableStateListOf(
                *labels.indices.map { String.format(Locale.ROOT, "%.1f", initial?.getOrNull(it) ?: 0.0) }.toTypedArray(),
            )
        }
    val values = fields.map { it.toDoubleOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transform_numeric)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label ->
                    OutlinedTextField(
                        value = fields[index],
                        onValueChange = { fields[index] = it },
                        label = { Text(stringResource(label)) },
                        singleLine = true,
                        isError = values[index] == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = values.all { it != null } && values[2]!! > 0 && values[3]!! > 0,
                onClick = { onConfirm(values.map { it!! }) },
            ) {
                Text(stringResource(R.string.selection_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.transform_cancel)) } },
    )
}

@Composable
private fun Choice(
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    if (selected) {
        FilledTonalButton(onClick = onClick) { Text(stringResource(labelRes)) }
    } else {
        TextButton(onClick = onClick) { Text(stringResource(labelRes)) }
    }
}

@Composable
private fun Action(
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) = TextButton(onClick = onClick) { Text(stringResource(labelRes)) }

private val MODE_LABELS =
    listOf(
        TransformMode.Freeform to R.string.transform_mode_freeform,
        TransformMode.Uniform to R.string.transform_mode_uniform,
        TransformMode.Distort to R.string.transform_mode_distort,
        TransformMode.Warp to R.string.transform_mode_warp,
    )

private const val HANDLE = 7f
private const val CLONE_MARKER = 18f
private val BAR_WIDTH = 760.dp
