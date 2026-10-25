package com.mobyle.nanquim.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.animation.AnimationExportPlan
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.animation.animationExportPlan
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.engine.AnimationFileWriter
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.InterchangeFiles
import android.graphics.Bitmap
import java.io.File
import java.nio.ByteBuffer
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

enum class AnimationFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
) {
    Gif("gif", "gif", "image/gif"),
    Apng("apng", "png", "image/apng"),
    Mp4("mp4", "mp4", "video/mp4"),
    WebM("webm", "webm", "video/webm"),

    PngSequence("pngSequence", "zip", "application/zip"),
    ;

    companion object {
        fun fromId(id: String): AnimationFormat? = entries.firstOrNull { it.id == id }
    }
}

data class AnimationExportSettings(
    val firstFrame: Int = 1,
    val lastFrame: Int = Int.MAX_VALUE,
    val framesPerSecond: Int? = null,
    val scalePercent: Int = 100,
    val plays: Int? = null,
    val includeBackground: Boolean = true,
    val dither: AnimationFileWriter.Dither = AnimationFileWriter.Dither.FloydSteinberg,
    val hevc: Boolean = false,
) {
    companion object {
        val SCALES = listOf(100, 50, 25)
    }
}

class AnimationExport(
    private val engine: Engine,
    private val canvasSession: CanvasSession,
    private val cacheDirectory: File,
) {
    var onProgress: (Int, Int) -> Unit = { _, _ -> }

    suspend fun export(
        format: AnimationFormat,
        settings: AnimationExportSettings,
        output: OutputStream,
    ) {
        val session = canvasSession.documentSession ?: throw GalleryException(GalleryError.WriteFailed, "No open artwork")
        val state = session.state
        if (!state.animation.enabled) throw GalleryException(GalleryError.WriteFailed, "Animation is off")
        val plan = state.animationExportPlan(settings.firstFrame, settings.lastFrame, settings.framesPerSecond)
        if (plan.slots.isEmpty()) throw GalleryException(GalleryError.WriteFailed, "No frames")
        val divisor = (100 / settings.scalePercent.coerceIn(25, 100)).let { if (it >= 4) 4 else if (it >= 2) 2 else 1 }
        var width = state.widthPx / divisor
        var height = state.heightPx / divisor
        val video = format == AnimationFormat.Mp4 || format == AnimationFormat.WebM
        if (video) {
            width -= width % 2
            height -= height % 2
        }
        val background = state.background.color
        val plays = settings.plays ?: if (state.animation.playback == PlaybackMode.OneShot) 1 else 0
        canvasSession.playback.stop()
        val file = File(cacheDirectory, "animation-${System.nanoTime()}.${format.extension}")
        session.exportHidesBackground = !settings.includeBackground && !video
        session.exportHidesPrivate = true
        try {
            val frames = Frames(session, plan, divisor, width, height, if (video) background else null)
            withContext(Dispatchers.IO) { file.delete() }
            when (format) {
                AnimationFormat.Gif, AnimationFormat.Apng -> writeAnimatedImage(format, file, frames, plays, settings.dither)
                AnimationFormat.Mp4, AnimationFormat.WebM -> writeVideo(format, file, frames, plan, settings.hevc)
                AnimationFormat.PngSequence -> writePngSequence(frames, plan, output)
            }
            if (format != AnimationFormat.PngSequence) withContext(Dispatchers.IO) { file.inputStream().use { it.copyTo(output) } }
        } finally {
            session.playbackFrameId = null
            session.exportHidesBackground = false
            session.exportHidesPrivate = false
            onProgress(0, 0)
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    private suspend fun writeAnimatedImage(
        format: AnimationFormat,
        file: File,
        frames: Frames,
        plays: Int,
        dither: AnimationFileWriter.Dither,
    ) {
        val writerFormat = if (format == AnimationFormat.Gif) AnimationFileWriter.Format.Gif else AnimationFileWriter.Format.Apng
        val writer =
            AnimationFileWriter.open(file.path, writerFormat, frames.width, frames.height, plays, dither)
                ?: throw GalleryException(GalleryError.WriteFailed, "Cannot create ${file.name}")
        writer.use {
            frames.forEachMerged { pixels, durationMilliseconds ->
                withContext(Dispatchers.IO) { writer.addFrame(pixels, durationMilliseconds) }
            }
            val error = withContext(Dispatchers.IO) { writer.finish() }
            if (error != null) throw GalleryException(GalleryError.WriteFailed, error)
        }
    }

    private suspend fun writeVideo(
        format: AnimationFormat,
        file: File,
        frames: Frames,
        plan: AnimationExportPlan,
        hevc: Boolean,
    ) {
        val mime =
            when {
                format == AnimationFormat.WebM -> VideoFileEncoder.VP9
                hevc -> VideoFileEncoder.HEVC
                else -> VideoFileEncoder.AVC
            }
        val (width, height) =
            withContext(Dispatchers.IO) { VideoFileEncoder.largestSize(mime, frames.width, frames.height) }
                ?: throw GalleryException(GalleryError.WriteFailed, "No $mime encoder")
        val encoder =
            withContext(Dispatchers.IO) {
                VideoFileEncoder.create(file.path, mime, width, height, plan.framesPerSecond, format == AnimationFormat.WebM)
            } ?: throw GalleryException(GalleryError.WriteFailed, "No $mime encoder for $width × $height")
        try {
            var startMicroseconds = 0L
            frames.forEachMerged { pixels, durationMilliseconds ->
                val start = startMicroseconds
                val sized = if (width == frames.width && height == frames.height) pixels else withContext(Dispatchers.Default) { resized(pixels, frames.width, frames.height, width, height) }
                withContext(Dispatchers.IO) { encoder.addFrame(sized, start) }
                startMicroseconds += durationMilliseconds * 1000L
            }
            val ok = withContext(Dispatchers.IO) { encoder.finish(startMicroseconds) }
            if (!ok) throw GalleryException(GalleryError.WriteFailed, "Video encoding failed")
        } finally {
            withContext(Dispatchers.IO) { encoder.release() }
        }
    }

    private suspend fun writePngSequence(
        frames: Frames,
        plan: AnimationExportPlan,
        output: OutputStream,
    ) {
        val zip = ZipOutputStream(output)
        val png = File(cacheDirectory, "animation-frame-${System.nanoTime()}.png")
        try {
            val digits = maxOf(4, plan.slots.size.toString().length)
            var slot = 0
            frames.forEachMerged { pixels, durationMilliseconds ->
                val bytes =
                    withContext(Dispatchers.IO) {
                        val result = InterchangeFiles.exportPng(pixels, frames.width, frames.height, 0.0, png.path)
                        if (!result.ok) throw GalleryException(GalleryError.WriteFailed, result.errorCode ?: "png")
                        png.readBytes()
                    }
                val repeats = frames.slotsOf(durationMilliseconds)
                withContext(Dispatchers.IO) {
                    repeat(repeats) {
                        slot++
                        val entry = ZipEntry("frame_${slot.toString().padStart(digits, '0')}.png")
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
            withContext(Dispatchers.IO) { zip.finish() }
        } finally {
            withContext(Dispatchers.IO) { png.delete() }
        }
    }

    private fun resized(
        rgba: ByteArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        val out = ByteBuffer.allocate(targetWidth * targetHeight * 4)
        scaled.copyPixelsToBuffer(out)
        source.recycle()
        scaled.recycle()
        return out.array()
    }

    private fun composedOver(
        source: ByteArray,
        color: RgbaColor,
    ): ByteArray {
        val out = source.copyOf()
        val red = (color.red * 255).roundToInt()
        val green = (color.green * 255).roundToInt()
        val blue = (color.blue * 255).roundToInt()
        var offset = 0
        while (offset < out.size) {
            val alpha = out[offset + 3].toInt() and 0xFF
            if (alpha != 255) {
                val rest = 255 - alpha
                out[offset] = (((out[offset].toInt() and 0xFF) * alpha + red * rest + 127) / 255).toByte()
                out[offset + 1] = (((out[offset + 1].toInt() and 0xFF) * alpha + green * rest + 127) / 255).toByte()
                out[offset + 2] = (((out[offset + 2].toInt() and 0xFF) * alpha + blue * rest + 127) / 255).toByte()
                out[offset + 3] = 255.toByte()
            }
            offset += 4
        }
        return out
    }

    private inner class Frames(
        private val session: com.mobyle.nanquim.document.session.DocumentSession,
        private val plan: AnimationExportPlan,
        private val divisor: Int,
        val width: Int,
        val height: Int,
        private val opaqueOver: RgbaColor?,
    ) {
        private val merged = plan.frames

        fun slotsOf(durationMilliseconds: Int): Int = maxOf(1, (durationMilliseconds * plan.framesPerSecond / 1000.0).roundToInt())

        suspend fun forEachMerged(action: suspend (ByteArray, Int) -> Unit) {
            merged.forEachIndexed { index, frame ->
                onProgress(index, merged.size)
                session.playbackFrameId = frame.frameId
                val render = readComposite()
                val pixels = withContext(Dispatchers.Default) { prepare(render.pixels, render.width, render.height) }
                action(pixels, frame.durationMilliseconds)
            }
            onProgress(merged.size, merged.size)
        }

        private suspend fun readComposite(): com.mobyle.nanquim.engine.SceneRender {
            val result = CompletableDeferred<com.mobyle.nanquim.engine.SceneRender>()
            engine.readComposite(false) { result.complete(it) }
            val render = result.await()
            render.error?.let { throw GalleryException(GalleryError.Unreadable, it) }
            return render
        }

        private fun prepare(
            source: ByteArray,
            sourceWidth: Int,
            sourceHeight: Int,
        ): ByteArray {
            val over = opaqueOver
            if (divisor == 1 && sourceWidth == width && sourceHeight == height) {
                return if (over == null) source else composedOver(source, over)
            }
            val out = ByteArray(width * height * 4)
            val area = divisor * divisor
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var red = 0L
                    var green = 0L
                    var blue = 0L
                    var alpha = 0L
                    for (dy in 0 until divisor) {
                        val sy = minOf(y * divisor + dy, sourceHeight - 1)
                        for (dx in 0 until divisor) {
                            val sx = minOf(x * divisor + dx, sourceWidth - 1)
                            val offset = (sy * sourceWidth + sx) * 4
                            val a = source[offset + 3].toLong() and 0xFF
                            red += (source[offset].toLong() and 0xFF) * a
                            green += (source[offset + 1].toLong() and 0xFF) * a
                            blue += (source[offset + 2].toLong() and 0xFF) * a
                            alpha += a
                        }
                    }
                    val target = (y * width + x) * 4
                    var r = if (alpha > 0) red.toDouble() / alpha else 0.0
                    var g = if (alpha > 0) green.toDouble() / alpha else 0.0
                    var b = if (alpha > 0) blue.toDouble() / alpha else 0.0
                    var a = alpha.toDouble() / area
                    if (over != null) {
                        val coverage = a / 255.0
                        r = r * coverage + over.red * 255.0 * (1 - coverage)
                        g = g * coverage + over.green * 255.0 * (1 - coverage)
                        b = b * coverage + over.blue * 255.0 * (1 - coverage)
                        a = 255.0
                    }
                    out[target] = r.roundToInt().coerceIn(0, 255).toByte()
                    out[target + 1] = g.roundToInt().coerceIn(0, 255).toByte()
                    out[target + 2] = b.roundToInt().coerceIn(0, 255).toByte()
                    out[target + 3] = a.roundToInt().coerceIn(0, 255).toByte()
                }
            }
            return out
        }
    }
}
