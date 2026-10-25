package com.mobyle.nanquim.brush

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class StampMode(
    val key: String,
) {
    Luminance("luminance"),

    Alpha("alpha"),

    Color("color"),
    ;

    companion object {
        fun of(key: String): StampMode? = entries.firstOrNull { it.key == key }
    }
}

class SquareTip(
    val side: Int,
    val pixels: IntArray,
)

object StampImport {
    private const val PNG_QUALITY = 100
    private const val RED_WEIGHT = 0.299f
    private const val GREEN_WEIGHT = 0.587f
    private const val BLUE_WEIGHT = 0.114f
    private const val OPAQUE = 0xFF000000.toInt()
    private const val CHANNEL = 0xFF

    private const val DEFAULT_SIZE = 0.5
    private const val MAXIMUM_SIZE = 1000.0

    fun coverage(
        color: Int,
        mode: StampMode,
    ): Int {
        val alpha = (color ushr 24) and CHANNEL
        if (mode != StampMode.Luminance) return alpha
        val red = (color shr 16) and CHANNEL
        val green = (color shr 8) and CHANNEL
        val luminance = RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * (color and CHANNEL)
        return (luminance * alpha / CHANNEL).roundToInt().coerceIn(0, CHANNEL)
    }

    fun squareTip(
        pixels: IntArray,
        width: Int,
        height: Int,
        mode: StampMode,
    ): SquareTip? {
        require(pixels.size == width * height) { "pixels do not match the size" }
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (coverage(pixels[y * width + x], mode) > 0) {
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < 0) return null
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        val side = max(cropWidth, cropHeight)
        val offsetX = (side - cropWidth) / 2
        val offsetY = (side - cropHeight) / 2
        val padding = if (mode == StampMode.Color) 0 else OPAQUE
        val tip = IntArray(side * side) { padding }
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                val color = pixels[(top + y) * width + left + x]
                tip[(offsetY + y) * side + offsetX + x] =
                    if (mode == StampMode.Color) {
                        color
                    } else {
                        val gray = coverage(color, mode)
                        OPAQUE or (gray shl 16) or (gray shl 8) or gray
                    }
            }
        }
        return SquareTip(side, tip)
    }

    fun png(tip: SquareTip): ByteArray {
        val source = Bitmap.createBitmap(tip.pixels, tip.side, tip.side, Bitmap.Config.ARGB_8888)
        val side = min(tip.side, TextureImport.MAXIMUM_SHAPE_SIDE)
        val scaled =
            if (side == tip.side) {
                source
            } else {
                Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(source, null, Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
                    source.recycle()
                }
            }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        scaled.recycle()
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): Triple<IntArray, Int, Int>? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Triple(pixels, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    fun brush(
        name: String,
        shapePng: ByteArray,
        mode: StampMode,
    ): BrushPackage {
        val now = Instant.now().toString()
        val text =
            """
            {
              "format": 1,
              "id": "${UUID.randomUUID()}",
              "name": "stamp",
              "createdAt": "$now",
              "modifiedAt": "$now",
              "shape": { "texture": "${TextureSlot.Shape.defaultFileName}", "rotationMode": "fixed" },
              "stamp": { "enabled": true, "keepColors": ${mode == StampMode.Color} },
              "render": { "accumulation": "buildUp" },
              "limits": { "maxSize": $MAXIMUM_SIZE },
              "defaults": { "size": $DEFAULT_SIZE },
              "properties": { "stampPreview": true }
            }
            """.trimIndent()
        val document = BrushDocument.parse(text).with("name", name)
        return BrushPackage(document, mapOf(TextureSlot.Shape.defaultFileName to shapePng))
    }
}
