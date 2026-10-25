package com.mobyle.nanquim.gallery

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlin.math.roundToInt

class VideoFileEncoder private constructor(
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    private val width: Int,
    private val height: Int,
    private val frameMicroseconds: Long,
) {
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var lastFrame: ByteArray? = null
    private var lastStart = 0L
    private var failed = false

    fun addFrame(
        rgba: ByteArray,
        startMicroseconds: Long,
    ) {
        if (failed) return
        queue(rgba, startMicroseconds, endOfStream = false)
        lastFrame = rgba
        lastStart = startMicroseconds
        drain(endOfStream = false)
    }

    fun finish(endMicroseconds: Long): Boolean {
        if (failed) return false
        val last = lastFrame
        if (last != null && endMicroseconds - lastStart > frameMicroseconds * 3 / 2) {
            queue(last, endMicroseconds - frameMicroseconds, endOfStream = false)
            drain(endOfStream = false)
        }
        val index = codec.dequeueInputBuffer(TIMEOUT_MICROSECONDS)
        if (index < 0) return false
        codec.queueInputBuffer(index, 0, 0, endMicroseconds, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
        if (muxing) muxer.stop()
        muxing = false
        return !failed
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
        if (muxing) runCatching { muxer.stop() }
        muxer.release()
    }

    private fun queue(
        rgba: ByteArray,
        startMicroseconds: Long,
        endOfStream: Boolean,
    ) {
        var index = codec.dequeueInputBuffer(TIMEOUT_MICROSECONDS)
        while (index < 0) {
            drain(endOfStream = false)
            if (failed) return
            index = codec.dequeueInputBuffer(TIMEOUT_MICROSECONDS)
        }
        val image = codec.getInputImage(index)
        if (image == null) {
            failed = true
            return
        }
        val planes = image.planes
        val luma = planes[0]
        val blueDifference = planes[1]
        val redDifference = planes[2]
        val lumaBuffer = luma.buffer
        for (y in 0 until height) {
            val rowStart = y * luma.rowStride
            for (x in 0 until width) {
                val offset = (y * width + x) * 4
                val red = rgba[offset].toInt() and 0xFF
                val green = rgba[offset + 1].toInt() and 0xFF
                val blue = rgba[offset + 2].toInt() and 0xFF
                val value = 16 + (0.1826 * red + 0.6142 * green + 0.0620 * blue)
                lumaBuffer.put(rowStart + x * luma.pixelStride, value.roundToInt().coerceIn(16, 235).toByte())
            }
        }
        val blueBuffer = blueDifference.buffer
        val redBuffer = redDifference.buffer
        for (y in 0 until height / 2) {
            for (x in 0 until width / 2) {
                var red = 0
                var green = 0
                var blue = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val offset = ((y * 2 + dy) * width + x * 2 + dx) * 4
                        red += rgba[offset].toInt() and 0xFF
                        green += rgba[offset + 1].toInt() and 0xFF
                        blue += rgba[offset + 2].toInt() and 0xFF
                    }
                }
                val r = red / 4.0
                val g = green / 4.0
                val b = blue / 4.0
                val cb = 128 + (-0.1006 * r - 0.3386 * g + 0.4392 * b)
                val cr = 128 + (0.4392 * r - 0.3989 * g - 0.0403 * b)
                blueBuffer.put(y * blueDifference.rowStride + x * blueDifference.pixelStride, cb.roundToInt().coerceIn(16, 240).toByte())
                redBuffer.put(y * redDifference.rowStride + x * redDifference.pixelStride, cr.roundToInt().coerceIn(16, 240).toByte())
            }
        }
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, startMicroseconds, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    private fun drain(endOfStream: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_MICROSECONDS else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (!muxing) {
                            failed = true
                        } else {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    companion object {
        const val AVC = MediaFormat.MIMETYPE_VIDEO_AVC
        const val HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC
        const val VP9 = MediaFormat.MIMETYPE_VIDEO_VP9

        private const val TIMEOUT_MICROSECONDS = 10_000L
        private const val KEY_FRAME_SECONDS = 1
        private const val SEARCH_STEPS = 24
        private const val MINIMUM_SCALE = 0.05

        fun supports(
            mime: String,
            width: Int,
            height: Int,
            framesPerSecond: Int,
        ): Boolean = encoderFor(mime, width, height) != null

        fun largestSize(
            mime: String,
            width: Int,
            height: Int,
        ): Pair<Int, Int>? {
            val encoders = encodersOf(mime)
            fun sizeAt(factor: Double) = (width * factor).toInt().let { it - it % 2 } to (height * factor).toInt().let { it - it % 2 }
            fun fits(factor: Double) = sizeAt(factor).let { (w, h) -> w >= 2 && h >= 2 && encoders.any { takes(it, mime, w, h) } }
            if (fits(1.0)) return sizeAt(1.0)
            var low = MINIMUM_SCALE
            var high = 1.0
            if (!fits(low)) return null
            repeat(SEARCH_STEPS) {
                val middle = (low + high) / 2
                if (fits(middle)) low = middle else high = middle
            }
            return sizeAt(low)
        }

        fun create(
            path: String,
            mime: String,
            width: Int,
            height: Int,
            framesPerSecond: Int,
            webm: Boolean,
        ): VideoFileEncoder? {
            val info = encoderFor(mime, width, height) ?: return null
            val format = format(mime, width, height, framesPerSecond)
            info.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.let { range ->
                format.setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE).coerceIn(range.lower, range.upper))
            }
            val codec = runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull() ?: return null
            return try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val muxer = MediaMuxer(path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                VideoFileEncoder(codec, muxer, width, height, 1_000_000L / framesPerSecond)
            } catch (exception: Exception) {
                codec.release()
                null
            }
        }

        private fun format(
            mime: String,
            width: Int,
            height: Int,
            framesPerSecond: Int,
        ): MediaFormat =
            MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, maxOf(2_000_000, (width.toLong() * height * framesPerSecond / 5).coerceAtMost(60_000_000).toInt()))
                setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }

        private fun encoderFor(
            mime: String,
            width: Int,
            height: Int,
        ): MediaCodecInfo? = encodersOf(mime).firstOrNull { takes(it, mime, width, height) }

        private fun encodersOf(mime: String): List<MediaCodecInfo> =
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .sortedBy { if (it.isHardwareAccelerated) 0 else 1 }

        private fun takes(
            info: MediaCodecInfo,
            mime: String,
            width: Int,
            height: Int,
        ): Boolean = runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(width, height) }.getOrDefault(false)
    }
}
