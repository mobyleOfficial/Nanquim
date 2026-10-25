package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.document.color.CmykConversion
import com.mobyle.nanquim.document.color.CmykInks
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.InkChannel

@Composable
fun ColorSlider(
    component: ColorComponent,
    color: ColorValue,
    label: String,
    onChange: (ColorValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = component.fraction(color)
    val thumb = component.withFraction(color, fraction)
    GradientSlider(
        value = color,
        fraction = fraction,
        stops = component.trackStops(color).map { Color(it.red, it.green, it.blue) },
        thumbColor = Color(thumb.red, thumb.green, thumb.blue),
        label = label,
        valueText = component.displayValue(color).toString(),
        onFraction = { base, target -> onChange(component.withFraction(base, target)) },
        modifier = modifier,
    )
}

@Composable
fun InkSlider(
    channel: InkChannel,
    inks: CmykInks,
    color: ColorValue,
    conversion: CmykConversion,
    label: String,
    onInks: (CmykInks) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stops =
        remember(inks, conversion) {
            listOf(0f, 0.5f, 1f).map { amount ->
                val stop = conversion.colorOf(channel.with(inks, amount), 1f)
                Color(stop.red, stop.green, stop.blue)
            }
        }
    GradientSlider(
        value = inks,
        fraction = channel.of(inks),
        stops = stops,
        thumbColor = Color(color.red, color.green, color.blue),
        label = label,
        valueText = channel.percent(inks).toString(),
        onFraction = { base, target -> onInks(channel.with(base, target)) },
        modifier = modifier,
    )
}

@Composable
private fun <T> GradientSlider(
    value: T,
    fraction: Float,
    stops: List<Color>,
    thumbColor: Color,
    label: String,
    valueText: String,
    onFraction: (T, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnFraction by rememberUpdatedState(onFraction)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(SLIDER_HEIGHT)
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    currentOnFraction(currentValue, target)
                    true
                }
            }.pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = currentValue
                    val inset = size.height / 2f

                    fun report(x: Float) {
                        val span = (size.width - 2f * inset).coerceAtLeast(1f)
                        currentOnFraction(start, ((x - inset) / span).coerceIn(0f, 1f))
                    }
                    report(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position != change.previousPosition) report(change.position.x)
                        change.consume()
                    }
                }
            },
    ) {
        val radius = size.height / 2f
        val trackHeight = size.height * TRACK_FRACTION
        val trackTop = (size.height - trackHeight) / 2f
        drawRoundRect(
            brush = Brush.horizontalGradient(stops, startX = radius, endX = size.width - radius),
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val center = Offset(radius + fraction * (size.width - 2f * radius), size.height / 2f)
        drawCircle(thumbColor, radius * 0.8f, center)
        drawCircle(Color.White, radius * 0.8f, center, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), radius * 0.8f + 2.dp.toPx(), center, style = Stroke(1.dp.toPx()))
    }
}

private val SLIDER_HEIGHT = 28.dp
private const val TRACK_FRACTION = 0.6f
