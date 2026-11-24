package com.mobyle.nanquim.domain.platform

import java.nio.ByteBuffer

/** An RGBA image the platform can draw, which the domain fills from pixels the engine rendered. */
interface RgbaImage {
    val width: Int
    val height: Int

    /** Copies [pixels] (RGBA, 8 bits a channel, rows packed) into the image, from its current position. */
    fun write(pixels: ByteBuffer)
}

/** Creates the platform's [RgbaImage]s. */
fun interface RgbaImages {
    fun create(
        width: Int,
        height: Int,
    ): RgbaImage
}
