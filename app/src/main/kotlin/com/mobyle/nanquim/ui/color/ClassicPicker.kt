package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.ColorValue

@Composable
fun ClassicPicker(
    color: ColorValue,
    onChange: (ColorValue) -> Unit,
    squareMaximum: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SaturationBrightnessSquare(
            color,
            onChange,
            stringResource(R.string.color_classic_description),
            Modifier.widthIn(max = squareMaximum).fillMaxWidth(),
        )
        for (component in ColorComponent.HSB) {
            ColorSlider(component, color, stringResource(component.titleRes), onChange)
        }
    }
}

@Composable
fun SaturationBrightnessSquare(
    color: ColorValue,
    onChange: (ColorValue) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val currentColor by rememberUpdatedState(color)
    val currentOnChange by rememberUpdatedState(onChange)
    val pure = ColorValue.fromHsb(color.hue, 1f, 1f)
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = currentColor

                    fun report(position: Offset) {
                        val saturation = (position.x / size.width).coerceIn(0f, 1f)
                        val brightness = (1f - position.y / size.height).coerceIn(0f, 1f)
                        currentOnChange(ColorValue.fromHsb(start.hue, saturation, brightness, start.alpha))
                    }
                    report(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position != change.previousPosition) report(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(pure.red, pure.green, pure.blue))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val at = Offset(color.saturation * size.width, (1f - color.brightness) * size.height)
        val marker = MARKER.toPx()
        drawCircle(Color(color.red, color.green, color.blue), marker, at)
        drawCircle(Color.White, marker, at, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), marker + 2.dp.toPx(), at, style = Stroke(1.dp.toPx()))
    }
}

val ColorComponent.titleRes: Int
    get() =
        when (this) {
            ColorComponent.Hue -> R.string.color_component_hue
            ColorComponent.Saturation -> R.string.color_component_saturation
            ColorComponent.Brightness -> R.string.color_component_brightness
            ColorComponent.Red -> R.string.color_component_red
            ColorComponent.Green -> R.string.color_component_green
            ColorComponent.Blue -> R.string.color_component_blue
        }

private val MARKER = 12.dp
