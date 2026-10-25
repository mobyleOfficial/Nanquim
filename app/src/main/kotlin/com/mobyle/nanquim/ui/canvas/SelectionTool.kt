package com.mobyle.nanquim.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.selection.PendingSelection
import com.mobyle.nanquim.canvas.selection.SelectionGeometry
import com.mobyle.nanquim.canvas.selection.SelectionMode
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.FillCommandIds
import com.mobyle.nanquim.command.STAMP_SOURCE_SELECTION
import com.mobyle.nanquim.command.SelectionCommandIds
import com.mobyle.nanquim.command.commandName
import com.mobyle.nanquim.document.session.SelectionCombine
import com.mobyle.nanquim.engine.Point
import kotlin.math.roundToInt

@Composable
fun SelectionShapeOverlay(session: CanvasSession) {
    val pending = session.pendingSelection ?: return

    @Suppress("UNUSED_VARIABLE")
    val cameraVersion = session.viewport.cameraVersion
    val transform = session.viewport.canvasToScreen() ?: return
    val outline: List<Point>
    val handles: List<Point>
    when (pending) {
        is PendingSelection.Box -> {
            outline =
                if (pending.ellipse) {
                    SelectionGeometry.ellipse(pending.left, pending.top, pending.right, pending.bottom)
                } else {
                    pending.corners
                }
            handles = pending.corners
        }
        is PendingSelection.Path -> {
            val closed = SelectionGeometry.closedPath(pending.nodes)
            outline = closed.ifEmpty { pending.nodes.map { it.point } }
            handles = pending.nodes.filter { it.corner }.map { it.point } + listOfNotNull(pending.nodes.firstOrNull()?.point)
        }
    }
    val screen = outline.map(transform::apply)
    val screenHandles = handles.map(transform::apply)
    Canvas(Modifier.fillMaxSize()) {
        if (screen.size >= 2) {
            val path =
                Path().apply {
                    moveTo(screen[0].x.toFloat(), screen[0].y.toFloat())
                    for (point in screen.drop(1)) lineTo(point.x.toFloat(), point.y.toFloat())
                    if (screen.size >= 3) close()
                }
            val width = 1.5.dp.toPx()
            val dash = DASH.dp.toPx()
            drawPath(path, Color.White, style = Stroke(width))
            drawPath(path, Color.Black, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))))
        }
        val radius = HANDLE_RADIUS.dp.toPx()
        screenHandles.forEachIndexed { index, point ->
            val first = pending is PendingSelection.Path && index == screenHandles.lastIndex
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            drawCircle(Color.White, if (first) radius * 1.5f else radius, center)
            drawCircle(Color.Black, if (first) radius * 1.5f else radius, center, style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
fun FillOverlay(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    session.colorDropPoint?.let { point ->
        val color = session.primaryColor
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(point.x.toFloat(), point.y.toFloat())
            drawCircle(Color.Black, DROP_RADIUS.dp.toPx() + 2.dp.toPx(), center)
            drawCircle(Color(color.red, color.green, color.blue), DROP_RADIUS.dp.toPx(), center)
        }
    }
    session.thresholdIndicator?.let { indicator ->
        Box(Modifier.fillMaxSize()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 6.dp,
                modifier =
                    Modifier.offset {
                        IntOffset(
                            indicator.screenX.roundToInt() - INDICATOR_OFFSET.dp.roundToPx(),
                            indicator.screenY.roundToInt() - 3 * INDICATOR_OFFSET.dp.roundToPx(),
                        )
                    },
            ) {
                Text(stringResource(R.string.fill_threshold, (indicator.threshold * 100).roundToInt()), Modifier.padding(8.dp))
            }
        }
    }
    if (session.continueFilling) {
        Box(Modifier.fillMaxSize()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.fill_continue))
                    TextButton(
                        onClick = { onCommand(FillCommandIds.STOP_CONTINUE, emptyMap()) },
                    ) { Text(stringResource(R.string.fill_continue_done)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectionToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var featherOpen by remember { mutableStateOf(false) }
    var featherRadius by remember { mutableFloatStateOf(DEFAULT_FEATHER) }
    var loadOpen by remember { mutableStateOf(false) }
    val document = session.document
    val hasSelection = document?.selectionId != null
    Surface(modifier = modifier.widthIn(max = MAXIMUM_BAR_WIDTH), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                for ((mode, label) in MODE_LABELS) {
                    Choice(label, session.selectionMode == mode) {
                        onCommand(SelectionCommandIds.SET_MODE, mapOf("mode" to mode.commandName()))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                for ((combine, label) in COMBINE_LABELS) {
                    Choice(label, session.selectionCombine == combine) {
                        onCommand(SelectionCommandIds.SET_COMBINE, mapOf("combine" to combine.commandName()))
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                val pending = session.pendingSelection != null
                Action(R.string.selection_apply, enabled = pending) { onCommand(SelectionCommandIds.APPLY_PENDING, emptyMap()) }
                Action(R.string.selection_discard, enabled = pending) { onCommand(SelectionCommandIds.DISCARD_PENDING, emptyMap()) }
                Action(
                    R.string.command_selection_invert,
                    enabled = hasSelection,
                ) { onCommand(DocumentCommandIds.SELECTION_INVERT, emptyMap()) }
                Action(R.string.selection_feather, enabled = hasSelection) { featherOpen = !featherOpen }
                Action(R.string.command_selection_save, enabled = hasSelection) { onCommand(DocumentCommandIds.SELECTION_SAVE, emptyMap()) }
                Box {
                    Action(R.string.selection_load, enabled = document?.savedSelections?.isNotEmpty() == true) { loadOpen = true }
                    DropdownMenu(expanded = loadOpen, onDismissRequest = { loadOpen = false }) {
                        for (saved in document?.savedSelections.orEmpty()) {
                            DropdownMenuItem(
                                text = { Text(saved.name) },
                                onClick = {
                                    loadOpen = false
                                    onCommand(DocumentCommandIds.SELECTION_LOAD, mapOf("selectionId" to saved.id))
                                },
                            )
                        }
                    }
                }
                Action(
                    R.string.command_selection_copy_paste,
                    enabled = true,
                ) { onCommand(DocumentCommandIds.SELECTION_COPY_PASTE, emptyMap()) }
                Action(R.string.command_edit_copy, enabled = true) { onCommand(DocumentCommandIds.EDIT_COPY, emptyMap()) }
                Action(R.string.selection_create_stamp, enabled = true) {
                    onCommand(BrushCommandIds.BRUSH_CREATE_STAMP, mapOf("source" to STAMP_SOURCE_SELECTION))
                }
                Action(R.string.command_edit_cut, enabled = true) { onCommand(DocumentCommandIds.EDIT_CUT, emptyMap()) }
                Action(R.string.command_edit_paste, enabled = session.documentSession?.hasClipboard == true) {
                    onCommand(DocumentCommandIds.EDIT_PASTE, emptyMap())
                }
                Action(R.string.command_selection_fill, enabled = true) { onCommand(SelectionCommandIds.FILL, emptyMap()) }
                Action(R.string.command_selection_all, enabled = true) { onCommand(DocumentCommandIds.SELECTION_ALL, emptyMap()) }
                Action(
                    R.string.command_selection_clear,
                    enabled = hasSelection,
                ) { onCommand(DocumentCommandIds.SELECTION_CLEAR, emptyMap()) }
            }
            if (featherOpen && hasSelection) {
                FlowRow(
                    verticalArrangement = Arrangement.Center,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Text(stringResource(R.string.selection_feather_radius, featherRadius.roundToInt()))
                    Slider(
                        featherRadius,
                        { featherRadius = it },
                        valueRange = 0f..MAXIMUM_FEATHER,
                        modifier = Modifier.width(FEATHER_SLIDER_WIDTH),
                    )
                    FilledTonalButton(onClick = {
                        featherOpen = false
                        onCommand(SelectionCommandIds.FEATHER, mapOf("radius" to featherRadius.roundToInt()))
                    }) { Text(stringResource(R.string.selection_apply)) }
                }
            }
        }
    }
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
    enabled: Boolean,
    onClick: () -> Unit,
) = TextButton(onClick = onClick, enabled = enabled) { Text(stringResource(labelRes)) }

private val MODE_LABELS =
    listOf(
        SelectionMode.Automatic to R.string.selection_mode_automatic,
        SelectionMode.Freehand to R.string.selection_mode_freehand,
        SelectionMode.Rectangle to R.string.selection_mode_rectangle,
        SelectionMode.Ellipse to R.string.selection_mode_ellipse,
        SelectionMode.Paint to R.string.selection_mode_paint,
    )

private val COMBINE_LABELS =
    listOf(
        SelectionCombine.Replace to R.string.selection_combine_replace,
        SelectionCombine.Add to R.string.selection_combine_add,
        SelectionCombine.Subtract to R.string.selection_combine_subtract,
        SelectionCombine.Intersect to R.string.selection_combine_intersect,
    )

private const val DASH = 5f
private const val DROP_RADIUS = 14f
private const val INDICATOR_OFFSET = 24
private const val HANDLE_RADIUS = 6f
private const val DEFAULT_FEATHER = 10f
private const val MAXIMUM_FEATHER = 100f
private val FEATHER_SLIDER_WIDTH = 240.dp
private val MAXIMUM_BAR_WIDTH = 720.dp
