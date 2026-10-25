package com.mobyle.nanquim.engine

data class SurfaceFolder(
    val surfaceId: Int,
    val folder: String,
    val isMask: Boolean,
    val isSelection: Boolean = false,
) {
    internal val formatValue: Int
        get() =
            when {
                isSelection -> 2
                isMask -> 1
                else -> 0
            }
}

data class DocumentSaveResult(
    val ok: Boolean,
    val error: String?,
    val tilesWritten: Long,
    val tilesRemoved: Long,
    val milliseconds: Double,
)

object DocumentFolder {
    init {
        System.loadLibrary("nanquim_engine")
    }

    fun recover(folder: String): String? = nativeRecover(folder)

    fun convertLayer(
        source: String,
        destination: String,
        fromDeep: Boolean,
        toDeep: Boolean,
        sourceProfile: ByteArray? = null,
        destinationProfile: ByteArray? = null,
    ): String? = nativeConvertLayer(source, destination, fromDeep, toDeep, sourceProfile, destinationProfile)

    @JvmStatic
    private external fun nativeRecover(folder: String): String?

    @JvmStatic
    private external fun nativeConvertLayer(
        source: String,
        destination: String,
        fromDeep: Boolean,
        toDeep: Boolean,
        sourceProfile: ByteArray?,
        destinationProfile: ByteArray?,
    ): String?
}
