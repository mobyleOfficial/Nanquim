package com.mobyle.nanquim.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.document.brush.BrushCurve

@Composable
fun CurveEditor(
    points: List<Pair<Double, Double>>,
    description: String,
    onChange: (List<Pair<Double, Double>>) -> Unit,
    modifier: Modifier = Modifier,
    indicator: Float? = null,
) {
    val currentPoints by rememberUpdatedState(points)
    val currentOnChange by rememberUpdatedState(onChange)
    var dragged by remember { mutableIntStateOf(-1) }
    var working by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    val shown = working ?: points
    val curve = BrushCurve(shown)
    val colors = MaterialTheme.colorScheme
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .border(1.dp, colors.outline)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val (x, y) = toCurve(offset, size.width.toFloat(), size.height.toFloat())
                            val list = currentPoints.toMutableList()
                            val near = BrushCurve.nearestPoint(list, x, y, HIT_RADIUS)
                            if (near > 0 && near < list.lastIndex) {
                                list.removeAt(near)
                            } else if (near < 0 && list.none { kotlin.math.abs(it.first - x) < MINIMUM_GAP }) {
                                list.add(x to y)
                                list.sortBy { it.first }
                            }
                            if (BrushCurve.isValid(list)) currentOnChange(list)
                        },
                    )
                }.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val (x, y) = toCurve(offset, size.width.toFloat(), size.height.toFloat())
                            dragged = BrushCurve.nearestPoint(currentPoints, x, y, HIT_RADIUS)
                            working = currentPoints
                        },
                        onDragEnd = {
                            working?.let(currentOnChange)
                            dragged = -1
                            working = null
                        },
                        onDragCancel = {
                            dragged = -1
                            working = null
                        },
                        onDrag = { change, _ ->
                            val list = working?.toMutableList() ?: return@detectDragGestures
                            val index = dragged.takeIf { it in list.indices } ?: return@detectDragGestures
                            val (x, y) = toCurve(change.position, size.width.toFloat(), size.height.toFloat())
                            val newX =
                                when (index) {
                                    0 -> 0.0
                                    list.lastIndex -> 1.0
                                    else -> x.coerceIn(list[index - 1].first + MINIMUM_GAP, list[index + 1].first - MINIMUM_GAP)
                                }
                            list[index] = newX to y
                            if (BrushCurve.isValid(list)) {
                                working = list
                                currentOnChange(list)
                            }
                        },
                    )
                },
    ) {
        val width = size.width
        val height = size.height
        for (step in 1 until GRID_LINES) {
            val fraction = step / GRID_LINES.toFloat()
            drawLine(colors.outlineVariant, Offset(width * fraction, 0f), Offset(width * fraction, height))
            drawLine(colors.outlineVariant, Offset(0f, height * fraction), Offset(width, height * fraction))
        }
        val path = Path()
        for (step in 0..CURVE_STEPS) {
            val x = step / CURVE_STEPS.toDouble()
            val point = Offset((x * width).toFloat(), ((1 - curve.evaluate(x)) * height).toFloat())
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, colors.primary, style = Stroke(width = 3.dp.toPx()))
        shown.forEach { (x, y) ->
            drawCircle(colors.primary, radius = 7.dp.toPx(), center = Offset((x * width).toFloat(), ((1 - y) * height).toFloat()))
        }
        indicator?.let { value ->
            val x = value.coerceIn(0f, 1f) * width
            drawLine(colors.tertiary, Offset(x, 0f), Offset(x, height), strokeWidth = 2.dp.toPx())
        }
    }
}

private fun toCurve(
    offset: Offset,
    width: Float,
    height: Float,
): Pair<Double, Double> = (offset.x / width).toDouble().coerceIn(0.0, 1.0) to (1 - offset.y / height).toDouble().coerceIn(0.0, 1.0)

private const val HIT_RADIUS = 0.08
private const val MINIMUM_GAP = 0.01
private const val GRID_LINES = 4
private const val CURVE_STEPS = 64
