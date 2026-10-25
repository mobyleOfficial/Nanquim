package com.mobyle.nanquim.document

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import com.mobyle.nanquim.engine.Engine
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

enum class ImageFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
) {
    Png("png", "png", "image/png"),
    Jpeg("jpeg", "jpg", "image/jpeg"),
    WebP("webp", "webp", "image/webp"),
    ;

    companion object {
        fun fromId(id: String): ImageFormat? = entries.firstOrNull { it.id == id }
    }
}

data class ImagePlacement(
    val left: Float,
    val top: Float,
    val scale: Float,
)

class ImageColor(
    val colorSpace: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB),
    val convert: ((ByteArray) -> Unit)? = null,
)

class ImageTransfer(
    private val contentResolver: ContentResolver,
) {
    fun colorSpace(uri: Uri): ColorSpace? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outColorSpace
    }

    fun size(uri: Uri): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) return options.outWidth to options.outHeight
        return try {
            var size: Pair<Int, Int>? = null
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                size = info.size.width to info.size.height
                decoder.setTargetSize(1, 1)
            }
            size
        } catch (error: IOException) {
            null
        }
    }

    fun writeToSurface(
        uri: Uri,
        engine: Engine,
        surfaceId: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        placement: ImagePlacement,
        color: ImageColor = ImageColor(),
    ) {
        val unscaled = placement.scale == 1f && placement.left == 0f && placement.top == 0f
        if (unscaled && writeByRegions(uri, engine, surfaceId, canvasWidth, canvasHeight, color)) return
        val (width, height) = size(uri) ?: throw IOException("Unreadable image")
        val targetWidth = (width * placement.scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * placement.scale).roundToInt().coerceAtLeast(1)
        val image =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(color.colorSpace)
                decoder.setTargetSize(targetWidth, targetHeight)
            }
        try {
            writeBitmapToSurface(
                image,
                engine,
                surfaceId,
                canvasWidth,
                canvasHeight,
                ImagePlacement(placement.left, placement.top, 1f),
                color.convert,
            )
        } finally {
            image.recycle()
        }
    }

    fun writeBitmapToSurface(
        image: Bitmap,
        engine: Engine,
        surfaceId: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        placement: ImagePlacement,
        convert: ((ByteArray) -> Unit)? = null,
    ) {
        val targetWidth = image.width * placement.scale
        val targetHeight = image.height * placement.scale
        val band = bandBitmap(canvasWidth, image.colorSpace)
        val canvas = Canvas(band)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val source = Rect(0, 0, image.width, image.height)
        val firstRow = (placement.top / TILE_SIZE).toInt().coerceAtLeast(0)
        val endRow = min(ceil((placement.top + targetHeight) / TILE_SIZE).toInt(), tileRows(canvasHeight))
        for (row in firstRow until endRow) {
            band.eraseColor(0)
            val top = placement.top - row * TILE_SIZE
            canvas.drawBitmap(image, source, RectF(placement.left, top, placement.left + targetWidth, top + targetHeight), paint)
            sendBand(engine, surfaceId, row, band, canvasWidth, canvasHeight, convert)
        }
        band.recycle()
    }

    private fun writeByRegions(
        uri: Uri,
        engine: Engine,
        surfaceId: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        color: ImageColor,
    ): Boolean {
        val decoder =
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(stream)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(stream, false)
                    }
                }
            } catch (error: IOException) {
                null
            } ?: return false
        try {
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = color.colorSpace
                }
            val width = min(decoder.width, canvasWidth)
            val band = bandBitmap(canvasWidth, color.colorSpace)
            val canvas = Canvas(band)
            for (row in 0 until tileRows(min(decoder.height, canvasHeight))) {
                val top = row * TILE_SIZE
                val bottom = min(top + TILE_SIZE, min(decoder.height, canvasHeight))
                val region = decoder.decodeRegion(Rect(0, top, width, bottom), options) ?: return false
                band.eraseColor(0)
                canvas.drawBitmap(region, 0f, 0f, null)
                region.recycle()
                sendBand(engine, surfaceId, row, band, canvasWidth, canvasHeight, color.convert)
            }
            band.recycle()
            return true
        } finally {
            decoder.recycle()
        }
    }

    private fun bandBitmap(
        width: Int,
        colorSpace: ColorSpace?,
    ): Bitmap = Bitmap.createBitmap(width, TILE_SIZE, Bitmap.Config.ARGB_8888, true, colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))

    private fun sendBand(
        engine: Engine,
        surfaceId: Int,
        row: Int,
        band: Bitmap,
        canvasWidth: Int,
        canvasHeight: Int,
        convert: ((ByteArray) -> Unit)?,
    ) {
        val rows = min(TILE_SIZE, canvasHeight - row * TILE_SIZE)
        val pixels = ByteBuffer.allocate(canvasWidth * TILE_SIZE * 4)
        band.copyPixelsToBuffer(pixels)
        val bytes = pixels.array().copyOf(canvasWidth * rows * 4)
        convert?.invoke(bytes)
        engine.writeSurfaceTileRow(surfaceId, row, bytes)
        waitForEngine(engine)
    }

    private fun waitForEngine(engine: Engine) {
        while (true) {
            val latch = CountDownLatch(1)
            var pending = 0
            engine.requestStats { stats ->
                pending = stats.pendingSurfaceWrites
                latch.countDown()
            }
            if (!latch.await(STATS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || pending <= MAXIMUM_PENDING_ROWS) return
            Thread.sleep(PENDING_POLL_MILLISECONDS)
        }
    }

    fun encode(
        width: Int,
        height: Int,
        unpremultipliedRgba: ByteArray,
        format: ImageFormat,
        quality: Int,
        lossless: Boolean,
        output: OutputStream,
    ): Boolean {
        val pixels = if (format == ImageFormat.Jpeg) flattenedOnWhite(unpremultipliedRgba, width * height) else unpremultipliedRgba
        val bitmap = RgbaImages.toBitmap(width, height, pixels)
        val compressFormat =
            when (format) {
                ImageFormat.Png -> Bitmap.CompressFormat.PNG
                ImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
                ImageFormat.WebP ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
            }
        val effectiveQuality = if (format == ImageFormat.WebP && lossless) 100 else quality
        val ok = RgbaImages.encode(bitmap, compressFormat, effectiveQuality, output)
        bitmap.recycle()
        return ok
    }

    private fun flattenedOnWhite(
        rgba: ByteArray,
        pixelCount: Int,
    ): ByteArray {
        val out = ByteArray(pixelCount * 4)
        for (pixel in 0 until pixelCount) {
            val alpha = rgba[pixel * 4 + 3].toInt() and 0xFF
            for (channel in 0 until 3) {
                val value = rgba[pixel * 4 + channel].toInt() and 0xFF
                out[pixel * 4 + channel] = ((value * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
            }
            out[pixel * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    companion object {
        const val TILE_SIZE = 256
        private const val MAXIMUM_PENDING_ROWS = 2
        private const val STATS_TIMEOUT_SECONDS = 5L
        private const val PENDING_POLL_MILLISECONDS = 16L

        fun tileRows(height: Int) = (height + TILE_SIZE - 1) / TILE_SIZE
    }
}
