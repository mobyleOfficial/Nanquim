package com.mobyle.nanquim.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToInt

enum class TimelapseLength(
    val id: String,
) {
    Full("full"),

    ThirtySeconds("30s"),
    ;

    companion object {
        fun fromId(id: String?): TimelapseLength? = entries.firstOrNull { it.id == id }
    }
}

class TimelapseExport(
    private val segments: List<File>,
    private val length: TimelapseLength,
    private val cardLines: List<String> = emptyList(),
    private val onProgress: (Float) -> Unit = {},
) {
    private class Segment(
        val file: File,
        val format: MediaFormat,
        val width: Int,
        val height: Int,
        val frames: Int,
    )

    fun write(output: File): Boolean {
        val readable = segments.mapNotNull(::probe)
        if (readable.isEmpty()) return false
        val target = readable.last()
        val sameSize = readable.all { it.width == target.width && it.height == target.height && it.format.getString(MediaFormat.KEY_MIME) == target.format.getString(MediaFormat.KEY_MIME) }
        return if (length == TimelapseLength.Full && cardLines.isEmpty() && sameSize) {
            remux(readable, output)
        } else {
            reencode(readable, target.width, target.height, output)
        }
    }

    private fun probe(file: File): Segment? =
        runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                var frames = 0
                while (extractor.sampleTime >= 0) {
                    frames++
                    extractor.advance()
                }
                if (frames == 0) null else Segment(file, format, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), frames)
            } finally {
                extractor.release()
            }
        }.getOrNull()

    private fun remux(
        segments: List<Segment>,
        output: File,
    ): Boolean {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val track = muxer.addTrack(segments.first().format)
            muxer.start()
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var offset = 0L
            val total = segments.sumOf { it.frames }.toFloat()
            var done = 0
            for (segment in segments) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.file.path)
                    extractor.selectTrack((0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true })
                    var last = 0L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.set(0, size, offset + extractor.sampleTime, if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, buffer, info)
                        last = maxOf(last, extractor.sampleTime)
                        extractor.advance()
                        onProgress(++done / total)
                    }
                    offset += last + FRAME_MICROSECONDS
                } finally {
                    extractor.release()
                }
            }
            muxer.stop()
            return true
        } catch (error: Exception) {
            return false
        } finally {
            muxer.release()
        }
    }

    private fun reencode(
        segments: List<Segment>,
        width: Int,
        height: Int,
        output: File,
    ): Boolean {
        val encoder = VideoFileEncoder.create(output.path, VideoFileEncoder.AVC, width, height, FRAMES_PER_SECOND, webm = false) ?: return false
        var frame = 0
        fun add(rgba: ByteArray) {
            encoder.addFrame(rgba, frame++ * FRAME_MICROSECONDS)
        }
        try {
            val card = if (cardLines.isEmpty()) null else card(width, height)
            card?.let { repeat(CARD_FRAMES) { _ -> add(it) } }
            val total = segments.sumOf { it.frames }
            val spedUp = length == TimelapseLength.ThirtySeconds && total > SPED_UP_FRAMES
            var index = 0
            var lastFrame: ByteArray? = null
            for (segment in segments) {
                decode(segment, width, height) { rgba ->
                    val keep = !spedUp || keptWhenSpedUp(index, total, SPED_UP_FRAMES)
                    if (keep) add(rgba)
                    lastFrame = rgba
                    index++
                    onProgress(index.toFloat() / total)
                }
            }
            if (length == TimelapseLength.ThirtySeconds) lastFrame?.let { final -> repeat(FINAL_FRAMES) { add(final) } }
            card?.let { repeat(CARD_FRAMES) { _ -> add(it) } }
            return encoder.finish(frame * FRAME_MICROSECONDS)
        } catch (error: Exception) {
            return false
        } finally {
            encoder.release()
        }
    }

    private fun decode(
        segment: Segment,
        width: Int,
        height: Int,
        onFrame: (ByteArray) -> Unit,
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(segment.file.path)
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        try {
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_MICROSECONDS)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_MICROSECONDS)
                if (index >= 0) {
                    if (info.size > 0) {
                        decoder.getOutputImage(index)?.use { image ->
                            val rgba = rgbaOf(image, segment.width, segment.height)
                            onFrame(if (segment.width == width && segment.height == height) rgba else letterbox(rgba, segment.width, segment.height, width, height))
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
    }

    private fun rgbaOf(
        image: Image,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height * 4)
        val (luma, blueDifference, redDifference) = image.planes
        val lumaBuffer = luma.buffer
        val blueBuffer = blueDifference.buffer
        val redBuffer = redDifference.buffer
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = 1.1644 * ((lumaBuffer.get(y * luma.rowStride + x * luma.pixelStride).toInt() and 0xFF) - 16)
                val cb = (blueBuffer.get(y / 2 * blueDifference.rowStride + x / 2 * blueDifference.pixelStride).toInt() and 0xFF) - 128.0
                val cr = (redBuffer.get(y / 2 * redDifference.rowStride + x / 2 * redDifference.pixelStride).toInt() and 0xFF) - 128.0
                val offset = (y * width + x) * 4
                out[offset] = (value + 1.7927 * cr).roundToInt().coerceIn(0, 255).toByte()
                out[offset + 1] = (value - 0.2132 * cb - 0.5329 * cr).roundToInt().coerceIn(0, 255).toByte()
                out[offset + 2] = (value + 2.1124 * cb).roundToInt().coerceIn(0, 255).toByte()
                out[offset + 3] = 255.toByte()
            }
        }
        return out
    }

    private fun letterbox(
        rgba: ByteArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        val target = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val scale = min(targetWidth.toFloat() / width, targetHeight.toFloat() / height)
        canvas.drawBitmap(source, Matrix().apply { setScale(scale, scale); postTranslate((targetWidth - width * scale) / 2, (targetHeight - height * scale) / 2) }, Paint(Paint.FILTER_BITMAP_FLAG))
        val out = ByteBuffer.allocate(targetWidth * targetHeight * 4)
        target.copyPixelsToBuffer(out)
        source.recycle()
        target.recycle()
        return out.array()
    }

    private fun card(
        width: Int,
        height: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(CARD_BACKGROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        cardLines.forEachIndexed { index, line ->
            paint.textSize = height * if (index == 0) 0.08f else 0.045f
            paint.typeface = if (index == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(line, width / 2f, height * (0.45f + index * 0.1f), paint)
        }
        val out = ByteBuffer.allocate(width * height * 4)
        bitmap.copyPixelsToBuffer(out)
        bitmap.recycle()
        return out.array()
    }

    companion object {
        const val FRAMES_PER_SECOND = 30
        private const val FRAME_MICROSECONDS = 1_000_000L / FRAMES_PER_SECOND
        private const val SPED_UP_FRAMES = 27 * FRAMES_PER_SECOND
        private const val FINAL_FRAMES = 3 * FRAMES_PER_SECOND
        private const val CARD_FRAMES = 2 * FRAMES_PER_SECOND
        private const val CARD_BACKGROUND = 0xFF202020.toInt()
        private const val TIMEOUT_MICROSECONDS = 10_000L
        private const val BUFFER_BYTES = 8 * 1024 * 1024
    }
}

internal fun keptWhenSpedUp(
    index: Int,
    total: Int,
    kept: Int,
): Boolean {
    val slot = index.toLong() * kept / total
    return (slot * total + kept - 1) / kept == index.toLong()
}
