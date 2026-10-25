package com.mobyle.nanquim.engine

import java.io.Closeable

class AnimationFileWriter private constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
) : Closeable {
    enum class Format { Gif, Apng }

    enum class Dither { None, Ordered, FloydSteinberg }

    fun addFrame(
        rgba: ByteArray,
        durationMilliseconds: Int,
    ): Boolean {
        check(handle != 0L) { "Writer finished" }
        require(rgba.size == width * height * 4) { "Frame is not $width × $height RGBA8" }
        return nativeAddFrame(handle, rgba, durationMilliseconds)
    }

    fun finish(): String? {
        check(handle != 0L) { "Writer finished" }
        val error = nativeFinish(handle)
        handle = 0L
        return error
    }

    override fun close() {
        if (handle != 0L) finish()
    }

    companion object {
        init {
            System.loadLibrary("nanquim_engine")
        }

        fun open(
            path: String,
            format: Format,
            width: Int,
            height: Int,
            plays: Int,
            dither: Dither = Dither.FloydSteinberg,
        ): AnimationFileWriter? {
            val handle = nativeOpen(path, format == Format.Gif, width, height, plays, dither.ordinal)
            return if (handle == 0L) null else AnimationFileWriter(handle, width, height)
        }

        @JvmStatic
        private external fun nativeOpen(
            path: String,
            gif: Boolean,
            width: Int,
            height: Int,
            plays: Int,
            dither: Int,
        ): Long

        @JvmStatic
        private external fun nativeAddFrame(
            handle: Long,
            rgba: ByteArray,
            durationMilliseconds: Int,
        ): Boolean

        @JvmStatic
        private external fun nativeFinish(handle: Long): String?
    }
}
