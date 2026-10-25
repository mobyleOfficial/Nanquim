package com.mobyle.nanquim.document.layer

enum class PixelFormat(
    val id: String,
    val bytesPerPixel: Int,
    val bitsPerChannel: Int,
) {
    Rgba8("rgba8_premul_srgb", 4, 8),
    Rgba16f("rgba16f_premul_linear", 8, 16),
    ;

    companion object {
        fun fromId(id: String?): PixelFormat? = entries.firstOrNull { it.id == id }

        fun fromBits(bits: Int): PixelFormat? = entries.firstOrNull { it.bitsPerChannel == bits }
    }
}
