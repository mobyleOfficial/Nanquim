package com.mobyle.nanquim.ui.brush

import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushEditorSection
import com.mobyle.nanquim.brush.BrushEditorSession
import com.mobyle.nanquim.canvas.input.StrokeInput
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.ui.common.CurveEditor
import com.mobyle.nanquim.document.brush.BrushCurve
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import kotlin.math.PI
import kotlin.math.roundToInt

@Composable
fun BrushEditorScreen(
    editor: BrushEditorSession,
    onCommand: (String, JsonObject) -> Unit,
    stylusRollReported: Boolean = false,
) {
    val document = editor.working.document
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_CANCEL, EMPTY) }) { Text(stringResource(R.string.action_cancel)) }
                Text(
                    stringResource(R.string.brush_editor_title, document.name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                )
                TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_DONE, EMPTY) }) { Text(stringResource(R.string.action_done)) }
            }
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val portrait = maxHeight > maxWidth
                if (portrait) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            SectionList(editor, onCommand)
                            FieldList(editor, document, onCommand, stylusRollReported, Modifier.weight(1f))
                        }
                        HorizontalDivider()
                        PadPanel(editor, onCommand, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        SectionList(editor, onCommand)
                        FieldList(editor, document, onCommand, stylusRollReported, Modifier.weight(1f))
                        PadPanel(editor, onCommand, Modifier.weight(1.3f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionList(
    editor: BrushEditorSession,
    onCommand: (String, JsonObject) -> Unit,
) {
    LazyColumn(Modifier.width(SECTION_COLUMN_WIDTH).fillMaxHeight().padding(8.dp)) {
        items(BrushEditorSection.entries) { section ->
            val selected = section == editor.section
            Text(
                stringResource(sectionTitle(section)),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            RoundedCornerShape(8.dp),
                        ).clickable {
                            onCommand(BrushCommandIds.EDITOR_SHOW_SECTION, JsonObject(mapOf("section" to JsonPrimitive(section.name))))
                        }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun FieldList(
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
    stylusRollReported: Boolean,
    modifier: Modifier,
) {
    LazyColumn(
        modifier.fillMaxHeight().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(editorFields(editor.section, stylusRollReported)) { field ->
            FieldRow(field, editor, document, onCommand)
        }
    }
}

@Composable
private fun PadPanel(
    editor: BrushEditorSession,
    onCommand: (String, JsonObject) -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.brush_pad_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(
                selected = editor.padUsesColor,
                onClick = {
                    onCommand(BrushCommandIds.EDITOR_SET_PAD_COLOR, JsonObject(mapOf("useColor" to JsonPrimitive(!editor.padUsesColor))))
                },
                label = { Text(stringResource(R.string.brush_pad_use_color)) },
            )
            FilterChip(
                selected = editor.padDark,
                onClick = {
                    onCommand(BrushCommandIds.EDITOR_SET_PAD_DARK, JsonObject(mapOf("dark" to JsonPrimitive(!editor.padDark))))
                },
                label = { Text(stringResource(R.string.brush_pad_dark)) },
            )
            TextButton(onClick = { onCommand(BrushCommandIds.EDITOR_CLEAR_PAD, EMPTY) }) {
                Text(stringResource(R.string.brush_pad_clear))
            }
        }
        val sizeLabel = stringResource(R.string.brush_pad_size, editor.padSizePx.roundToInt())
        Text(sizeLabel)
        Slider(
            value = editor.padSizePx,
            onValueChange = {
                onCommand(BrushCommandIds.EDITOR_SET_PAD_SIZE, JsonObject(mapOf("sizePx" to JsonPrimitive(it))))
            },
            valueRange = 1f..BrushEditorSession.MAXIMUM_PAD_SIZE_PX,
            modifier = Modifier.semantics { contentDescription = sizeLabel },
        )
        editor.brushError?.let { error ->
            Text(stringResource(R.string.brush_editor_invalid, error), color = MaterialTheme.colorScheme.error)
        }
        DrawingPad(editor, Modifier.weight(1f, fill = false))
    }
}

private val EMPTY = JsonObject(emptyMap())

private fun setValue(
    onCommand: (String, JsonObject) -> Unit,
    path: String,
    value: JsonElement,
) = onCommand(BrushCommandIds.EDITOR_SET_VALUE, JsonObject(mapOf("path" to JsonPrimitive(path), "value" to value)))

@Composable
private fun FieldRow(
    field: EditorField,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    val label = stringResource(field.labelRes)
    when (field) {
        is Header -> Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        is NumberField -> {
            val value = (document.number(field.path) ?: field.default).coerceIn(field.minimum, field.maximum)
            Column {
                Row {
                    Text(label, modifier = Modifier.weight(1f))
                    Text(formatValue(value, field.unit))
                }
                Slider(
                    value = value.toFloat(),
                    onValueChange = { raw ->
                        val stored = if (field.unit == FieldUnit.Count) raw.roundToInt().toDouble() else raw.toDouble()
                        setValue(onCommand, field.path, JsonPrimitive(stored))
                    },
                    valueRange = field.minimum.toFloat()..field.maximum.toFloat(),
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        }
        is ToggleField ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f))
                Switch(
                    checked = document.boolean(field.path) ?: field.default,
                    onCheckedChange = { setValue(onCommand, field.path, JsonPrimitive(it)) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
            }
        is ChoiceField -> {
            val current = document.string(field.path) ?: field.default
            Column {
                Text(label)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        field.options.chunked(OPTIONS_PER_ROW).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { (value, labelRes) ->
                                    FilterChip(
                                        selected = value == current,
                                        onClick = { setValue(onCommand, field.path, JsonPrimitive(value)) },
                                        label = { Text(stringResource(labelRes)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        is CurveField -> CurveFieldRow(field, label, editor, document, onCommand)
        is BrushPickerField -> BrushPickerRow(field, label, editor, document, onCommand)
        is AboutField -> AboutRow(field, label, editor, document, onCommand)
        is TextureField -> TextureFieldRow(field, label, editor, document, onCommand)
        is TextField -> {
            var text by remember(editor.brushId, field.path) { mutableStateOf(document.string(field.path).orEmpty()) }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    setValue(onCommand, field.path, JsonPrimitive(it))
                },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BrushPickerRow(
    field: BrushPickerField,
    label: String,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    val groups by editor.libraryGroups.collectAsState(initial = emptyList())
    val brushes = groups.flatMap { it.brushes }.filter { it.brushId != editor.brushId }
    val current = document.string(field.path)
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
                Text(brushes.firstOrNull { it.brushId == current }?.name ?: stringResource(R.string.brush_field_dual_brush_none))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.brush_field_dual_brush_none)) },
                    onClick = {
                        open = false
                        onCommand(BrushCommandIds.EDITOR_REMOVE_VALUE, JsonObject(mapOf("path" to JsonPrimitive(field.path))))
                    },
                )
                for (entry in brushes) {
                    DropdownMenuItem(
                        text = { Text(entry.name) },
                        onClick = {
                            open = false
                            setValue(onCommand, field.path, JsonPrimitive(entry.brushId))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CurveFieldRow(
    field: CurveField,
    label: String,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    val curve = BrushCurve.resolve(document, document.string(field.path))
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(
                checked = curve != null,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        setValue(onCommand, field.path, JsonPrimitive(BrushCurve.REFERENCE_PREFIX + "linear"))
                    } else {
                        onCommand(BrushCommandIds.EDITOR_REMOVE_VALUE, JsonObject(mapOf("path" to JsonPrimitive(field.path))))
                    }
                },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
        if (curve != null) {
            CurveEditor(
                points = curve.points,
                description = label,
                indicator = editor.lastPressure,
                onChange = { points ->
                    setValue(onCommand, "curves.${field.curveName}", BrushCurve(points).toJson())
                    setValue(onCommand, field.path, JsonPrimitive(BrushCurve.REFERENCE_PREFIX + field.curveName))
                },
                modifier = Modifier.padding(horizontal = 24.dp).size(CURVE_EDITOR_SIZE),
            )
        }
    }
}

@Composable
private fun TextureFieldRow(
    field: TextureField,
    label: String,
    editor: BrushEditorSession,
    document: BrushDocument,
    onCommand: (String, JsonObject) -> Unit,
) {
    val slotName = field.slot.name.lowercase()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                onCommand(
                    BrushCommandIds.EDITOR_IMPORT_TEXTURE,
                    JsonObject(mapOf("slot" to JsonPrimitive(slotName), "uri" to JsonPrimitive(uri.toString()))),
                )
            }
        }
    var sourceEditorOpen by remember { mutableStateOf(false) }
    if (sourceEditorOpen) {
        SourceEditorDialog(field.slot, editor, document, onCommand) { sourceEditorOpen = false }
    }
    val fileName = document.string(field.slot.field)
    val bytes = fileName?.let { editor.working.files[it] }
    val preview = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(TEXTURE_PREVIEW_SIZE).border(1.dp, MaterialTheme.colorScheme.outline).background(Color.Black)) {
                if (preview != null) {
                    Image(preview, contentDescription = label, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.Low)
                } else {
                    Text(
                        stringResource(if (field.slot.name == "Shape") R.string.brush_texture_round else R.string.brush_texture_none),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            OutlinedButton(onClick = { picker.launch("image/*") }) { Text(stringResource(R.string.brush_texture_import)) }
            TextButton(onClick = { sourceEditorOpen = true }) { Text(stringResource(R.string.brush_source_edit)) }
            if (fileName != null) {
                TextButton(onClick = {
                    onCommand(BrushCommandIds.EDITOR_REMOVE_TEXTURE, JsonObject(mapOf("slot" to JsonPrimitive(slotName))))
                }) {
                    Text(stringResource(if (field.slot.name == "Shape") R.string.brush_texture_use_round else R.string.brush_texture_remove))
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DrawingPad(
    editor: BrushEditorSession,
    modifier: Modifier,
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val samples = remember { StrokeSampleBuffer() }
    val background = if (editor.padDark) PAD_DARK else PAD_LIGHT
    val description = stringResource(R.string.brush_pad_title)
    Canvas(
        modifier
            .aspectRatio(BrushEditorSession.PAD_WIDTH.toFloat() / BrushEditorSession.PAD_HEIGHT)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .onSizeChanged { viewSize = it }
            .semantics { contentDescription = description }
            .pointerInteropFilter { event ->
                if (viewSize.width == 0) return@pointerInteropFilter false
                val scale = BrushEditorSession.PAD_WIDTH.toFloat() / viewSize.width
                samples.clear()
                val pointer = 0
                val tool = event.getToolType(pointer)
                fun add(
                    x: Float,
                    y: Float,
                    pressure: Float,
                    tilt: Float,
                    orientation: Float,
                    time: Long,
                ) {
                    val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
                    samples.add(
                        x = x * scale,
                        y = y * scale,
                        pressure = if (stylus) pressure.coerceIn(0f, 1f) else 1f,
                        tilt = tilt,
                        orientation = orientation,
                        eventTimeNanos = time,
                        toolType = tool,
                    )
                }
                for (index in 0 until event.historySize) {
                    add(
                        event.getHistoricalX(pointer, index),
                        event.getHistoricalY(pointer, index),
                        event.getHistoricalPressure(pointer, index),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointer, index),
                        event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointer, index),
                        StrokeInput.historicalTimeNanos(event, index),
                    )
                }
                add(
                    event.getX(pointer),
                    event.getY(pointer),
                    event.getPressure(pointer),
                    event.getAxisValue(MotionEvent.AXIS_TILT, pointer),
                    event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointer),
                    StrokeInput.currentTimeNanos(event),
                )
                editor.lastPressure = event.getPressure(pointer).coerceIn(0f, 1f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> editor.padBeginStroke(samples.copy())
                    MotionEvent.ACTION_MOVE -> editor.padAddSamples(samples.copy())
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        editor.padAddSamples(samples.copy())
                        editor.padEndStroke()
                        editor.lastPressure = null
                    }
                }
                true
            },
    ) {
        drawRect(background)
        editor.padImage?.let { image ->
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private fun formatValue(
    value: Double,
    unit: FieldUnit,
): String =
    when (unit) {
        FieldUnit.Percent -> "${(value * 100).roundToInt()}%"
        FieldUnit.Pixels -> "%.1f px".format(value)
        FieldUnit.Degrees -> "${(value * 180 / PI).roundToInt()}°"
        FieldUnit.Count -> value.roundToInt().toString()
        FieldUnit.Factor -> "%.2f".format(value)
    }

private val PAD_LIGHT = Color(0xFFFFFFFF)
private val PAD_DARK = Color(0xFF26262A)
private val SECTION_COLUMN_WIDTH = 190.dp
private val CURVE_EDITOR_SIZE = 220.dp
private val TEXTURE_PREVIEW_SIZE = 72.dp
private const val OPTIONS_PER_ROW = 3
