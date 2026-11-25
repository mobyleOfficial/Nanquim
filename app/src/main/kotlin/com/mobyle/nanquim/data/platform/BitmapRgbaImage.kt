package com.mobyle.nanquim.data.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mobyle.nanquim.domain.platform.RgbaImage
import com.mobyle.nanquim.domain.platform.RgbaImages
import java.nio.ByteBuffer

/** [RgbaImage] backed by an ARGB_8888 [Bitmap], whose memory layout is RGBA. */
class BitmapRgbaImage(
    override val width: Int,
    override val height: Int,
) : RgbaImage {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    override fun write(pixels: ByteBuffer) = bitmap.copyPixelsFromBuffer(pixels)

    companion object : RgbaImages {
        override fun create(
            width: Int,
            height: Int,
        ): RgbaImage = BitmapRgbaImage(width, height)
    }
}

/** The image to draw in Compose; this platform's images are always [BitmapRgbaImage]s. */
fun RgbaImage.imageBitmap(): ImageBitmap = (this as BitmapRgbaImage).bitmap.asImageBitmap()
