package com.mobyle.nanquim.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.adjust.AdjustmentEditor
import com.mobyle.nanquim.canvas.adjust.AdjustmentMode
import com.mobyle.nanquim.canvas.adjust.PerspectiveParameters
import com.mobyle.nanquim.canvas.adjust.hasPuck
import com.mobyle.nanquim.canvas.adjust.isAngle
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.command.AdjustmentCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.engine.AdjustmentFilter
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class AdjustmentMenuEntry(
    @StringRes val labelRes: Int,
    val filter: AdjustmentFilter? = null,
) {
    HueSaturationBrightness(R.string.adjust_hue_saturation_brightness, AdjustmentFilter.HueSaturationBrightness),
    ColorBalance(R.string.adjust_color_balance, AdjustmentFilter.ColorBalance),
    Curves(R.string.adjust_curves, AdjustmentFilter.Curves),
    GradientMap(R.string.adjust_gradient_map, AdjustmentFilter.GradientMap),
    GaussianBlur(R.string.adjust_gaussian_blur, AdjustmentFilter.GaussianBlur),
    MotionBlur(R.string.adjust_motion_blur, AdjustmentFilter.MotionBlur),
    PerspectiveBlur(R.string.adjust_perspective_blur, AdjustmentFilter.PerspectiveBlur),
    Noise(R.string.adjust_noise, AdjustmentFilter.Noise),
    Sharpen(R.string.adjust_sharpen, AdjustmentFilter.Sharpen),
    Bloom(R.string.adjust_bloom, AdjustmentFilter.Bloom),
    Glitch(R.string.adjust_glitch, AdjustmentFilter.Glitch),
    Halftone(R.string.adjust_halftone, AdjustmentFilter.Halftone),
    ChromaticAberration(R.string.adjust_chromatic_aberration, AdjustmentFilter.ChromaticAberration),
    Liquify(R.string.adjust_liquify, AdjustmentFilter.Liquify),
    Clone(R.string.tool_clone),
}

@Composable
fun AdjustmentsMenuButton(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        val selected = session.canvasTool == CanvasTool.Adjust
        if (icon != null) {
            BarIconButton(icon, R.string.tool_adjustments, selected || open) { open = true }
        } else if (selected) {
            FilledTonalButton(onClick = { open = true }) { Text(stringResource(R.string.tool_adjustments)) }
        } else {
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.tool_adjustments)) }
        }
        AdjustmentsMenu(expanded = open, onDismiss = { open = false }, onCommand = onCommand)
    }
}

@Composable
fun AdjustmentsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    var shownModes by remember { mutableStateOf<AdjustmentMenuEntry?>(null) }

    fun close() {
        shownModes = null
        onDismiss()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = ::close) {
        for (entry in AdjustmentMenuEntry.entries) {
            val filter = entry.filter
            DropdownMenuItem(
                text = { Text(stringResource(entry.labelRes)) },
                enabled = filter != null || entry == AdjustmentMenuEntry.Clone,
                onClick = {
                    if (entry == AdjustmentMenuEntry.Clone) {
                        close()
                        onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to "clone"))
                    } else if (entry == AdjustmentMenuEntry.Liquify) {
                        close()
                        onCommand(AdjustmentCommandIds.preview(AdjustmentFilter.Liquify), mapOf("mode" to "layer"))
                    } else {
                        shownModes = if (shownModes == entry) null else entry
                    }
                },
            )
            if (filter != null && shownModes == entry) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.adjust_mode_layer)) },
                    modifier = Modifier.padding(start = 16.dp),
                    onClick = {
                        close()
                        onCommand(AdjustmentCommandIds.preview(filter), mapOf("mode" to "layer"))
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.adjust_mode_pencil)) },
                    modifier = Modifier.padding(start = 16.dp),
                    onClick = {
                        close()
                        onCommand(AdjustmentCommandIds.preview(filter), mapOf("mode" to "pencil"))
                    },
                )
            }
        }
    }
}

internal val COLOR_BALANCE_RANGES =
    listOf(R.string.adjust_range_shadows, R.string.adjust_range_midtones, R.string.adjust_range_highlights)

internal val COLOR_BALANCE_AXES =
    listOf(R.string.adjust_axis_cyan_red, R.string.adjust_axis_magenta_green, R.string.adjust_axis_yellow_blue)

@Composable
internal fun parameterLabel(
    filter: AdjustmentFilter,
    index: Int,
): String =
    when (filter) {
        AdjustmentFilter.HueSaturationBrightness ->
            stringResource(
                listOf(R.string.adjust_parameter_hue, R.string.adjust_parameter_saturation, R.string.adjust_parameter_brightness)[index],
            )
        AdjustmentFilter.ColorBalance ->
            stringResource(
                R.string.adjust_range_axis,
                stringResource(COLOR_BALANCE_RANGES[index / 3]),
                stringResource(
                    COLOR_BALANCE_AXES[
                        index %
                            3,
                    ],
                ),
            )
        AdjustmentFilter.Curves -> ""
        AdjustmentFilter.GradientMap, AdjustmentFilter.Sharpen -> stringResource(R.string.adjust_parameter_amount)
        AdjustmentFilter.GaussianBlur -> stringResource(R.string.adjust_parameter_radius)
        else ->
            stringResource(
                when (filter.parameterNames[index]) {
                    "angle" -> R.string.adjust_parameter_angle
                    "x" -> R.string.adjust_parameter_puck_x
                    "y" -> R.string.adjust_parameter_puck_y
                    "directional" -> R.string.adjust_perspective_directional
                    "displace" -> R.string.adjust_chromatic_displace
                    "scale" -> R.string.adjust_parameter_scale
                    "octaves" -> R.string.adjust_parameter_octaves
                    "turbulence" -> R.string.adjust_parameter_turbulence
                    "size" -> R.string.adjust_parameter_size
                    "transition" -> R.string.adjust_parameter_transition
                    "burn" -> R.string.adjust_parameter_burn
                    "blockSize" -> R.string.adjust_parameter_block_size
                    "zoom" -> R.string.adjust_parameter_zoom
                    "frequency" -> R.string.adjust_parameter_frequency
                    "dotSize" -> R.string.adjust_parameter_dot_size
                    "falloff" -> R.string.adjust_parameter_falloff
                    "transparency" -> R.string.adjust_parameter_transparency
                    "pressure" -> R.string.liquify_pressure
                    "distortion" -> R.string.liquify_distortion
                    "momentum" -> R.string.liquify_momentum
                    "adjust" -> R.string.liquify_adjust
                    else -> R.string.adjust_parameter_amount
                },
            )
    }

internal fun AdjustmentFilter.choices(index: Int): List<Int>? =
    when (parameterNames.getOrNull(index)) {
        "type" ->
            when (this) {
                AdjustmentFilter.Noise -> listOf(R.string.adjust_noise_clouds, R.string.adjust_noise_billows, R.string.adjust_noise_ridges)
                AdjustmentFilter.Glitch ->
                    listOf(
                        R.string.adjust_glitch_artifact,
                        R.string.adjust_glitch_wave,
                        R.string.adjust_glitch_signal,
                        R.string.adjust_glitch_diverge,
                    )
                else -> null
            }
        "style" -> listOf(R.string.adjust_halftone_full_color, R.string.adjust_halftone_screen_print, R.string.adjust_halftone_newspaper)
        "directional" -> listOf(R.string.adjust_perspective_positional, R.string.adjust_perspective_directional)
        "displace" -> listOf(R.string.adjust_chromatic_perspective, R.string.adjust_chromatic_displace)
        "liquifyMode" ->
            listOf(
                R.string.liquify_push,
                R.string.liquify_twirl_right,
                R.string.liquify_twirl_left,
                R.string.liquify_pinch,
                R.string.liquify_expand,
                R.string.liquify_crystals,
                R.string.liquify_edge,
                R.string.liquify_reconstruct,
            )
        else -> null
    }

@Composable
internal fun AdjustmentEditor.valueText(index: Int): String =
    if (filter.isAngle(index)) {
        stringResource(R.string.adjust_value_degrees, (values[index] * 180).roundToInt())
    } else {
        "${percent(index)}%"
    }

internal fun AdjustmentEditor.percent(index: Int): Int {
    val range = range(index)
    val value = values[index]
    return if (range.minimum < 0) {
        (value / maxOf(range.maximum, -range.minimum) * 100).roundToInt()
    } else {
        ((value - range.minimum) / range.span * 100).roundToInt()
    }
}

@Composable
fun AdjustmentBanner(
    session: CanvasSession,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    val editor = session.adjustmentEditor ?: return
    if (!editor.hasParameters) return
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Text(
            "${parameterLabel(editor.filter, editor.mainParameter)} ${editor.valueText(editor.mainParameter)}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun AdjustmentPanel(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    val editor = session.adjustmentEditor ?: return
    Surface(modifier = modifier.widthIn(max = PANEL_WIDTH), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (editor.filter) {
                AdjustmentFilter.HueSaturationBrightness ->
                    editor.filter.parameterNames.indices
                        .forEach { ParameterSlider(session, editor, it, onCommand) }
                AdjustmentFilter.ColorBalance -> ColorBalanceControls(session, editor, onCommand)
                AdjustmentFilter.Curves -> CurvesControls(session, editor, onCommand)
                AdjustmentFilter.GradientMap -> GradientMapControls(session, editor, onCommand)
                AdjustmentFilter.GaussianBlur, AdjustmentFilter.Sharpen, AdjustmentFilter.MotionBlur ->
                    editor.filter.parameterNames.indices
                        .forEach { ParameterSlider(session, editor, it, onCommand) }
                else -> ChoiceAndSliderControls(session, editor, onCommand)
            }
            if (editor.filter == AdjustmentFilter.Liquify) LiquifyActions(editor, onCommand)
            Text(
                stringResource(
                    when {
                        editor.mode == AdjustmentMode.Pencil -> R.string.adjust_hint_pencil
                        editor.filter == AdjustmentFilter.MotionBlur -> R.string.adjust_hint_motion
                        editor.filter.hasPuck -> R.string.adjust_hint_perspective
                        editor.filter == AdjustmentFilter.Liquify -> R.string.liquify_hint
                        editor.hasParameters -> R.string.adjust_hint
                        else -> R.string.adjust_hint_no_drag
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
internal fun ParameterSlider(
    session: CanvasSession,
    editor: AdjustmentEditor,
    index: Int,
    onCommand: (String, Map<String, Any>) -> Unit,
    label: String = parameterLabel(editor.filter, index),
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    val name = editor.filter.parameterNames[index]
    val range = editor.range(index)
    val main = index == editor.mainParameter
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onCommand(AdjustmentCommandIds.SELECT_PARAMETER, mapOf("name" to name)) },
            modifier = Modifier.width(LABEL_WIDTH),
        ) {
            Text(
                label,
                style = if (main) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                color = if (main) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = editor.values[index],
            onValueChange = { value ->
                if (editor.mainParameter != index) editor.selectMainParameter(index)
                editor.preview(index, value)
                session.adjustmentChanged()
            },
            onValueChangeFinished = {
                onCommand(AdjustmentCommandIds.SET_PARAMETER, mapOf("name" to name, "value" to editor.values[index]))
            },
            valueRange = range.minimum..range.maximum,
            modifier = Modifier.weight(1f),
        )
        Text(editor.valueText(index), Modifier.width(PERCENT_WIDTH), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColumnScope.ChoiceAndSliderControls(
    session: CanvasSession,
    editor: AdjustmentEditor,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val names = editor.filter.parameterNames
    for (index in names.indices) {
        val choices = editor.filter.choices(index) ?: continue
        choices.withIndex().chunked(CHOICES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                for ((value, labelRes) in row) {
                    val selected = editor.values[index].roundToInt() == value
                    val onClick = {
                        onCommand(
                            AdjustmentCommandIds.SET_PARAMETER,
                            mapOf("name" to names[index], "value" to value.toFloat()),
                        )
                    }
                    if (selected) {
                        FilledTonalButton(onClick = onClick) { Text(stringResource(labelRes)) }
                    } else {
                        TextButton(onClick = onClick) { Text(stringResource(labelRes)) }
                    }
                }
            }
        }
    }
    val modeUsesAngle = !editor.filter.hasPuck || editor.values[PerspectiveParameters.DIRECTIONAL] >= 0.5f
    for (index in names.indices) {
        if (editor.filter.choices(index) != null) continue
        if (editor.filter.isAngle(index) && !modeUsesAngle) continue
        ParameterSlider(session, editor, index, onCommand)
    }
}

@Composable
private fun ColumnScope.LiquifyActions(
    editor: AdjustmentEditor,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) {
        TextButton(enabled = editor.canUndo, onClick = { onCommand(AdjustmentCommandIds.UNDO, emptyMap()) }) {
            Text(stringResource(R.string.adjust_undo))
        }
        TextButton(enabled = editor.canRedo, onClick = { onCommand(AdjustmentCommandIds.REDO, emptyMap()) }) {
            Text(stringResource(R.string.adjust_redo))
        }
        TextButton(onClick = { onCommand(AdjustmentCommandIds.RESET, emptyMap()) }) { Text(stringResource(R.string.adjust_reset)) }
        TextButton(onClick = { onCommand(AdjustmentCommandIds.CANCEL, emptyMap()) }) { Text(stringResource(R.string.adjust_cancel)) }
        FilledTonalButton(onClick = { onCommand(AdjustmentCommandIds.COMMIT, emptyMap()) }) { Text(stringResource(R.string.adjust_apply)) }
    }
}

@Composable
fun PerspectivePuck(session: CanvasSession) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion

    @Suppress("UNUSED_VARIABLE")
    val camera = session.viewport.cameraVersion
    val editor = session.adjustmentEditor ?: return
    val puck = session.adjustmentPuck ?: return
    val transform = session.viewport.canvasToScreen() ?: return
    val center = transform.apply(puck)
    val directional = editor.values[PerspectiveParameters.DIRECTIONAL] >= 0.5f
    val angle = editor.values[PerspectiveParameters.ANGLE] * PI
    val ahead = transform.apply(Point(puck.x + cos(angle), puck.y + sin(angle)))
    Canvas(Modifier.fillMaxSize()) {
        val at = Offset(center.x.toFloat(), center.y.toFloat())
        val radius = PUCK_RADIUS.dp.toPx()
        val width = 2.dp.toPx()
        drawCircle(Color.Black.copy(alpha = 0.35f), radius, at)
        drawCircle(Color.White, radius, at, style = Stroke(width * 2))
        drawCircle(Color.Black, radius, at, style = Stroke(width))
        drawCircle(Color.White, width * 2, at)
        if (directional) {
            val dx = ahead.x - center.x
            val dy = ahead.y - center.y
            val length = hypot(dx, dy).takeIf { it > 0 } ?: return@Canvas
            val tip = Offset(at.x + (dx / length * radius * 2).toFloat(), at.y + (dy / length * radius * 2).toFloat())
            drawLine(Color.White, at, tip, width * 2)
            drawLine(Color.Black, at, tip, width)
        }
    }
}

private const val PUCK_RADIUS = 22f
private const val CHOICES_PER_ROW = 4

@Composable
fun AdjustmentMenu(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val version = session.adjustmentVersion
    val editor = session.adjustmentEditor ?: return
    val at = session.adjustmentMenu ?: return
    val placement =
        Modifier.layout { measurable, constraints ->
            val menu = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val gap = MENU_OFFSET.roundToPx()
                val below = at.y.roundToInt() + gap
                val y = if (below + menu.height <= constraints.maxHeight) below else at.y.roundToInt() - gap - menu.height
                val x = (at.x.roundToInt() - menu.width / 2).coerceIn(0, (constraints.maxWidth - menu.width).coerceAtLeast(0))
                menu.place(x, y.coerceAtLeast(0))
            }
        }
    Surface(modifier = placement, shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onCommand(AdjustmentCommandIds.COMPARE, mapOf("enabled" to true))
                                tryAwaitRelease()
                                onCommand(AdjustmentCommandIds.COMPARE, mapOf("enabled" to false))
                            },
                        )
                    }.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(R.string.adjust_preview),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            FilledTonalButton(
                onClick = { onCommand(AdjustmentCommandIds.COMMIT, emptyMap()) },
            ) { Text(stringResource(R.string.adjust_apply)) }
            TextButton(enabled = editor.canRedo, onClick = { onCommand(AdjustmentCommandIds.REDO, emptyMap()) }) {
                Text(stringResource(R.string.adjust_redo))
            }
            TextButton(onClick = { onCommand(AdjustmentCommandIds.RESET, emptyMap()) }) { Text(stringResource(R.string.adjust_reset)) }
            TextButton(enabled = editor.canUndo, onClick = { onCommand(AdjustmentCommandIds.UNDO, emptyMap()) }) {
                Text(stringResource(R.string.adjust_undo))
            }
            TextButton(onClick = { onCommand(AdjustmentCommandIds.CANCEL, emptyMap()) }) { Text(stringResource(R.string.adjust_cancel)) }
        }
    }
}

internal val PANEL_WIDTH = 620.dp
internal val LABEL_WIDTH = 160.dp
internal val PERCENT_WIDTH = 56.dp
private val MENU_OFFSET = 24.dp
