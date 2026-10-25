package com.mobyle.nanquim.ui.color

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.document.color.ColorDisc
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscPoint
import com.mobyle.nanquim.document.color.DiscZoom
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun DiscPicker(
    color: ColorValue,
    zoom: DiscZoom,
    onZoomChange: (DiscZoom) -> Unit,
    onPick: (hue: Float, saturation: Float, brightness: Float) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val currentColor by rememberUpdatedState(color)
    val currentZoom by rememberUpdatedState(zoom)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val shaders = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ColorShaders() else null }
    val fallback = if (shaders == null) rememberFallbackImages(size, color.hue, zoom) else null

    Canvas(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                var lastTap: Tap? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geometry = DiscGeometry(Size(this.size.width.toFloat(), this.size.height.toFloat()))
                    val target = geometry.targetAt(down.position)
                    val previous = lastTap
                    val doubleTap =
                        previous != null &&
                            previous.target == target &&
                            down.uptimeMillis - previous.timeMillis <= viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - previous.position).getDistance() <= viewConfiguration.touchSlop * 4

                    fun pick(position: Offset) {
                        val color = currentColor
                        when (target) {
                            DiscTarget.Ring -> {
                                val offset = geometry.unitOffset(position)
                                currentOnPick(ColorDisc.hueAt(offset.x, offset.y), color.saturation, color.brightness)
                            }
                            DiscTarget.Disc -> {
                                val point = currentZoom.toDisc(geometry.discPoint(position))
                                val (saturation, brightness) = ColorDisc.saturationBrightnessAt(point.x, point.y)
                                currentOnPick(color.hue, saturation, brightness)
                            }
                        }
                    }
                    pick(down.position)
                    down.consume()
                    var gestureZoom = currentZoom
                    var pinching = false
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (target == DiscTarget.Disc && pressed.size >= 2) {
                            pinching = true
                            val focus = geometry.discPoint(event.calculateCentroid())
                            val pan = event.calculatePan()
                            val panInDisc = DiscPoint(pan.x / geometry.discRadius.toDouble(), -pan.y / geometry.discRadius.toDouble())
                            gestureZoom = gestureZoom.pinched(event.calculateZoom().toDouble(), focus, panInDisc)
                            currentOnZoomChange(gestureZoom)
                        } else if (!pinching) {
                            val change = event.changes.firstOrNull { it.id == down.id && it.pressed }
                            if (change != null) {
                                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (change.position != change.previousPosition) pick(change.position)
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    lastTap =
                        if (!pinching && !moved) {
                            if (doubleTap) {
                                val color = currentColor
                                when (target) {
                                    DiscTarget.Ring -> {
                                        val offset = geometry.unitOffset(down.position)
                                        currentOnPick(
                                            ColorDisc.snapHue(ColorDisc.hueAt(offset.x, offset.y)),
                                            color.saturation,
                                            color.brightness,
                                        )
                                    }
                                    DiscTarget.Disc -> {
                                        val point = currentZoom.toDisc(geometry.discPoint(down.position))
                                        val (tappedSaturation, tappedBrightness) = ColorDisc.saturationBrightnessAt(point.x, point.y)
                                        val (saturation, brightness) =
                                            ColorDisc.snapSaturationBrightness(
                                                tappedSaturation,
                                                tappedBrightness,
                                            )
                                        currentOnPick(color.hue, saturation, brightness)
                                    }
                                }
                                null
                            } else {
                                Tap(target, down.position, down.uptimeMillis)
                            }
                        } else {
                            null
                        }
                }
            },
    ) {
        val geometry = DiscGeometry(this.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shaders != null) {
            drawWithShaders(shaders, geometry, color.hue, zoom)
        } else if (fallback != null) {
            drawFallback(geometry, fallback)
        }
        drawMarkers(geometry, color, zoom)
    }
}

private enum class DiscTarget { Ring, Disc }

private data class Tap(
    val target: DiscTarget,
    val position: Offset,
    val timeMillis: Long,
)

private class DiscGeometry(
    size: Size,
) {
    val outerRadius = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val ringWidth = outerRadius * RING_FRACTION
    val ringMiddle = outerRadius - ringWidth / 2f
    val discRadius = outerRadius * DISC_FRACTION

    fun targetAt(position: Offset): DiscTarget {
        val distance = (position - center).getDistance()
        val boundary = (discRadius + (outerRadius - ringWidth)) / 2f
        return if (distance >= boundary) DiscTarget.Ring else DiscTarget.Disc
    }

    fun unitOffset(position: Offset): DiscPoint = DiscPoint((position.x - center.x).toDouble(), (center.y - position.y).toDouble())

    fun discPoint(position: Offset): DiscPoint =
        DiscPoint((position.x - center.x).toDouble() / discRadius, (center.y - position.y).toDouble() / discRadius)

    fun screenOf(
        point: DiscPoint,
        radius: Float,
    ): Offset = Offset(center.x + (point.x * radius).toFloat(), center.y - (point.y * radius).toFloat())
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawWithShaders(
    shaders: ColorShaders,
    geometry: DiscGeometry,
    hue: Float,
    zoom: DiscZoom,
) {
    shaders.setRing(geometry.center.x, geometry.center.y)
    drawCircle(ShaderBrush(shaders.ring), radius = geometry.ringMiddle, center = geometry.center, style = Stroke(geometry.ringWidth))
    shaders.setDisc(
        geometry.center.x,
        geometry.center.y,
        geometry.discRadius,
        hue,
        zoom.scale.toFloat(),
        zoom.center.x.toFloat(),
        zoom.center.y.toFloat(),
    )
    drawCircle(ShaderBrush(shaders.disc), radius = geometry.discRadius, center = geometry.center)
}

private class FallbackImages(
    val ring: ImageBitmap?,
    val disc: ImageBitmap?,
)

@Composable
private fun rememberFallbackImages(
    size: IntSize,
    hue: Float,
    zoom: DiscZoom,
): FallbackImages {
    val side = min(size.width, size.height)
    val ring by produceState<ImageBitmap?>(null, side) {
        if (side <= 0) return@produceState
        value = withContext(Dispatchers.Default) { image(ColorDiscPixels.ring(side, 1f - RING_FRACTION), side) }
    }
    val disc by produceState<ImageBitmap?>(null, hue, zoom) {
        value = withContext(Dispatchers.Default) { image(ColorDiscPixels.disc(FALLBACK_DISC_PIXELS, hue, zoom), FALLBACK_DISC_PIXELS) }
    }
    return FallbackImages(ring, disc)
}

private fun image(
    pixels: IntArray,
    side: Int,
): ImageBitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888).asImageBitmap()

private fun DrawScope.drawFallback(
    geometry: DiscGeometry,
    images: FallbackImages,
) {
    images.ring?.let { ring ->
        val side = (geometry.outerRadius * 2f).toInt()
        drawImage(
            ring,
            dstOffset = IntOffset((geometry.center.x - geometry.outerRadius).toInt(), (geometry.center.y - geometry.outerRadius).toInt()),
            dstSize = IntSize(side, side),
        )
    }
    images.disc?.let { disc ->
        val circle =
            Path().apply {
                addOval(Rect(geometry.center, geometry.discRadius))
            }
        clipPath(circle) {
            val side = (geometry.discRadius * 2f).toInt()
            drawImage(
                disc,
                dstOffset = IntOffset((geometry.center.x - geometry.discRadius).toInt(), (geometry.center.y - geometry.discRadius).toInt()),
                dstSize = IntSize(side, side),
            )
        }
    }
}

private fun DrawScope.drawMarkers(
    geometry: DiscGeometry,
    color: ColorValue,
    zoom: DiscZoom,
) {
    val outline = 2.dp.toPx()
    val hueColor = ColorValue.fromHsb(color.hue, 1f, 1f)
    val ringAt = geometry.screenOf(ColorDisc.ringPoint(color.hue), geometry.ringMiddle)
    val ringMarker = geometry.ringWidth * 0.42f
    drawCircle(Color(hueColor.red, hueColor.green, hueColor.blue), ringMarker, ringAt)
    drawCircle(Color.White, ringMarker, ringAt, style = Stroke(outline))
    drawCircle(Color.Black.copy(alpha = 0.5f), ringMarker + outline, ringAt, style = Stroke(outline / 2f))
    val view = zoom.toView(ColorDisc.discPoint(color.saturation, color.brightness))
    if (hypot(view.x, view.y) <= 1.0) {
        val discAt = geometry.screenOf(view, geometry.discRadius)
        val discMarker = DISC_MARKER.toPx()
        drawCircle(Color(color.red, color.green, color.blue), discMarker, discAt)
        drawCircle(Color.White, discMarker, discAt, style = Stroke(outline))
        drawCircle(Color.Black.copy(alpha = 0.5f), discMarker + outline, discAt, style = Stroke(outline / 2f))
    }
}

private const val RING_FRACTION = 0.16f

private const val DISC_FRACTION = 0.78f

private const val FALLBACK_DISC_PIXELS = 256
private val DISC_MARKER = 12.dp
