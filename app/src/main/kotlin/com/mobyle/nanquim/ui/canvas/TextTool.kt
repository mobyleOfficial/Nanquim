package com.mobyle.nanquim.ui.canvas

import android.graphics.Typeface
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.TextCommandIds
import com.mobyle.nanquim.text.FontEntry
import com.mobyle.nanquim.text.TextEditor
import com.mobyle.nanquim.text.TextInputView
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.TextGeometry
import kotlin.math.roundToInt

private fun textToScreen(
    session: CanvasSession,
    geometry: TextGeometry,
): ((Float, Float) -> Offset)? {
    val camera = session.viewport.canvasToScreen() ?: return null
    val t = geometry.transform
    return { x, y ->
        val canvas = Point(t[0] * x + t[2] * y + t[4], t[1] * x + t[3] * y + t[5])
        val screen = camera.apply(canvas)
        Offset(screen.x.toFloat(), screen.y.toFloat())
    }
}

private fun codePoints(
    text: String,
    utf16: Int,
): Int = text.codePointCount(0, utf16.coerceIn(0, text.length))

@Composable
fun TextEditOverlay(session: CanvasSession) {
    @Suppress("UNUSED_VARIABLE")
    val cameraVersion = session.viewport.cameraVersion
    val editing = session.textEditor.editing ?: return
    val geometry = editing.geometry ?: return
    val toScreen = textToScreen(session, geometry) ?: return
    val accent = MaterialTheme.colorScheme.primary
    val text = editing.text
    val start = codePoints(text, editing.selectionMin)
    val end = codePoints(text, editing.selectionMax)
    val minimum = with(LocalDensity.current) { TEXT_MINIMUM_BOX.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val width = maxOf(geometry.width, minimum)
        val height = maxOf(geometry.height, minimum)
        val outline =
            Path().apply {
                val corners = listOf(toScreen(0f, 0f), toScreen(width, 0f), toScreen(width, height), toScreen(0f, height))
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
        drawPath(outline, accent, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        for (box in geometry.selection(start, end)) {
            val corners =
                listOf(
                    toScreen(box.x, box.y),
                    toScreen(box.x + box.width, box.y),
                    toScreen(box.x + box.width, box.y + box.height),
                    toScreen(box.x, box.y + box.height),
                )
            drawPath(
                Path().apply {
                    moveTo(corners[0].x, corners[0].y)
                    corners.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                },
                accent.copy(alpha = TEXT_SELECTION_ALPHA),
            )
        }
        if (start == end) {
            val caret = geometry.caret(start)
            drawLine(accent, toScreen(caret[0], caret[1]), toScreen(caret[2], caret[3]), 2.dp.toPx())
        }
    }
    if (start != end) {
        SelectionHandle(session, geometry, toScreen, anchorAtStart = false, start, end)
        SelectionHandle(session, geometry, toScreen, anchorAtStart = true, start, end)
    }
}

@Composable
private fun SelectionHandle(
    session: CanvasSession,
    geometry: TextGeometry,
    toScreen: (Float, Float) -> Offset,
    anchorAtStart: Boolean,
    start: Int,
    end: Int,
) {
    val caret = geometry.caret(if (anchorAtStart) end else start)
    val bottom = toScreen(caret[2], caret[3])
    val radius = with(LocalDensity.current) { TEXT_HANDLE_RADIUS.toPx() }
    val currentGeometry by rememberUpdatedState(geometry)
    val toCanvas = session.viewport.screenToCanvas()
    Box(
        Modifier
            .offset { IntOffset((bottom.x - radius).roundToInt(), bottom.y.roundToInt()) }
            .size(TEXT_HANDLE_RADIUS * 2)
            .pointerInput(anchorAtStart) {
                var position = Offset.Zero
                detectDragGestures(onDragStart = { position = it + Offset(bottom.x - radius, bottom.y) }) { change, drag ->
                    change.consume()
                    position += drag
                    val canvas =
                        toCanvas?.apply(Point(position.x.toDouble(), (position.y - radius).toDouble())) ?: return@detectDragGestures
                    val t = currentGeometry.transform
                    val determinant = t[0] * t[3] - t[1] * t[2]
                    if (determinant == 0.0) return@detectDragGestures
                    val dx = canvas.x - t[4]
                    val dy = canvas.y - t[5]
                    val u = ((t[3] * dx - t[2] * dy) / determinant).toFloat()
                    val v = ((-t[1] * dx + t[0] * dy) / determinant).toFloat()
                    val editing = session.textEditor.editing ?: return@detectDragGestures
                    val text = editing.text
                    val moved = text.offsetByCodePoints(0, currentGeometry.hitTest(u, v).coerceIn(0, text.codePointCount(0, text.length)))
                    val anchor = if (anchorAtStart) editing.selectionMin else editing.selectionMax
                    session.textEditor.setSelection(anchor, moved)
                }
            },
    ) {
        Surface(Modifier.fillMaxSize(), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {}
    }
}

@Composable
fun TextKeyboard(session: CanvasSession) {
    val editor = session.textEditor
    val editing = editor.editing ?: return
    var view by remember { mutableStateOf<TextInputView?>(null) }
    AndroidView(
        factory = { context -> TextInputView(context, editor).also { view = it } },
        modifier = Modifier.size(1.dp).alpha(0f),
    )
    LaunchedEffect(view) { view?.showKeyboard() }
    LaunchedEffect(editing.text, editing.selectionStart, editing.selectionEnd) { view?.syncFromEditor() }
    DisposableEffect(Unit) { onDispose { view?.hideKeyboard() } }
}

@Composable
fun TextToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = session.textEditor
    val editing = editor.editing
    var fontsShown by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(8.dp).width(TEXT_PANEL_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editing == null) {
                Text(stringResource(R.string.text_hint), Modifier.padding(8.dp))
                return@Column
            }
            val style = editor.currentStyle() ?: JsonObject(emptyMap())
            val paragraph = editing.model["paragraph"] as? JsonObject ?: JsonObject(emptyMap())

            fun flag(
                source: JsonObject,
                key: String,
            ) = (source[key] as? JsonPrimitive)?.booleanOrNull == true

            fun number(
                source: JsonObject,
                key: String,
                fallback: Double,
            ) = (source[key] as? JsonPrimitive)?.doubleOrNull ?: fallback
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fontId = (style["fontId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val entry = session.fontLibrary?.find(fontId)
                FilledTonalButton(onClick = { fontsShown = true }) {
                    Text(entry?.let { "${it.info.family} ${it.info.style}" } ?: fontId, fontFamily = entry?.let { fontFamilyOf(it) })
                }
                TextButton(onClick = {
                    val color = session.primaryColor
                    onCommand(TextCommandIds.SET_STYLE, mapOf("color" to listOf(color.red, color.green, color.blue, 1f)))
                }) { Text(stringResource(R.string.text_use_color)) }
                for ((alignment, label) in listOf(
                    "left" to R.string.text_align_left,
                    "center" to R.string.text_align_center,
                    "right" to R.string.text_align_right,
                )) {
                    Toggle(label, (paragraph["alignment"] as? JsonPrimitive)?.contentOrNull == alignment) {
                        onCommand(TextCommandIds.SET_PARAGRAPH, mapOf("alignment" to alignment))
                    }
                }
                Toggle(R.string.text_vertical, flag(paragraph, "vertical")) {
                    onCommand(TextCommandIds.SET_PARAGRAPH, mapOf("vertical" to !flag(paragraph, "vertical")))
                }
                Toggle(R.string.text_underline, flag(style, "underline")) {
                    onCommand(
                        TextCommandIds.SET_STYLE,
                        mapOf("underline" to !flag(style, "underline")),
                    )
                }
                Toggle(R.string.text_strikethrough, flag(style, "strikethrough")) {
                    onCommand(TextCommandIds.SET_STYLE, mapOf("strikethrough" to !flag(style, "strikethrough")))
                }
                Toggle(R.string.text_all_caps, flag(style, "allCaps")) {
                    onCommand(
                        TextCommandIds.SET_STYLE,
                        mapOf("allCaps" to !flag(style, "allCaps")),
                    )
                }
                val kerning = (style["kerning"] as? JsonPrimitive)?.contentOrNull != "none"
                Toggle(R.string.text_kerning, kerning) { onCommand(TextCommandIds.SET_STYLE, mapOf("kerning" to !kerning)) }
                Toggle(R.string.text_more, expanded) { expanded = !expanded }
                TextButton(onClick = { onCommand(TextCommandIds.DONE, emptyMap()) }) { Text(stringResource(R.string.text_done)) }
            }
            if (!expanded) return@Column
            StyleSlider(R.string.text_size, number(style, "sizePt", TextEditor.DEFAULT_SIZE_PT), 4.0..400.0, "pt") {
                onCommand(TextCommandIds.SET_STYLE, mapOf("sizePt" to it))
            }
            StyleSlider(R.string.text_tracking, number(style, "tracking", 0.0), -200.0..800.0, "") {
                onCommand(TextCommandIds.SET_STYLE, mapOf("tracking" to it))
            }
            StyleSlider(R.string.text_line_height, number(paragraph, "lineHeight", 1.2), 0.5..3.0, "×", decimals = 2) {
                onCommand(TextCommandIds.SET_PARAGRAPH, mapOf("lineHeight" to it))
            }
            StyleSlider(R.string.text_baseline_shift, number(style, "baselineShift", 0.0), -100.0..100.0, "pt") {
                onCommand(TextCommandIds.SET_STYLE, mapOf("baselineShift" to it))
            }
            val outline = style["outline"] as? JsonObject ?: JsonObject(emptyMap())
            StyleSlider(R.string.text_outline, number(outline, "width", 0.0), 0.0..20.0, "pt", decimals = 1) {
                val color = session.secondaryColor
                onCommand(
                    TextCommandIds.SET_STYLE,
                    mapOf(
                        "outlineWidth" to it,
                        "outlineColor" to listOf(color.red, color.green, color.blue, 1f),
                    ),
                )
            }
        }
    }
    if (fontsShown && session.fontLibrary != null) {
        FontPicker(
            fonts = session.fontLibrary!!.fonts,
            recent = editor.recentFontIds,
            onPick = { id ->
                fontsShown = false
                onCommand(TextCommandIds.SET_STYLE, mapOf("fontId" to id))
            },
            onDismiss = { fontsShown = false },
        )
    }
}

@Composable
private fun Toggle(
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
private fun StyleSlider(
    @StringRes labelRes: Int,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    unit: String,
    decimals: Int = 0,
    onSet: (Double) -> Unit,
) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged?.toDouble() ?: value
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), Modifier.width(TEXT_LABEL_WIDTH), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = shown.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
            onValueChange = { dragged = it },
            onValueChangeFinished = {
                dragged?.let { onSet(if (decimals == 0) it.roundToInt().toDouble() else it.toDouble()) }
                dragged = null
            },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text("%.${decimals}f%s".format(shown, unit), Modifier.width(TEXT_VALUE_WIDTH), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FontPicker(
    fonts: List<FontEntry>,
    recent: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matching =
        fonts.filter { query.isBlank() || it.info.family.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true) }
    val ordered = matching.filter { it.id in recent }.sortedBy { recent.indexOf(it.id) } + matching.filterNot { it.id in recent }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_fonts)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.text_fonts_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = TEXT_FONT_LIST_HEIGHT)) {
                    items(ordered, key = { it.id }) { entry ->
                        Text(
                            "${entry.info.family} ${entry.info.style}",
                            fontFamily = fontFamilyOf(entry),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(entry.id) }.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private val typefaces = mutableMapOf<String, FontFamily?>()

private fun fontFamilyOf(entry: FontEntry): FontFamily? =
    typefaces.getOrPut(entry.id) {
        runCatching { FontFamily(Typeface.Builder(entry.file).setTtcIndex(entry.info.faceIndex).build()) }.getOrNull()
    }

private val TEXT_MINIMUM_BOX = 24.dp
private val TEXT_HANDLE_RADIUS = 10.dp
private val TEXT_PANEL_WIDTH = 560.dp
private val TEXT_LABEL_WIDTH = 110.dp
private val TEXT_VALUE_WIDTH = 64.dp
private val TEXT_FONT_LIST_HEIGHT = 420.dp
private const val TEXT_SELECTION_ALPHA = 0.3f
