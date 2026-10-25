package com.mobyle.nanquim.brush

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.engine.BrushPad
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

class BrushThumbnails(
    private val library: BrushLibrary,
    private val cacheDirectory: File,
) {
    private val memory = LruCache<String, ImageBitmap>(MEMORY_ENTRIES)
    private val animations = LruCache<String, BrushAnimation>(ANIMATION_ENTRIES)
    private val rendering = Dispatchers.Default.limitedParallelism(RENDER_THREADS)

    suspend fun thumbnail(brushId: String): BrushThumbnail? = packageOf(brushId)?.let { thumbnail(it) }

    suspend fun thumbnail(brushPackage: BrushPackage): BrushThumbnail? {
        val key = keyOf(brushPackage)
        val colored = isColored(brushPackage)
        memory.get(key)?.let { return BrushThumbnail(it, colored) }
        val file = File(cacheDirectory, "$key.png")
        val bitmap =
            withContext(rendering) {
                if (file.isFile) {
                    BitmapFactory.decodeFile(file.path)
                } else {
                    render(brushPackage)?.also { rendered ->
                        cacheDirectory.mkdirs()
                        file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                    }
                }
            } ?: return null
        return BrushThumbnail(bitmap.asImageBitmap().also { memory.put(key, it) }, colored)
    }

    suspend fun animation(
        brushId: String,
        large: Boolean = false,
    ): BrushAnimation? {
        val brushPackage = packageOf(brushId) ?: return null
        val key = keyOf(brushPackage)
        if (!large) animations.get(key)?.let { return it }
        val width = if (large) LARGE_WIDTH else WIDTH
        val height = if (large) LARGE_HEIGHT else HEIGHT
        val frames =
            withContext(rendering) {
                val document = brushPackage.document
                val pixels =
                    BrushPad.renderThumbnailFrames(
                        brushJson = document.toJsonText(),
                        shapePng = brushPackage.shapeTextureBytes,
                        grainPng = brushPackage.grainTextureBytes,
                        previewSize = (document.number("properties.previewSize") ?: DEFAULT_PREVIEW_SIZE).toFloat(),
                        stampPreview = document.boolean("properties.stampPreview") ?: false,
                        width = width,
                        height = height,
                        frameCount = FRAME_COUNT,
                    ) ?: return@withContext null
                val frameBytes = width * height * BYTES_PER_PIXEL
                List(FRAME_COUNT) { frame ->
                    Bitmap
                        .createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        .apply { copyPixelsFromBuffer(ByteBuffer.wrap(pixels, frame * frameBytes, frameBytes)) }
                        .asImageBitmap()
                }
            } ?: return null
        return BrushAnimation(frames, isColored(brushPackage)).also { if (!large) animations.put(key, it) }
    }

    private suspend fun packageOf(brushId: String): BrushPackage? =
        try {
            library.loadPackage(brushId)
        } catch (exception: Exception) {
            Log.w(TAG, "No package for $brushId", exception)
            null
        }

    private fun isColored(brushPackage: BrushPackage) =
        brushPackage.shapeTextureBytes != null && brushPackage.document.boolean("stamp.keepColors") == true

    private fun render(brushPackage: BrushPackage): Bitmap? {
        val document = brushPackage.document
        val pixels =
            BrushPad.renderThumbnail(
                brushJson = document.toJsonText(),
                shapePng = brushPackage.shapeTextureBytes,
                grainPng = brushPackage.grainTextureBytes,
                previewSize = (document.number("properties.previewSize") ?: DEFAULT_PREVIEW_SIZE).toFloat(),
                stampPreview = document.boolean("properties.stampPreview") ?: false,
                width = WIDTH,
                height = HEIGHT,
            ) ?: return null
        return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        }
    }

    private fun keyOf(brushPackage: BrushPackage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(brushPackage.document.toJsonText().toByteArray())
        brushPackage.shapeTextureBytes?.let(digest::update)
        brushPackage.grainTextureBytes?.let(digest::update)
        digest.update(RENDER_VERSION.toByteArray())
        return digest.digest().take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "BrushThumbnails"
        const val WIDTH = 512
        const val HEIGHT = 128
        private const val DEFAULT_PREVIEW_SIZE = 0.5
        private const val MEMORY_ENTRIES = 256
        private const val ANIMATION_ENTRIES = 4
        private const val BYTES_PER_PIXEL = 4
        const val LARGE_WIDTH = 1024
        const val LARGE_HEIGHT = 256
        const val FRAME_COUNT = 18
        const val ANIMATION_MILLISECONDS = 600L
        private const val RENDER_THREADS = 2
        private const val PNG_QUALITY = 100
        private const val KEY_BYTES = 16

        private const val RENDER_VERSION = "1"
    }
}

data class BrushThumbnail(
    val image: ImageBitmap,
    val colored: Boolean,
)

class BrushAnimation(
    val frames: List<ImageBitmap>,
    val colored: Boolean,
)
