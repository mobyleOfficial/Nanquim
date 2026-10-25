package com.mobyle.nanquim.document

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

object RgbaImages {
    fun toBitmap(
        width: Int,
        height: Int,
        unpremultipliedRgba: ByteArray,
    ): Bitmap {
        require(unpremultipliedRgba.size >= width * height * 4) { "Not enough pixels" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.isPremultiplied = false
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(unpremultipliedRgba, 0, width * height * 4))
        return bitmap
    }

    fun premultipliedToBitmap(
        width: Int,
        height: Int,
        premultipliedRgba: ByteArray,
    ): Bitmap =
        createBitmap(width, height).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(premultipliedRgba, 0, width * height * 4))
        }

    fun encode(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        output: OutputStream,
    ): Boolean = bitmap.compress(format, quality.coerceIn(0, 100), output)

    fun toPng(
        width: Int,
        height: Int,
        unpremultipliedRgba: ByteArray,
    ): ByteArray {
        val bitmap = toBitmap(width, height, unpremultipliedRgba)
        return ByteArrayOutputStream().use { stream ->
            encode(bitmap, Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }
}
