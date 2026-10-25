package com.mobyle.nanquim.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.roundToInt

data class HoverPoint(
    val x: Float,
    val y: Float,
    val orientation: Float,
)

class HoverTipImages(
    val side: Int,
    val fill: Bitmap,
    val outline: Bitmap,
)

object HoverTip {
    private const val MAXIMUM_SIDE = 256
    private const val CHANNEL = 0xFF
    private const val HALF = 128
    private const val WHITE = 0x00FFFFFF

    fun outlineMask(
        coverage: IntArray,
        side: Int,
    ): BooleanArray =
        BooleanArray(side * side) { index ->
            val x = index % side
            val y = index / side
            if (coverage[index] < HALF) return@BooleanArray false
            val neighbors = listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)
            neighbors.any { (nx, ny) -> nx !in 0 until side || ny !in 0 until side || coverage[ny * side + nx] < HALF }
        }

    fun load(
        png: ByteArray,
        keepColors: Boolean,
    ): HoverTipImages? {
        val decoded = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        val side = minOf(MAXIMUM_SIDE, max(decoded.width, decoded.height))
        val scaled = Bitmap.createScaledBitmap(decoded, side, side, true)
        if (scaled != decoded) decoded.recycle()
        val pixels = IntArray(side * side)
        scaled.getPixels(pixels, 0, side, 0, 0, side, side)
        scaled.recycle()
        val coverage =
            IntArray(pixels.size) { index ->
                val color = pixels[index]
                val alpha = (color ushr 24) and CHANNEL
                if (keepColors) {
                    alpha
                } else {
                    (((color shr 8) and CHANNEL) * alpha / CHANNEL.toFloat()).roundToInt()
                }
            }
        val fill =
            IntArray(pixels.size) { index ->
                if (keepColors) pixels[index] else (coverage[index] shl 24) or WHITE
            }
        val edge = outlineMask(coverage, side)
        val outline = IntArray(pixels.size) { index -> if (edge[index]) -1 else 0 }
        return HoverTipImages(
            side,
            Bitmap.createBitmap(fill, side, side, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(outline, side, side, Bitmap.Config.ARGB_8888),
        )
    }
}
