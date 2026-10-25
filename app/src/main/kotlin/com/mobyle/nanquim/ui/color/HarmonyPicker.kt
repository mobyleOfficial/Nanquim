package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.ColorHarmony
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscPoint
import com.mobyle.nanquim.document.color.HarmonyDisc
import kotlin.math.min

@Composable
fun HarmonyPicker(
    color: ColorValue,
    harmony: ColorHarmony,
    onHarmonyChange: (ColorHarmony) -> Unit,
    onChange: (ColorValue) -> Unit,
    discMaximum: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = harmony.colors(color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HarmonySelector(harmony, onHarmonyChange)
        HarmonyDiscView(
            color,
            harmony,
            onChange,
            stringResource(R.string.color_harmony_description),
            Modifier.widthIn(max = discMaximum).fillMaxWidth(),
        )
        ColorSlider(ColorComponent.Brightness, color, stringResource(R.string.color_component_brightness), onChange)
        Row {
            colors.forEachIndexed { index, harmonyColor ->
                val label = stringResource(R.string.color_harmony_swatch, index + 1, harmonyColor.toHex())
                Box(
                    Modifier
                        .size(HARMONY_TARGET)
                        .clickable { onChange(harmonyColor) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(HARMONY_SWATCH)
                            .border(if (index == 0) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .padding(2.dp)
                            .background(Color(harmonyColor.red, harmonyColor.green, harmonyColor.blue), CircleShape),
                    )
                }
            }
        }
    }
}

@Composable
private fun HarmonySelector(
    harmony: ColorHarmony,
    onHarmonyChange: (ColorHarmony) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(harmony.titleRes) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in ColorHarmony.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(choice.titleRes)) },
                    onClick = {
                        open = false
                        onHarmonyChange(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun HarmonyDiscView(
    color: ColorValue,
    harmony: ColorHarmony,
    onChange: (ColorValue) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val currentColor by rememberUpdatedState(color)
    val currentHarmony by rememberUpdatedState(harmony)
    val currentOnChange by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val radius = min(size.width, size.height) / 2f
                    val center = Offset(size.width / 2f, size.height / 2f)

                    fun unit(position: Offset) =
                        DiscPoint(
                            (position.x - center.x).toDouble() / radius,
                            (center.y - position.y).toDouble() / radius,
                        )
                    val start = currentColor
                    val startHarmony = currentHarmony
                    val colors = startHarmony.colors(start)
                    val downPoint = unit(down.position)
                    val reach = MARKER_REACH.toPx() / radius.toDouble()
                    val touched = HarmonyDisc.markerAt(colors, downPoint.x, downPoint.y, reach) ?: 0
                    val offset = if (touched == 0) 0f else startHarmony.hueOffsets[touched - 1]

                    fun drag(position: Offset) {
                        val point = unit(position)
                        val markerHue = start.hue + offset
                        val (hue, saturation) = HarmonyDisc.hueSaturationAt(point.x, point.y, markerHue)
                        currentOnChange(ColorValue.fromHsb(hue - offset, saturation, start.brightness, start.alpha))
                    }
                    var dragging = touched == 0
                    if (dragging) drag(down.position)
                    down.consume()
                    var released = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            released = true
                            break
                        }
                        if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (dragging && change.position != change.previousPosition) drag(change.position)
                        change.consume()
                    }
                    if (released && !dragging) currentOnChange(colors[touched])
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2f
        drawHarmonyDisc(radius, color.brightness)
        drawHarmonyMarkers(radius, harmony.colors(color))
    }
}

private fun DrawScope.drawHarmonyDisc(
    radius: Float,
    brightness: Float,
) {
    drawCircle(Brush.sweepGradient(*SWEEP_STOPS, center = center), radius)
    drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center = center, radius = radius), radius)
    drawCircle(Color.Black.copy(alpha = 1f - brightness), radius)
}

private fun DrawScope.drawHarmonyMarkers(
    radius: Float,
    colors: List<ColorValue>,
) {
    colors.forEachIndexed { index, marker ->
        val point = HarmonyDisc.point(marker.hue, marker.saturation)
        val at = Offset(center.x + (point.x * radius).toFloat(), center.y - (point.y * radius).toFloat())
        val size = if (index == 0) BASE_MARKER.toPx() else MARKER.toPx()
        drawCircle(Color(marker.red, marker.green, marker.blue), size, at)
        drawCircle(Color.White, size, at, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), size + 2.dp.toPx(), at, style = Stroke(1.dp.toPx()))
    }
}

private val SWEEP_STOPS: Array<Pair<Float, Color>> =
    listOf(90f, 120f, 180f, 240f, 300f, 360f, 420f, 450f)
        .map { hue ->
            val pure = ColorValue.fromHsb(hue % 360f, 1f, 1f)
            (hue - 90f) / 360f to Color(pure.red, pure.green, pure.blue)
        }.toTypedArray()

private val ColorHarmony.titleRes: Int
    get() =
        when (this) {
            ColorHarmony.Complementary -> R.string.color_harmony_complementary
            ColorHarmony.SplitComplementary -> R.string.color_harmony_split_complementary
            ColorHarmony.Analogous -> R.string.color_harmony_analogous
            ColorHarmony.Triadic -> R.string.color_harmony_triadic
            ColorHarmony.Tetradic -> R.string.color_harmony_tetradic
        }

private val BASE_MARKER = 14.dp
private val MARKER = 10.dp
private val MARKER_REACH = 24.dp
private val HARMONY_SWATCH = 32.dp
private val HARMONY_TARGET = 48.dp
