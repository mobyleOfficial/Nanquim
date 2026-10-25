package com.mobyle.nanquim.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PadRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
}

class BrushPad(
    val width: Int,
    val height: Int,
) : AutoCloseable {
    private var handle: Long = nativeCreate(width, height)
    private val rect = IntArray(4)

    val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

    fun setBrush(
        brushJson: String,
        shapePng: ByteArray?,
        grainPng: ByteArray?,
    ): String? = nativeSetBrush(requireHandle(), brushJson, shapePng, grainPng)

    fun setPressureCurve(points: List<Pair<Float, Float>>?) =
        nativeSetPressureCurve(requireHandle(), points?.flatMap { listOf(it.first, it.second) }?.toFloatArray())

    fun beginStroke(
        sizePx: Float,
        opacity: Float,
        red: Float,
        green: Float,
        blue: Float,
        erase: Boolean = false,
        secondaryRed: Float = 1f,
        secondaryGreen: Float = 1f,
        secondaryBlue: Float = 1f,
    ): Boolean = nativeBeginStroke(requireHandle(), sizePx, opacity, red, green, blue, secondaryRed, secondaryGreen, secondaryBlue, erase)

    fun addSamples(samples: StrokeSampleBuffer): PadRect {
        if (samples.count == 0) return EMPTY
        nativeAddSamples(requireHandle(), samples.byteBuffer, samples.byteCount, pixels, rect)
        return result()
    }

    fun endStroke(): PadRect {
        nativeEndStroke(requireHandle(), pixels, rect)
        return result()
    }

    fun clear(): PadRect {
        nativeClear(requireHandle(), pixels, rect)
        return result()
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private fun result(): PadRect = PadRect(rect[0], rect[1], rect[2], rect[3]).also { rect.fill(0) }

    private fun requireHandle(): Long = handle.also { check(it != 0L) { "BrushPad is closed" } }

    companion object {
        private val EMPTY = PadRect(0, 0, 0, 0)

        init {
            System.loadLibrary("nanquim_engine")
        }

        fun renderThumbnail(
            brushJson: String,
            shapePng: ByteArray?,
            grainPng: ByteArray?,
            previewSize: Float,
            stampPreview: Boolean,
            width: Int,
            height: Int,
        ): ByteArray? = nativeRenderThumbnail(brushJson, shapePng, grainPng, previewSize, stampPreview, width, height)

        fun renderThumbnailFrames(
            brushJson: String,
            shapePng: ByteArray?,
            grainPng: ByteArray?,
            previewSize: Float,
            stampPreview: Boolean,
            width: Int,
            height: Int,
            frameCount: Int,
        ): ByteArray? = nativeRenderThumbnailFrames(brushJson, shapePng, grainPng, previewSize, stampPreview, width, height, frameCount)

        fun tipFootprint(
            brushJson: String,
            sizePixels: Float,
            pressure: Float,
            viewRotation: Float,
        ): FloatArray? = nativeTipFootprint(brushJson, sizePixels, pressure, viewRotation)

        data class AutoRepeatSettings(
            val outputSize: Int = 0,
            val patchScale: Float = 0.25f,
            val seamOverlap: Float = 0.25f,
            val mirroring: Boolean = false,
            val ignoreLowBands: Boolean = false,
            val maskHardness: Float = 0.5f,
            val pyramidBlending: Boolean = false,
            val seed: Long = 1,
        )

        fun interface ProgressListener {
            fun onProgress(fraction: Float): Boolean
        }

        class GrayImage(
            val side: Int,
            val pixels: ByteArray,
        )

        fun autoRepeat(
            png: ByteArray,
            settings: AutoRepeatSettings,
            listener: ProgressListener? = null,
        ): GrayImage? {
            val result =
                with(settings) {
                    nativeAutoRepeat(
                        png,
                        outputSize,
                        patchScale,
                        seamOverlap,
                        mirroring,
                        ignoreLowBands,
                        maskHardness,
                        pyramidBlending,
                        seed,
                        listener,
                    )
                } ?: return null
            val side = (0 until 4).sumOf { (result[it].toInt() and 0xFF) shl (8 * it) }
            return GrayImage(side, result.copyOfRange(4, result.size))
        }

        @JvmStatic private external fun nativeAutoRepeat(
            png: ByteArray,
            outputSize: Int,
            patchScale: Float,
            seamOverlap: Float,
            mirroring: Boolean,
            ignoreLowBands: Boolean,
            maskHardness: Float,
            pyramidBlending: Boolean,
            seed: Long,
            listener: ProgressListener?,
        ): ByteArray?

        @JvmStatic private external fun nativeTipFootprint(
            brushJson: String,
            sizePixels: Float,
            pressure: Float,
            viewRotation: Float,
        ): FloatArray?

        @JvmStatic private external fun nativeCreate(
            width: Int,
            height: Int,
        ): Long

        @JvmStatic private external fun nativeDestroy(handle: Long)

        @JvmStatic private external fun nativeSetBrush(
            handle: Long,
            brushJson: String,
            shapePng: ByteArray?,
            grainPng: ByteArray?,
        ): String?

        @JvmStatic private external fun nativeSetPressureCurve(
            handle: Long,
            points: FloatArray?,
        )

        @JvmStatic private external fun nativeBeginStroke(
            handle: Long,
            sizePx: Float,
            opacity: Float,
            red: Float,
            green: Float,
            blue: Float,
            secondaryRed: Float,
            secondaryGreen: Float,
            secondaryBlue: Float,
            erase: Boolean,
        ): Boolean

        @JvmStatic private external fun nativeAddSamples(
            handle: Long,
            samples: ByteBuffer,
            byteCount: Int,
            output: ByteBuffer,
            rect: IntArray,
        )

        @JvmStatic private external fun nativeEndStroke(
            handle: Long,
            output: ByteBuffer,
            rect: IntArray,
        )

        @JvmStatic private external fun nativeClear(
            handle: Long,
            output: ByteBuffer,
            rect: IntArray,
        )

        @JvmStatic private external fun nativeRenderThumbnail(
            brushJson: String,
            shapePng: ByteArray?,
            grainPng: ByteArray?,
            previewSize: Float,
            stampPreview: Boolean,
            width: Int,
            height: Int,
        ): ByteArray?

        @JvmStatic private external fun nativeRenderThumbnailFrames(
            brushJson: String,
            shapePng: ByteArray?,
            grainPng: ByteArray?,
            previewSize: Float,
            stampPreview: Boolean,
            width: Int,
            height: Int,
            frameCount: Int,
        ): ByteArray?
    }
}
