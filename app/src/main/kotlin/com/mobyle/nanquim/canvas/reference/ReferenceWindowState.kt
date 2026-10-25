package com.mobyle.nanquim.canvas.reference

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.canvas.SrgbColor
import com.mobyle.nanquim.document.OpenArtwork
import com.mobyle.nanquim.document.RgbaImages
import com.mobyle.nanquim.document.reference.ReferenceMode
import com.mobyle.nanquim.document.reference.ReferenceSettings
import com.mobyle.nanquim.document.reference.ReferenceView
import com.mobyle.nanquim.engine.Engine
import java.io.File
import java.io.IOException

class ReferenceWindowState(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    var settings: ReferenceSettings by mutableStateOf(ReferenceSettings())
        private set

    var image: Bitmap? by mutableStateOf(null)
        private set

    var canvasImage: Bitmap? by mutableStateOf(null)
        private set

    var canvasVersion: Int by mutableIntStateOf(0)
        private set

    private var artwork: OpenArtwork? = null
    private var documentSerial = 0
    private var canvasRequest: Int? = null
    private var canvasAgain = false

    fun open(artwork: OpenArtwork?) {
        this.artwork = artwork
        val serial = ++documentSerial
        settings = artwork?.info?.reference ?: ReferenceSettings()
        image = null
        canvasImage = null
        canvasRequest = null
        val file = artwork?.let { imageFile(it.folder) }
        if (file != null && settings.hasImage) {
            scope.launch {
                val bitmap = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
                if (serial == documentSerial) image = bitmap
            }
        }
    }

    fun close() = open(null)

    fun change(changed: ReferenceSettings) {
        if (changed == settings) return
        settings = changed
        val open = artwork ?: return
        open.info = open.info.copy(reference = changed)
        onChanged()
    }

    fun changeView(view: ReferenceView) = change(settings.withView(view))

    suspend fun importImage(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result<Bitmap> {
        val serial = documentSerial
        val folder = artwork?.folder
        val result =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = decode(contentResolver, uri)
                    if (folder != null) writeImage(folder, bitmap)
                    bitmap
                }
            }
        if (serial != documentSerial) return Result.failure(IOException("The document was closed"))
        result.onSuccess { bitmap ->
            image = bitmap
            change(settings.copy(visible = true, mode = ReferenceMode.Image, imageView = ReferenceView(), hasImage = folder != null))
        }
        return result
    }

    fun imageColor(
        x: Double,
        y: Double,
    ): SrgbColor? {
        val bitmap = image ?: return null
        val column = x.toInt()
        val row = y.toInt()
        if (x < 0 || y < 0 || column >= bitmap.width || row >= bitmap.height) return null
        val color = bitmap[column, row]
        if (color ushr 24 == 0) return null
        return SrgbColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f)
    }

    fun refreshCanvas(maxSide: Int) {
        if (canvasRequest != null) {
            canvasAgain = true
            return
        }
        val serial = documentSerial
        canvasRequest = serial
        engine.requestCompositePreview(maxSide) { render ->
            if (serial != documentSerial) return@requestCompositePreview
            if (render.error != null || render.width == 0) {
                canvasRequest = null
                return@requestCompositePreview
            }
            scope.launch {
                val bitmap =
                    withContext(Dispatchers.Default) { RgbaImages.premultipliedToBitmap(render.width, render.height, render.pixels) }
                if (serial != documentSerial) return@launch
                canvasImage = bitmap
                canvasVersion++
                canvasRequest = null
                if (canvasAgain) {
                    canvasAgain = false
                    refreshCanvas(maxSide)
                }
            }
        }
    }

    companion object {
        const val MAXIMUM_IMAGE_SIDE = 2048

        const val IMAGE_PATH = "reference/image.png"

        fun imageFile(folder: File) = File(folder, IMAGE_PATH)

        private fun decode(
            contentResolver: ContentResolver,
            uri: Uri,
        ): Bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val longer = maxOf(info.size.width, info.size.height)
                if (longer > MAXIMUM_IMAGE_SIDE) {
                    val scale = MAXIMUM_IMAGE_SIDE.toDouble() / longer
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }

        private fun writeImage(
            folder: File,
            bitmap: Bitmap,
        ) {
            val file = imageFile(folder)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.outputStream().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Cannot encode the image")
                stream.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Cannot save the image")
        }
    }
}
