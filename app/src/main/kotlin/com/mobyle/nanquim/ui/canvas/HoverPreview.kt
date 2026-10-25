package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.HoverTip
import com.mobyle.nanquim.canvas.HoverTipImages
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.engine.BrushPad
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val DEFAULT_FILL_OPACITY = 0.2
private const val DEFAULT_HOVER_PRESSURE = 0.5
private const val RADIANS_TO_DEGREES = (180.0 / Math.PI).toFloat()

@Composable
fun HoverPreview(session: CanvasSession) {
    val hover = session.hoverPoint
    val tool =
        when (session.canvasTool) {
            CanvasTool.Brush -> BrushTool.Paint
            CanvasTool.Eraser -> BrushTool.Erase
            CanvasTool.Smudge -> BrushTool.Smudge
            else -> null
        }
    val brush = tool?.let { session.brushes[it] }
    val document = brush?.document

    @Suppress("UNUSED_VARIABLE")
    val version = session.viewport.cameraVersion
    val transform = session.viewport.canvasToScreen()
    val viewRotation = transform?.let { atan2(it.b, it.a) } ?: 0.0
    val sizePx = tool?.let { session.toolSettings(it).sizePx } ?: 0f
    val pressure = document?.number("hover.pressure") ?: DEFAULT_HOVER_PRESSURE
    val footprint by produceState<FloatArray?>(null, document, sizePx, pressure, viewRotation) {
        value =
            document?.let {
                withContext(Dispatchers.Default) {
                    BrushPad.tipFootprint(it.toJsonText(), sizePx, pressure.toFloat(), viewRotation.toFloat())
                }
            }
    }
    val keepColors = document?.boolean("stamp.keepColors") == true
    val tipImages by produceState<HoverTipImages?>(null, brush?.brushId, document) {
        value =
            brush?.takeIf { document?.shapeTexture != null }?.let {
                withContext(Dispatchers.IO) {
                    session.brushLibrary
                        .loadPackage(it.brushId)
                        .shapeTextureBytes
                        ?.let { png -> HoverTip.load(png, keepColors) }
                }
            }
    }
    if (hover == null || document == null || transform == null) return
    val tip = footprint ?: return
    val outline = document.boolean("hover.outline") ?: true
    val fill = document.boolean("hover.fill") ?: false
    if (!outline && !fill) return

    var angle = tip[1].toDouble()
    if (document.string("shape.rotationMode") == "penAzimuth" || document.boolean("shape.azimuth") == true) {
        angle += hover.orientation
    }
    if (document.boolean("roll.enabled") == true && session.stylusRollReported) {
        angle += hover.orientation * (document.number("roll.amount") ?: 1.0)
    }
    val scale = hypot(transform.a, transform.b)
    val diameter = (tip[0] * scale).toFloat()
    val roundness = tip[2]
    val color = session.primaryColor
    val fillColor =
        Color(color.red, color.green, color.blue)
            .copy(alpha = (document.number("hover.fillOpacity") ?: DEFAULT_FILL_OPACITY).toFloat().coerceIn(0f, 1f))
    val images = tipImages
    val textured = document.shapeTexture != null
    Canvas(Modifier.fillMaxSize()) {
        withTransform({
            translate(hover.x, hover.y)
            rotate(((angle + viewRotation).toFloat()) * RADIANS_TO_DEGREES, Offset.Zero)
            scale(1f, roundness, Offset.Zero)
        }) {
            val radius = diameter / 2
            if (textured && images != null) {
                drawTip(images, diameter, fill && tool != BrushTool.Erase, outline, fillColor, keepColors)
            } else if (!textured) {
                if (fill && tool != BrushTool.Erase) drawCircle(fillColor, radius, Offset.Zero)
                if (outline) {
                    drawCircle(Color.White, radius, Offset.Zero, style = Stroke(OUTLINE_UNDER_PX))
                    drawCircle(Color.Black, radius, Offset.Zero, style = Stroke(1f))
                }
            }
        }
    }
}

private const val OUTLINE_UNDER_PX = 3f

private fun DrawScope.drawTip(
    images: HoverTipImages,
    diameter: Float,
    fill: Boolean,
    outline: Boolean,
    fillColor: Color,
    keepColors: Boolean,
) {
    val side = diameter.roundToInt().coerceAtLeast(1)
    val offset = IntOffset(-side / 2, -side / 2)
    val size = IntSize(side, side)
    if (fill) {
        drawImage(
            images.fill.asImageBitmap(),
            dstOffset = offset,
            dstSize = size,
            alpha = if (keepColors) fillColor.alpha else 1f,
            colorFilter = if (keepColors) null else ColorFilter.tint(fillColor),
        )
    }
    if (outline) {
        val image = images.outline.asImageBitmap()
        drawImage(image, dstOffset = offset + IntOffset(1, 1), dstSize = size, colorFilter = ColorFilter.tint(Color.White))
        drawImage(image, dstOffset = offset, dstSize = size, colorFilter = ColorFilter.tint(Color.Black))
    }
}
