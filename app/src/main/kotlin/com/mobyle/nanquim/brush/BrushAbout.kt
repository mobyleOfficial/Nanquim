package com.mobyle.nanquim.brush

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import kotlin.math.min

object BrushAbout {
    const val AUTHOR_FILE = "author.png"
    const val SIGNATURE_FILE = "signature.png"
    const val SIGNATURE_WIDTH = 512
    const val SIGNATURE_HEIGHT = 128

    private const val AUTHOR_SIDE = 256
    private const val SIGNATURE_STROKE_PX = 6f
    private const val PNG_QUALITY = 100

    fun authorPicture(bytes: ByteArray): ByteArray? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val crop = min(source.width, source.height)
        val side = min(AUTHOR_SIDE, crop)
        val target = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val left = (source.width - crop) / 2
        val top = (source.height - crop) / 2
        Canvas(target).drawBitmap(source, Rect(left, top, left + crop, top + crop), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        return encode(target)
    }

    fun signature(strokes: List<List<Pair<Float, Float>>>): ByteArray? {
        val drawn = strokes.filter { it.isNotEmpty() }
        if (drawn.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(SIGNATURE_WIDTH, SIGNATURE_HEIGHT, Bitmap.Config.ARGB_8888)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = SIGNATURE_STROKE_PX
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                color = android.graphics.Color.BLACK
            }
        val canvas = Canvas(bitmap)
        for (stroke in drawn) {
            val path = Path()
            stroke.forEachIndexed { index, (x, y) ->
                val px = x.coerceIn(0f, 1f) * SIGNATURE_WIDTH
                val py = y.coerceIn(0f, 1f) * SIGNATURE_HEIGHT
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            if (stroke.size == 1) path.lineTo(stroke[0].first * SIGNATURE_WIDTH + 0.1f, stroke[0].second * SIGNATURE_HEIGHT)
            canvas.drawPath(path, paint)
        }
        return encode(bitmap)
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
        bitmap.recycle()
        return output.toByteArray()
    }
}
