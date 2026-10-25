package com.mobyle.nanquim.engine

class CmykColors private constructor(
    private var handle: Long,
) : AutoCloseable {
    @Synchronized
    fun toCmyk(
        red: Float,
        green: Float,
        blue: Float,
    ): FloatArray {
        check(handle != 0L) { "Closed" }
        return FloatArray(4).also { nativeToCmyk(handle, red, green, blue, it) }
    }

    @Synchronized
    fun toRgb(
        cyan: Float,
        magenta: Float,
        yellow: Float,
        black: Float,
    ): FloatArray {
        check(handle != 0L) { "Closed" }
        return FloatArray(3).also { nativeToRgb(handle, cyan, magenta, yellow, black, it) }
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    companion object {
        init {
            System.loadLibrary("nanquim_engine")
        }

        fun create(
            rgbProfile: ByteArray?,
            cmykProfile: ByteArray,
        ): CmykColors? = nativeCreate(rgbProfile, cmykProfile).takeIf { it != 0L }?.let(::CmykColors)

        @JvmStatic
        private external fun nativeCreate(
            rgbProfile: ByteArray?,
            cmykProfile: ByteArray,
        ): Long

        @JvmStatic
        private external fun nativeRelease(handle: Long)

        @JvmStatic
        private external fun nativeToCmyk(
            handle: Long,
            red: Float,
            green: Float,
            blue: Float,
            inks: FloatArray,
        )

        @JvmStatic
        private external fun nativeToRgb(
            handle: Long,
            cyan: Float,
            magenta: Float,
            yellow: Float,
            black: Float,
            rgb: FloatArray,
        )
    }
}
