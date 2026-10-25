package com.mobyle.nanquim.brush

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object TextureImport {
    private const val PNG_QUALITY = 100
    private const val RED_WEIGHT = 0.299f
    private const val GREEN_WEIGHT = 0.587f
    private const val BLUE_WEIGHT = 0.114f

    fun read(
        resolver: ContentResolver,
        uri: Uri,
        slot: TextureSlot,
        keepColors: Boolean = false,
    ): ByteArray? {
        val maximumSide = if (slot == TextureSlot.Shape) MAXIMUM_SHAPE_SIDE else MAXIMUM_GRAIN_SIDE
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val crop =
            if (slot == TextureSlot.Shape) {
                val side = min(source.width, source.height)
                Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
            } else {
                Rect(0, 0, source.width, source.height)
            }
        val scale = min(1f, maximumSide.toFloat() / max(crop.width(), crop.height()))
        val width = max(1, (crop.width() * scale).roundToInt())
        val height = max(1, (crop.height() * scale).roundToInt())
        val scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(source, crop, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            if (keepColors) break
            val color = pixels[index]
            val luminance = RED_WEIGHT * Color.red(color) + GREEN_WEIGHT * Color.green(color) + BLUE_WEIGHT * Color.blue(color)
            val gray = (luminance * Color.alpha(color) / 255f).roundToInt().coerceIn(0, 255)
            pixels[index] = Color.rgb(gray, gray, gray)
        }
        scaled.setPixels(pixels, 0, width, 0, 0, width, height)
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        scaled.recycle()
        return output.toByteArray()
    }

    fun grayPng(
        side: Int,
        values: ByteArray,
    ): ByteArray {
        val pixels = IntArray(side * side) { index -> (values[index].toInt() and 0xFF).let { Color.rgb(it, it, it) } }
        val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        bitmap.recycle()
        return output.toByteArray()
    }

    const val MAXIMUM_SHAPE_SIDE = 1024
    const val MAXIMUM_GRAIN_SIDE = 2048
}
