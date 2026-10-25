package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.EyedropperLoupe
import com.mobyle.nanquim.canvas.HistoryToast
import com.mobyle.nanquim.canvas.LayerSelectToast
import com.mobyle.nanquim.canvas.SrgbColor
import com.mobyle.nanquim.canvas.titleRes
import kotlin.math.roundToInt

@Composable
fun HistoryToastView(
    toast: HistoryToast,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(toast.serial) {
        delay(TOAST_MILLIS)
        onTimeout()
    }
    val label = stringResource(toast.label.titleRes())
    val text = stringResource(if (toast.undone) R.string.history_undone else R.string.history_redone, label)
    Surface(
        modifier.padding(top = 72.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun LayerSelectToastView(
    toast: LayerSelectToast,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(toast.serial) {
        delay(TOAST_MILLIS)
        onTimeout()
    }
    Surface(
        modifier.padding(top = 72.dp).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Text(stringResource(R.string.layer_select_toast, toast.layerName), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EyedropperLoupeView(loupe: EyedropperLoupe) {
    val density = LocalDensity.current
    val sizePx = with(density) { LOUPE_SIZE.toPx() }
    val liftPx = with(density) { LOUPE_LIFT.toPx() }
    Box(
        Modifier
            .offset { IntOffset((loupe.screenX - sizePx / 2).roundToInt(), (loupe.screenY - liftPx - sizePx).roundToInt()) }
            .size(LOUPE_SIZE),
    ) {
        val sampled = loupe.color ?: loupe.previous
        Canvas(Modifier.size(LOUPE_SIZE)) {
            val ring = size.minDimension * 0.18f
            val inset = ring / 2
            val arcSize = Size(size.width - ring, size.height - ring)
            drawArc(sampled.toCompose(), 180f, 180f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(ring))
            drawArc(loupe.previous.toCompose(), 0f, 180f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(ring))
            drawCircle(Color.White, radius = size.minDimension / 2, style = Stroke(2f))
            drawCircle(Color.Black, radius = size.minDimension / 2 - ring - 1f, style = Stroke(2f))
        }
    }
}

private fun SrgbColor.toCompose() = Color(red, green, blue)

private const val TOAST_MILLIS = 1500L
private val LOUPE_SIZE = 120.dp
private val LOUPE_LIFT = 40.dp
