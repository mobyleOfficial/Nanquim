package com.mobyle.nanquim.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.math.roundToInt

object AnimationDecoder {
    data class Decoded(
        val format: String,
        val width: Int,
        val height: Int,
        val frames: Int,
        val durationMilliseconds: Long,
        val colors: List<List<IntArray>>,
        val mime: String? = null,
    )

    fun decode(
        file: File,
        frames: List<Int>,
        points: List<Pair<Int, Int>>,
    ): Decoded {
        val head = file.inputStream().use { stream -> ByteArray(16).also { stream.read(it) } }
        return when {
            head.startsWith("GIF8") -> image(file, "gif", gifTiming(file.readBytes()), points)
            head.startsWith("\u0089PNG") -> image(file, "apng", apngTiming(file.readBytes()), points)
            head.startsWith("PK") -> zip(file, frames, points)
            else -> video(file, frames, points)
        }
    }

    private fun ByteArray.startsWith(text: String): Boolean = text.indices.all { index -> index < size && (this[index].toInt() and 0xFF) == text[index].code }

    private fun sample(
        bitmap: Bitmap,
        points: List<Pair<Int, Int>>,
    ): List<IntArray> {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
        return points.map { (x, y) ->
            val color = software.getPixel(x.coerceIn(0, software.width - 1), y.coerceIn(0, software.height - 1))
            intArrayOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, (color ushr 24) and 0xFF)
        }
    }

    private fun image(
        file: File,
        format: String,
        timing: Pair<Int, Long>,
        points: List<Pair<Int, Int>>,
    ): Decoded {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isUnpremultipliedRequired = true
            }
        return Decoded(format, bitmap.width, bitmap.height, timing.first, timing.second, listOf(sample(bitmap, points)))
    }

    private fun gifTiming(bytes: ByteArray): Pair<Int, Long> {
        fun u8(index: Int) = bytes[index].toInt() and 0xFF
        fun u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
        var offset = 13
        if (u8(10) and 0x80 != 0) offset += 3 * (1 shl ((u8(10) and 7) + 1))
        var frames = 0
        var delay = 0L
        fun skipSubBlocks(start: Int): Int {
            var position = start
            while (position < bytes.size && u8(position) != 0) position += u8(position) + 1
            return position + 1
        }
        while (offset < bytes.size) {
            when (u8(offset)) {
                0x21 -> {
                    if (u8(offset + 1) == 0xF9) delay += u16(offset + 4) * 10L
                    offset = skipSubBlocks(offset + 2)
                }
                0x2C -> {
                    frames++
                    val packed = u8(offset + 9)
                    offset += 10
                    if (packed and 0x80 != 0) offset += 3 * (1 shl ((packed and 7) + 1))
                    offset = skipSubBlocks(offset + 1)
                }
                else -> break
            }
        }
        return frames to delay
    }

    private fun apngTiming(bytes: ByteArray): Pair<Int, Long> {
        val buffer = ByteBuffer.wrap(bytes)
        var offset = 8
        var frames = 0
        var delay = 0L
        while (offset + 8 <= bytes.size) {
            val length = buffer.getInt(offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "acTL") frames = buffer.getInt(offset + 8)
            if (type == "fcTL") {
                val numerator = buffer.getShort(offset + 8 + 20).toInt() and 0xFFFF
                val denominator = (buffer.getShort(offset + 8 + 22).toInt() and 0xFFFF).let { if (it == 0) 100 else it }
                delay += numerator * 1000L / denominator
            }
            if (type == "IEND") break
            offset += 12 + length
        }
        return frames to delay
    }

    private fun zip(
        file: File,
        frames: List<Int>,
        points: List<Pair<Int, Int>>,
    ): Decoded =
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { it.name.endsWith(".png") }.sortedBy { it.name }
            val bitmaps = frames.map { index -> zip.getInputStream(entries[index]).use { BitmapFactory.decodeStream(it) } }
            val first = bitmaps.firstOrNull()
            Decoded("pngSequence", first?.width ?: 0, first?.height ?: 0, entries.size, 0, bitmaps.map { sample(it, points) })
        }

    private fun video(
        file: File,
        frames: List<Int>,
        points: List<Pair<Int, Int>>,
    ): Decoded {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val format = extractor.getTrackFormat(0)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalArgumentException("No video track")
        extractor.selectTrack(0)
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        val decoder = MediaCodec.createDecoderByType(mime)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val sampled = HashMap<Int, List<IntArray>>()
        var decoded = 0
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_MICROSECONDS)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
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
                        if (decoded in frames) decoder.getOutputImage(index)?.use { image -> sampled[decoded] = sampleYuv(image, points) }
                        decoded++
                    }
                    decoder.releaseOutputBuffer(index, false)
                    outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                }
            }
        } finally {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
        val colors = frames.map { sampled[it].orEmpty() }
        return Decoded(if (mime == MediaFormat.MIMETYPE_VIDEO_VP9) "webm" else "mp4", width, height, decoded, duration, colors, mime)
    }

    private fun sampleYuv(
        image: Image,
        points: List<Pair<Int, Int>>,
    ): List<IntArray> {
        val (luma, blueDifference, redDifference) = image.planes
        return points.map { (px, py) ->
            val x = px.coerceIn(0, image.width - 1)
            val y = py.coerceIn(0, image.height - 1)
            val value = (luma.buffer.get(y * luma.rowStride + x * luma.pixelStride).toInt() and 0xFF) - 16.0
            val cb = (blueDifference.buffer.get(y / 2 * blueDifference.rowStride + x / 2 * blueDifference.pixelStride).toInt() and 0xFF) - 128.0
            val cr = (redDifference.buffer.get(y / 2 * redDifference.rowStride + x / 2 * redDifference.pixelStride).toInt() and 0xFF) - 128.0
            val red = 1.1644 * value + 1.7927 * cr
            val green = 1.1644 * value - 0.2132 * cb - 0.5329 * cr
            val blue = 1.1644 * value + 2.1124 * cb
            intArrayOf(red.roundToInt().coerceIn(0, 255), green.roundToInt().coerceIn(0, 255), blue.roundToInt().coerceIn(0, 255), 255)
        }
    }

    private const val TIMEOUT_MICROSECONDS = 10_000L
}
