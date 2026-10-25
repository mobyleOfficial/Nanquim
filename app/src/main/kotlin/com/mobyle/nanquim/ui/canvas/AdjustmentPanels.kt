package com.mobyle.nanquim.ui.canvas

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.adjust.AdjustmentEditor
import com.mobyle.nanquim.canvas.adjust.CurveChannel
import com.mobyle.nanquim.canvas.adjust.CurveEditing
import com.mobyle.nanquim.canvas.adjust.CurvePoint
import com.mobyle.nanquim.canvas.adjust.GradientCategory
import com.mobyle.nanquim.canvas.adjust.GradientEntry
import com.mobyle.nanquim.command.AdjustmentCommandIds
import com.mobyle.nanquim.command.GradientCommandIds
import com.mobyle.nanquim.formats.gradient.GradientDefinition
import com.mobyle.nanquim.formats.gradient.GradientStop
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun ColumnScope.ColorBalanceControls(
    session: CanvasSession,
    editor: AdjustmentEditor,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    var range by remember(editor) { mutableIntStateOf(editor.mainParameter / 3) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
        COLOR_BALANCE_RANGES.forEachIndexed { index, label ->
            Tab(label, range == index) {
                range = index
                onCommand(
                    AdjustmentCommandIds.SELECT_PARAMETER,
                    mapOf("name" to editor.filter.parameterNames[index * 3 + editor.mainParameter % 3]),
                )
            }
        }
    }
    for (axis in 0 until 3) {
        ParameterSlider(session, editor, range * 3 + axis, onCommand, stringResource(COLOR_BALANCE_AXES[axis]))
    }
}

private val CURVE_LABELS =
    mapOf(
        CurveChannel.Master to R.string.adjust_curve_gamma,
        CurveChannel.Red to R.string.adjust_curve_red,
        CurveChannel.Green to R.string.adjust_curve_green,
        CurveChannel.Blue to R.string.adjust_curve_blue,
        CurveChannel.Alpha to R.string.adjust_curve_alpha,
    )

private fun CurveChannel.color(): Color =
    when (this) {
        CurveChannel.Master -> Color(0xFFE0E0E0)
        CurveChannel.Red -> Color(0xFFFF5252)
        CurveChannel.Green -> Color(0xFF69F0AE)
        CurveChannel.Blue -> Color(0xFF448AFF)
        CurveChannel.Alpha -> Color(0xFFBDBDBD)
    }

@Composable
internal fun ColumnScope.CurvesControls(
    session: CanvasSession,
    editor: AdjustmentEditor,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    var channel by remember(editor) { mutableStateOf(editor.selectedCurve) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
        for (entry in CurveChannel.entries) {
            Tab(CURVE_LABELS.getValue(entry), channel == entry) {
                channel = entry
                editor.selectedCurve = entry
            }
        }
    }
    CurveEditorView(session, editor, channel, onCommand, Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun CurveEditorView(
    session: CanvasSession,
    editor: AdjustmentEditor,
    channel: CurveChannel,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    val points = editor.curve(channel)
    val histogram = session.adjustmentHistogram?.of(channel)
    val lineColor = channel.color()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val histogramColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Canvas(
        modifier
            .size(CURVE_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, gridColor, RoundedCornerShape(8.dp))
            .pointerInput(editor, channel) {
                val side = size.width.toFloat()
                val grab = NODE_GRAB.toPx() / side
                awaitEachGesture {
                    val down = awaitFirstDown()

                    fun normalized(position: Offset) = position.x / side to 1f - position.y / side
                    val (startX, startY) = normalized(down.position)
                    var current = editor.curve(channel)
                    var index = CurveEditing.pointNear(current, startX, startY, grab)
                    if (index == null) {
                        val inserted = CurveEditing.insert(current, startX, startY) ?: return@awaitEachGesture
                        current = inserted.first
                        index = inserted.second
                        editor.previewCurve(channel, current)
                        session.adjustmentChanged()
                    }
                    val moving = index
                    var removing = false
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val (x, y) = normalized(change.position)
                        removing =
                            current.size > 2 &&
                            (y < -REMOVE_MARGIN || y > 1f + REMOVE_MARGIN || x < -REMOVE_MARGIN || x > 1f + REMOVE_MARGIN)
                        val shown = if (removing) CurveEditing.remove(current, moving) else CurveEditing.move(current, moving, x, y)
                        if (!removing) current = shown
                        editor.previewCurve(channel, shown)
                        session.adjustmentChanged()
                    } while (true)
                    val final = if (removing) CurveEditing.remove(current, moving) else current
                    onCommand(
                        AdjustmentCommandIds.SET_CURVE,
                        mapOf("curve" to channel.name.lowercase(), "points" to final.map { listOf(it.x, it.y) }),
                    )
                }
            },
    ) {
        val side = size.width
        for (step in 1 until 4) {
            val position = side * step / 4
            drawLine(gridColor, Offset(position, 0f), Offset(position, side), 1f)
            drawLine(gridColor, Offset(0f, position), Offset(side, position), 1f)
        }
        if (histogram != null) {
            val peak = histogram.maxOrNull()?.takeIf { it > 0 } ?: 1
            val barWidth = side / histogram.size
            histogram.forEachIndexed { bin, count ->
                val height = side * sqrt(count.toFloat() / peak)
                drawRect(histogramColor, Offset(bin * barWidth, side - height), Size(barWidth + 0.5f, height))
            }
        }
        drawLine(gridColor, Offset(0f, side), Offset(side, 0f), 1f)
        val path =
            Path().apply {
                for (sample in 0..CURVE_SAMPLES) {
                    val x = sample.toFloat() / CURVE_SAMPLES
                    val y = CurveEditing.evaluate(points, x)
                    if (sample == 0) moveTo(x * side, (1 - y) * side) else lineTo(x * side, (1 - y) * side)
                }
            }
        drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
        for (point in points) {
            val center = Offset(point.x * side, (1 - point.y) * side)
            drawCircle(Color.White, NODE_RADIUS.toPx(), center)
            drawCircle(lineColor, NODE_RADIUS.toPx(), center, style = Stroke(2.dp.toPx()))
        }
    }
}

private val GRADIENT_CATEGORIES = listOf(GradientCategory.Custom) + GradientCategory.entries.filter { it != GradientCategory.Custom }

@Composable
internal fun GradientMapControls(
    session: CanvasSession,
    editor: AdjustmentEditor,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    ParameterSlider(session, editor, 0, onCommand)
    val selectedId = editor.state.gradientId
    val entries = session.gradients.entries
    Column(
        Modifier.heightIn(max = LIBRARY_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (category in GRADIENT_CATEGORIES) {
            val inCategory = entries.filter { it.category == category }
            if (inCategory.isEmpty()) continue
            Text(stringResource(category.labelRes), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (entry in inCategory) {
                    GradientSwatch(entry, selected = entry.id == selectedId) {
                        onCommand(AdjustmentCommandIds.SET_GRADIENT, mapOf("gradientId" to entry.id))
                    }
                }
            }
        }
    }
    val selected = selectedId?.let(session.gradients::entry)
    var editing by remember { mutableStateOf<GradientEntry?>(null) }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onCommand(GradientCommandIds.IMPORT, mapOf("uri" to uri.toString()))
        }
    val newName = stringResource(R.string.gradient_new_name)
    val selectedName = selected?.let { gradientName(it) }.orEmpty()
    val copyName = stringResource(R.string.gradient_copy_name, selectedName)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = {
            val stops = editor.state.gradient?.stops ?: emptyList()
            onCommand(
                GradientCommandIds.CREATE,
                mapOf(
                    "name" to newName,
                    "stops" to stops.map { listOf(it.position, it.red, it.green, it.blue) },
                ),
            )
        }) { Text(stringResource(R.string.gradient_new)) }
        TextButton(enabled = selected != null, onClick = {
            selected?.let { onCommand(GradientCommandIds.DUPLICATE, mapOf("gradientId" to it.id, "name" to copyName)) }
        }) { Text(stringResource(R.string.gradient_duplicate)) }
        TextButton(enabled = selected?.builtIn == false, onClick = { editing = selected }) { Text(stringResource(R.string.gradient_edit)) }
        TextButton(enabled = selected?.builtIn == false, onClick = {
            selected?.let { onCommand(GradientCommandIds.DELETE, mapOf("gradientId" to it.id)) }
        }) { Text(stringResource(R.string.gradient_delete)) }
        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.gradient_import)) }
        TextButton(enabled = selected != null, onClick = {
            selected?.let { onCommand(GradientCommandIds.EXPORT, mapOf("gradientId" to it.id, "name" to selectedName)) }
        }) { Text(stringResource(R.string.gradient_export)) }
    }
    editing?.let { entry ->
        GradientEditorDialog(entry.definition, onDismiss = { editing = null }) { definition ->
            editing = null
            onCommand(
                GradientCommandIds.UPDATE,
                mapOf(
                    "gradientId" to entry.id,
                    "name" to definition.name,
                    "stops" to definition.stops.map { listOf(it.position, it.red, it.green, it.blue) },
                ),
            )
        }
    }
}

@Composable
private fun gradientName(entry: GradientEntry): String = entry.nameRes?.let { stringResource(it) } ?: entry.definition.name

private fun GradientDefinition.brush(): Brush =
    if (stops.size == 1) {
        Brush.horizontalGradient(listOf(stops[0].color(), stops[0].color()))
    } else {
        Brush.horizontalGradient(colorStops = stops.map { it.position to it.color() }.toTypedArray())
    }

private fun GradientStop.color() = Color(red, green, blue)

@Composable
private fun GradientSwatch(
    entry: GradientEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(SWATCH_WIDTH).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SWATCH_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(6.dp),
                ),
        ) {
            Canvas(Modifier.fillMaxWidth().height(SWATCH_HEIGHT)) { drawRect(entry.definition.brush()) }
        }
        Text(gradientName(entry), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun GradientEditorDialog(
    initial: GradientDefinition,
    onDismiss: () -> Unit,
    onSave: (GradientDefinition) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var stops by remember { mutableStateOf(initial.stops) }
    var selected by remember { mutableIntStateOf(0) }
    val current = GradientDefinition(name, stops.sortedBy { it.position })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gradient_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.gradient_name)) },
                    singleLine = true,
                )
                val markerColor = MaterialTheme.colorScheme.onSurface
                val selectedColor = MaterialTheme.colorScheme.primary
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(EDITOR_BAR_HEIGHT)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val position = (down.position.x / size.width).coerceIn(0f, 1f)
                                val grab = MARKER_GRAB.toPx() / size.width
                                val near = stops.indices.minByOrNull { kotlin.math.abs(stops[it].position - position) }
                                if (near != null && kotlin.math.abs(stops[near].position - position) <= grab) {
                                    selected = near
                                } else if (stops.size < GradientDefinition.MAXIMUM_STOPS) {
                                    val color = GradientDefinition(name, stops.sortedBy { it.position }).colorAt(position)
                                    stops = stops + GradientStop(position, color[0], color[1], color[2])
                                    selected = stops.lastIndex
                                }
                            }
                        },
                ) {
                    val barHeight = size.height * 0.6f
                    drawRect(current.brush(), size = Size(size.width, barHeight))
                    stops.forEachIndexed { index, stop ->
                        val x = stop.position * size.width
                        val color = if (index == selected) selectedColor else markerColor
                        val marker =
                            Path().apply {
                                moveTo(x, barHeight)
                                lineTo(x - MARKER_HALF_WIDTH.toPx(), size.height)
                                lineTo(x + MARKER_HALF_WIDTH.toPx(), size.height)
                                close()
                            }
                        drawPath(marker, stop.color())
                        drawPath(marker, color, style = Stroke(if (index == selected) 3.dp.toPx() else 1.dp.toPx()))
                    }
                }
                stops.getOrNull(selected)?.let { stop ->
                    fun update(changed: GradientStop) {
                        stops = stops.toMutableList().apply { set(selected, changed) }
                    }
                    StopSlider(R.string.gradient_stop_position, stop.position) { update(stop.copy(position = it)) }
                    StopSlider(R.string.gradient_stop_red, stop.red) { update(stop.copy(red = it)) }
                    StopSlider(R.string.gradient_stop_green, stop.green) { update(stop.copy(green = it)) }
                    StopSlider(R.string.gradient_stop_blue, stop.blue) { update(stop.copy(blue = it)) }
                    TextButton(enabled = stops.size > 2, onClick = {
                        stops = stops.filterIndexed { index, _ -> index != selected }
                        selected = 0
                    }) { Text(stringResource(R.string.gradient_stop_delete)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(current) },
            ) { Text(stringResource(R.string.gradient_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.adjust_cancel)) } },
    )
}

@Composable
private fun StopSlider(
    @StringRes labelRes: Int,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), Modifier.width(STOP_LABEL_WIDTH), style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, modifier = Modifier.weight(1f))
        Text("${(value * 100).roundToInt()}%", Modifier.width(PERCENT_WIDTH), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Tab(
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

private val CURVE_SIZE = 280.dp
private val NODE_RADIUS = 7.dp
private val NODE_GRAB = 24.dp
private const val REMOVE_MARGIN = 0.12f
private const val CURVE_SAMPLES = 128
private val LIBRARY_HEIGHT = 220.dp
private val SWATCH_WIDTH = 96.dp
private val SWATCH_HEIGHT = 28.dp
private val EDITOR_BAR_HEIGHT = 56.dp
private val MARKER_GRAB = 16.dp
private val MARKER_HALF_WIDTH = 8.dp
private val STOP_LABEL_WIDTH = 96.dp
