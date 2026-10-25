package com.mobyle.nanquim.color

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.camera.core.ImageProxy
import com.mobyle.nanquim.document.color.PaletteExtractor

object PaletteImages {
    const val MAXIMUM_DECODED_SIDE = 1024

    fun decode(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
            val longer = maxOf(info.size.width, info.size.height)
            if (longer > MAXIMUM_DECODED_SIDE) {
                val scale = MAXIMUM_DECODED_SIDE.toDouble() / longer
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }

    fun cameraPixels(image: ImageProxy): Triple<Int, Int, IntArray> {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = maxOf(1, maxOf(image.width, image.height) / PaletteExtractor.MAXIMUM_SIDE)
        val width = image.width / step
        val height = image.height / step
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * step * rowStride
            for (x in 0 until width) {
                val offset = row + x * step * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                pixels[y * width + x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Triple(width, height, pixels)
    }
}
