package com.mobyle.nanquim.gallery

import android.content.ContentResolver
import android.graphics.ColorSpace
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.engine.CmykImportProfiles
import com.mobyle.nanquim.brush.BrushFiles
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.MAXIMUM_CANVAS_SIDE_PX
import com.mobyle.nanquim.command.MINIMUM_LAYERS_FOR_NEW_CANVAS
import com.mobyle.nanquim.document.ArtworkFailure
import com.mobyle.nanquim.document.ArtworkReadResult
import com.mobyle.nanquim.document.ArtworkStorage
import com.mobyle.nanquim.document.DocumentArchive
import com.mobyle.nanquim.document.ImageColor
import com.mobyle.nanquim.document.ImageFormat
import com.mobyle.nanquim.document.ImagePlacement
import com.mobyle.nanquim.document.ImageTransfer
import com.mobyle.nanquim.document.JpegIcc
import com.mobyle.nanquim.document.LoadedArtwork
import com.mobyle.nanquim.document.ZipProbe
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.document.brush.BrushPackageIo
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.withoutPrivateLayers
import com.mobyle.nanquim.document.layer.TextLayerContent
import com.mobyle.nanquim.document.layer.maximumLayers
import com.mobyle.nanquim.document.layer.textModels
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import com.mobyle.nanquim.document.session.LayerNames
import com.mobyle.nanquim.engine.ColorProfiles
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.InterchangeFiles
import com.mobyle.nanquim.engine.ProfileColorSpace
import com.mobyle.nanquim.engine.SceneRender
import com.mobyle.nanquim.color.PaletteFileFormat
import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.formats.interchange.ImportJson
import com.mobyle.nanquim.formats.interchange.ImportJsonException
import com.mobyle.nanquim.formats.interchange.ImportWarning
import com.mobyle.nanquim.formats.interchange.ProcreateBrush
import com.mobyle.nanquim.formats.interchange.ProcreateBrushSet
import com.mobyle.nanquim.formats.nanquim.CanvasUnit
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import kotlin.math.min
import kotlin.math.sqrt

data class ExportOptions(
    val imageFormat: ImageFormat?,
    val quality: Int = DEFAULT_QUALITY,
    val lossless: Boolean = true,
    val interchangeFormat: InterchangeFormat? = null,
    val layersAsPages: Boolean = false,
    val cmykProfileId: String? = null,
    val animationFormat: AnimationFormat? = null,
    val animation: AnimationExportSettings = AnimationExportSettings(),
) {
    val formatId: String get() = animationFormat?.id ?: interchangeFormat?.id ?: imageFormat?.id ?: NANQUIM_FORMAT_ID

    val isPackage: Boolean get() = animationFormat == null && interchangeFormat == null && imageFormat == null

    fun commandArguments(): Map<String, Any?> =
        mapOf(
            "format" to formatId,
            "quality" to quality,
            "lossless" to lossless,
            "layersAsPages" to layersAsPages,
            "cmykProfile" to cmykProfileId,
            "firstFrame" to animation.firstFrame,
            "lastFrame" to animation.lastFrame.takeIf { it != Int.MAX_VALUE },
            "framesPerSecond" to animation.framesPerSecond,
            "scale" to animation.scalePercent,
            "plays" to animation.plays,
            "includeBackground" to animation.includeBackground,
            "dither" to animation.dither.name.replaceFirstChar { it.lowercase() },
            "hevc" to animation.hevc,
        )

    val extension: String
        get() = animationFormat?.extension ?: interchangeFormat?.extension ?: imageFormat?.extension ?: NanquimFileType.Document.extension
    val mimeType: String
        get() = animationFormat?.mimeType ?: interchangeFormat?.mimeType ?: imageFormat?.mimeType ?: NanquimFileType.Document.mimeType

    companion object {
        const val RGB_COLOR = "rgb"
        const val DEFAULT_QUALITY = 90
        const val NANQUIM_FORMAT_ID = "nanquim"
    }
}

enum class InterchangeFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
) {
    Psd("psd", "psd", "image/vnd.adobe.photoshop"),
    Tiff("tiff", "tif", "image/tiff"),

    Pdf("pdf", "pdf", "application/pdf"),

    Procreate("procreate", "procreate", "application/octet-stream"),
    ;

    companion object {
        fun fromId(id: String): InterchangeFormat? = entries.firstOrNull { it.id == id }
    }
}

data class InterchangeImport(
    val artworkId: String,
    val warnings: List<ImportWarning>,
)

data class TransferReport(
    val export: Boolean,
    val warnings: List<ImportWarning>,
)

enum class ImportTarget(
    val id: String,
) {
    NewArtwork("artwork"),
    Layer("layer"),
    ;

    companion object {
        fun fromId(id: String): ImportTarget? = entries.firstOrNull { it.id == id }
    }
}

class ArtworkTransfer(
    private val contentResolver: ContentResolver,
    private val engine: Engine,
    private val storage: ArtworkStorage,
    private val gallery: Gallery,
    private val canvasSession: CanvasSession,
    private val cacheDirectory: File,
    private val layerNames: LayerNames,
    private val untitledName: () -> String,
    private val backgroundLayerName: () -> String,
    private val appVersion: String,
    private val memoryBudgetBytes: () -> Long,
) {
    private val images = ImageTransfer(contentResolver)

    val animationExport = AnimationExport(engine, canvasSession, cacheDirectory)

    var report: TransferReport? by mutableStateOf(null)

    fun displayName(uri: Uri): String? =
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.substringBeforeLast('.')

    private fun fileNameOf(uri: Uri): String? =
        contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun isDocumentPackage(uri: Uri): Boolean {
        val type = contentResolver.getType(uri)
        if (type == NanquimFileType.Document.mimeType) return true
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return name != null && NanquimFileType.fromFileName(name) == NanquimFileType.Document
    }

    fun interchangeFormatOf(uri: Uri): InterchangeFormat? {
        if (fileNameOf(uri)?.endsWith(".${InterchangeFormat.Procreate.extension}", ignoreCase = true) == true) {
            return InterchangeFormat.Procreate
        }
        val header =
            try {
                contentResolver.openInputStream(uri)?.use { it.readUpTo(PSD_SIGNATURE.size) }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: return null
        return when {
            header.contentEquals(PSD_SIGNATURE) -> InterchangeFormat.Psd
            TIFF_SIGNATURES.any { header.contentEquals(it) } -> InterchangeFormat.Tiff
            header.contentEquals(ZIP_SIGNATURE) && ZipProbe.entryNames(contentResolver, uri)?.contains(PROCREATE_ARCHIVE) == true ->
                InterchangeFormat.Procreate
            else -> null
        }
    }

    suspend fun importInterchange(
        uri: Uri,
        format: InterchangeFormat,
    ): InterchangeImport {
        val id = storage.newId()
        val staging = storage.stagingFolder(id)
        val source = File(cacheDirectory, "import-$id")
        val name = displayName(uri) ?: untitledName()
        val profiles = canvasSession.colorProfiles
        val cmyk =
            CmykImportProfiles(
                fallback = profiles.bytes(ColorProfileLibrary.NANQUIM_CMYK_ID),
                working = profiles.bytes(ColorProfileLibrary.PRINT_WORKING_PROFILE_ID),
            )
        val warnings =
            withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                    input.use { stream -> source.outputStream().use { stream.copyTo(it) } }
                    if (!staging.mkdirs()) throw IOException("Cannot create ${staging.path}")
                    val result =
                        when (format) {
                            InterchangeFormat.Psd ->
                                InterchangeFiles.importPsd(source.path, staging.path, MAXIMUM_CANVAS_SIDE_PX, MAXIMUM_IMPORTED_LAYERS, cmyk)
                            InterchangeFormat.Tiff -> InterchangeFiles.importTiff(source.path, staging.path, MAXIMUM_CANVAS_SIDE_PX, cmyk)
                            InterchangeFormat.Procreate ->
                                InterchangeFiles.importProcreate(source.path, staging.path, MAXIMUM_CANVAS_SIDE_PX, MAXIMUM_IMPORTED_LAYERS)
                            InterchangeFormat.Pdf -> throw GalleryException(GalleryError.Unsupported, "PDF is export only")
                        }
                    if (!result.ok) {
                        throw GalleryException(
                            interchangeError(result.errorCode),
                            "${result.errorCode} ${result.errorDetail}".trim(),
                        )
                    }
                    val importFile = File(staging, ImportJson.FILE_NAME)
                    val artwork = ImportJson.parse(importFile.readText()) { number -> layerNames.layer(number) }
                    val (profile, profileWarnings) = importedProfile(artwork, staging)
                    val now = Instant.now()
                    val timelapse = TimelapseSettings(enabled = gallery.recordTimelapse())
                    val cmykProfile = importedCmykProfile(artwork, staging)
                    val info =
                        DocumentInfo(
                            id,
                            name,
                            now,
                            now,
                            artwork.dpi,
                            CanvasUnit.Pixels,
                            colorProfile = profile,
                            cmykProfile = cmykProfile,
                            timelapse = timelapse,
                        )
                    ArtworkStorage.writeManifestAtomically(staging, NanquimManifest.write(artwork.state, info, artwork.uuids, appVersion))
                    importFile.delete()
                    if (!storage.publishStaging(id)) throw IOException("Cannot move the import into place")
                    artwork.warnings + profileWarnings
                } catch (error: IOException) {
                    staging.deleteRecursively()
                    throw GalleryException(GalleryError.Unreadable, error.message ?: "")
                } catch (error: ImportJsonException) {
                    staging.deleteRecursively()
                    throw GalleryException(GalleryError.Unreadable, error.message ?: "")
                } catch (error: GalleryException) {
                    staging.deleteRecursively()
                    throw error
                } finally {
                    source.delete()
                }
            }
        gallery.sync()
        if (warnings.isNotEmpty()) report = TransferReport(export = false, warnings)
        return InterchangeImport(id, warnings)
    }

    private suspend fun importedCmykProfile(
        artwork: com.mobyle.nanquim.formats.interchange.ImportedArtwork,
        folder: File,
    ): DocumentColorProfile? {
        val file = artwork.cmykProfileFile ?: return null
        val bytes = File(folder, file).takeIf { it.isFile }?.readBytes() ?: return null
        val library = canvasSession.colorProfiles
        val bundled = library.printProfiles.firstOrNull { it.builtIn && library.bytes(it.id)?.contentEquals(bytes) == true }
        return DocumentColorProfile(bundled?.name ?: artwork.cmykProfileName ?: file, bytes)
    }

    private suspend fun importedProfile(
        artwork: com.mobyle.nanquim.formats.interchange.ImportedArtwork,
        folder: File,
    ): Pair<DocumentColorProfile?, List<ImportWarning>> {
        val name = artwork.colorProfileName ?: return null to emptyList()
        artwork.colorProfileFile?.let { file ->
            val bytes = File(folder, file).takeIf { it.isFile }?.readBytes()
            if (bytes != null) {
                if (file != PROFILE_FILE) File(folder, PROFILE_FILE).writeBytes(bytes)
                val library = canvasSession.colorProfiles
                val bundled = library.documentProfiles.firstOrNull { it.builtIn && library.bytes(it.id)?.contentEquals(bytes) == true }
                return DocumentColorProfile(bundled?.name ?: name, bytes) to emptyList()
            }
        }
        val library = canvasSession.colorProfiles
        val entry = library.documentProfiles.firstOrNull { it.builtIn && sameProfileName(it.name, name) }
        val profile = entry?.let { library.documentProfile(it.id) } ?: return null to listOf(ImportWarning("colorProfileIgnored", name))
        File(folder, PROFILE_FILE).writeBytes(profile.icc)
        return profile to emptyList()
    }

    private fun sameProfileName(
        bundled: String,
        named: String,
    ): Boolean {
        val key = { text: String -> text.lowercase().replace(Regex("[^a-z0-9]"), "") }
        val bundledKey = key(bundled).removeSuffix("compatible")
        val namedKey = key(named)
        return namedKey == bundledKey || (namedKey.isNotEmpty() && bundledKey.startsWith(namedKey)) ||
            (bundledKey.isNotEmpty() && namedKey.startsWith(bundledKey))
    }

    private suspend fun imageColorFor(profile: DocumentColorProfile?): ImageColor {
        if (profile == null) return ImageColor()
        val library = canvasSession.colorProfiles
        val icc = profile.icc
        for ((id, named) in ANDROID_COLOR_SPACES) {
            if (library.bytes(id)?.contentEquals(icc) == true) return ImageColor(ColorSpace.get(named))
        }
        return ImageColor(convert = { pixels -> ColorProfiles.convertRgba8(pixels, null, icc, premultiplied = true) })
    }

    private suspend fun bundledProfileOf(colorSpace: ColorSpace?): DocumentColorProfile? {
        val named = ANDROID_COLOR_SPACES.entries.firstOrNull { colorSpace != null && ColorSpace.get(it.value) == colorSpace } ?: return null
        return canvasSession.colorProfiles.documentProfile(named.key)
    }

    suspend fun importDocument(uri: Uri): String {
        val id = storage.newId()
        val staging = storage.stagingFolder(id)
        withContext(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                stream.use { DocumentArchive.extract(it, staging) }
                val probeId = staging.name
                when (val result = ArtworkStorage(staging.parentFile!!).read(probeId)) {
                    is ArtworkReadResult.Success -> {
                        val info = result.artwork.info.copy(id = id)
                        ArtworkStorage.writeManifestAtomically(staging, ArtworkStorage.withIdentity(result.artwork.manifest, info))
                    }
                    is ArtworkReadResult.Failure -> throw GalleryException(failureOf(result), result.detail)
                }
                if (!storage.publishStaging(id)) throw IOException("Cannot move the import into place")
            } catch (error: IOException) {
                staging.deleteRecursively()
                throw GalleryException(GalleryError.Unreadable, error.message ?: "")
            } catch (error: GalleryException) {
                staging.deleteRecursively()
                throw error
            }
        }
        gallery.sync()
        return id
    }

    suspend fun importImageAsArtwork(uri: Uri): String {
        val (width, height) = withContext(Dispatchers.IO) { images.size(uri) } ?: throw GalleryException(GalleryError.Unreadable, uri.toString())
        var scale = min(1.0, MAXIMUM_CANVAS_SIDE_PX.toDouble() / maxOf(width, height))
        while (maximumLayers(memoryBudgetBytes(), (width * scale).toInt(), (height * scale).toInt()) < MINIMUM_LAYERS_FOR_NEW_CANVAS) {
            scale *= sqrt(0.5)
        }
        val canvasWidth = (width * scale).toInt().coerceAtLeast(1)
        val canvasHeight = (height * scale).toInt().coerceAtLeast(1)
        val id =
            gallery.newCanvas(
                canvasWidth,
                canvasHeight,
                displayName(uri),
                DocumentInfo.DEFAULT_DPI,
                CanvasUnit.Pixels,
                show = false,
            )
        val layerId = canvasSession.document?.layers?.firstOrNull()?.id ?: throw GalleryException(GalleryError.WriteFailed, id)
        val profile = bundledProfileOf(withContext(Dispatchers.IO) { images.colorSpace(uri) })
        if (profile != null) canvasSession.assignColorProfile(profile)
        val color = imageColorFor(profile)
        withContext(Dispatchers.IO) {
            val placement = ImagePlacement(0f, 0f, (canvasWidth.toFloat() / width))
            images.writeToSurface(uri, engine, layerId, canvasWidth, canvasHeight, placement, color)
        }
        gallery.closeHiddenCanvas()
        return id
    }

    suspend fun importImageAsLayer(uri: Uri): Int {
        if (interchangeFormatOf(uri) == InterchangeFormat.Tiff) return importTiffAsLayer(uri)
        val session = canvasSession.documentSession ?: throw GalleryException(GalleryError.NotFound, "No open artwork")
        val (width, height) = withContext(Dispatchers.IO) { images.size(uri) } ?: throw GalleryException(GalleryError.Unreadable, uri.toString())
        val canvasWidth = session.state.widthPx
        val canvasHeight = session.state.heightPx
        val scale = min(1f, min(canvasWidth.toFloat() / width, canvasHeight.toFloat() / height))
        val placement = ImagePlacement((canvasWidth - width * scale) / 2f, (canvasHeight - height * scale) / 2f, scale)
        val color = imageColorFor(canvasSession.colorProfile)
        val layerId = session.newLayer()
        displayName(uri)?.let { name -> session.rename(layerId, name) }
        withContext(Dispatchers.IO) { images.writeToSurface(uri, engine, layerId, canvasWidth, canvasHeight, placement, color) }
        canvasSession.persistence.documentChanged()
        return layerId
    }

    private suspend fun importTiffAsLayer(uri: Uri): Int {
        val session = canvasSession.documentSession ?: throw GalleryException(GalleryError.NotFound, "No open artwork")
        val id = storage.newId()
        val source = File(cacheDirectory, "import-$id")
        val folder = File(cacheDirectory, "import-folder-$id")
        val documentIcc = canvasSession.colorProfile?.icc
        val (image, warnings) =
            withContext(Dispatchers.IO) {
                try {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                    input.use { stream -> source.outputStream().use { stream.copyTo(it) } }
                    if (!folder.mkdirs()) throw IOException("Cannot create ${folder.path}")
                    val result = InterchangeFiles.importTiff(source.path, folder.path, MAXIMUM_LAYER_IMAGE_SIDE_PX)
                    if (!result.ok) {
                        throw GalleryException(interchangeError(result.errorCode), "${result.errorCode} ${result.errorDetail}".trim())
                    }
                    val imported = ImportJson.parse(File(folder, ImportJson.FILE_NAME).readText()) { layerNames.layer(it) }
                    val layer = imported.state.layers.single()
                    val uuid = imported.uuids.uuidOf(layer.id) ?: throw IOException("No layer in the converted TIFF")
                    val width = imported.state.widthPx
                    val height = imported.state.heightPx
                    val pixels =
                        InterchangeFiles.readLayer(File(folder, "layers/$uuid").path, width, height)
                            ?: throw IOException("Unreadable converted TIFF")
                    val fileIcc = imported.colorProfileFile?.let { File(folder, it).takeIf(File::isFile)?.readBytes() }
                    if (fileIcc != null || documentIcc != null) {
                        ColorProfiles.convertRgba8(pixels, fileIcc, documentIcc, premultiplied = true)
                    }
                    val bitmap = createBitmap(width, height)
                    bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(pixels))
                    bitmap to imported.warnings
                } catch (error: IOException) {
                    throw GalleryException(GalleryError.Unreadable, error.message ?: "")
                } catch (error: ImportJsonException) {
                    throw GalleryException(GalleryError.Unreadable, error.message ?: "")
                } finally {
                    source.delete()
                    folder.deleteRecursively()
                }
            }
        try {
            val canvasWidth = session.state.widthPx
            val canvasHeight = session.state.heightPx
            val scale = min(1f, min(canvasWidth.toFloat() / image.width, canvasHeight.toFloat() / image.height))
            val placement = ImagePlacement((canvasWidth - image.width * scale) / 2f, (canvasHeight - image.height * scale) / 2f, scale)
            val layerId = session.newLayer()
            displayName(uri)?.let { name -> session.rename(layerId, name) }
            withContext(Dispatchers.IO) { images.writeBitmapToSurface(image, engine, layerId, canvasWidth, canvasHeight, placement) }
            canvasSession.persistence.documentChanged()
            if (warnings.isNotEmpty()) report = TransferReport(export = false, warnings)
            return layerId
        } finally {
            image.recycle()
        }
    }

    private fun interchangeHeader(uri: Uri): ByteArray? =
        runCatching { contentResolver.openInputStream(uri)?.use { it.readUpTo(ZIP_SIGNATURE.size) } }.getOrNull()

    suspend fun isBrushFile(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val name = fileNameOf(uri)?.lowercase().orEmpty()
            val type = contentResolver.getType(uri)
            when {
                name.endsWith(".${NanquimFileType.Brush.extension}") || name.endsWith(".${NanquimFileType.BrushSet.extension}") -> true
                name.endsWith(".brush") || name.endsWith(".brushset") -> true
                type == NanquimFileType.Brush.mimeType || type == NanquimFileType.BrushSet.mimeType -> true
                isDocumentPackage(uri) || interchangeFormatOf(uri) != null -> false
                else -> {
                    val names = ZipProbe.entryNames(contentResolver, uri)
                    if (names != null) {
                        val procreateArchive = names.any { it == ProcreateBrush.ARCHIVE || it.endsWith("/${ProcreateBrush.ARCHIVE}") }
                        BrushPackage.BRUSH_JSON in names || BrushPackageIo.BRUSHSET_JSON in names ||
                            ProcreateBrushSet.INDEX in names || procreateArchive
                    } else {
                        val isZip = interchangeHeader(uri)?.contentEquals(ZIP_SIGNATURE) == true
                        isZip && runCatching { BrushFiles.kindOf(BrushFiles.read(contentResolver, uri)) != null }.getOrDefault(false)
                    }
                }
            }
        }

    suspend fun isPaletteFile(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val name = fileNameOf(uri)?.lowercase().orEmpty()
            PaletteFileFormat.IMPORT_EXTENSIONS.any { name.endsWith(".$it") } ||
                contentResolver.getType(uri) == NanquimFileType.Palette.mimeType
        }

    suspend fun export(
        artworkId: String,
        options: ExportOptions,
        output: OutputStream,
    ) {
        options.animationFormat?.let { format ->
            if (canvasSession.artwork?.id != artworkId) throw GalleryException(GalleryError.WriteFailed, "Open the artwork to export its animation")
            animationExport.export(format, options.animation, output)
            return
        }
        options.interchangeFormat?.let { interchange ->
            exportInterchange(artworkId, interchange, options, output)
            return
        }
        val format = options.imageFormat
        if (format == null) {
            exportPackage(artworkId, output)
            return
        }
        val color = exportColor(artworkId)
        val render = composite(artworkId, deep = color.deep && format == ImageFormat.Png)
        val ok = withContext(Dispatchers.IO) { writeImage(render, format, options, color, output) }
        if (!ok) throw GalleryException(GalleryError.WriteFailed, artworkId)
    }

    private class ExportColor(
        val profile: DocumentColorProfile?,
        val deep: Boolean,
        val dpi: Double,
    )

    private suspend fun exportColor(artworkId: String): ExportColor {
        val open = canvasSession.artwork
        val state = canvasSession.documentSession?.state
        if (open?.id == artworkId && state != null) {
            return ExportColor(open.info.colorProfile, state.pixelFormat == PixelFormat.Rgba16f, open.info.dpi)
        }
        val artwork = savedArtwork(artworkId)
        return ExportColor(artwork.info.colorProfile, artwork.state.pixelFormat == PixelFormat.Rgba16f, artwork.info.dpi)
    }

    private fun writeImage(
        render: SceneRender,
        format: ImageFormat,
        options: ExportOptions,
        color: ExportColor,
        output: OutputStream,
    ): Boolean {
        val icc = color.profile?.icc
        when (format) {
            ImageFormat.Png -> {
                val file = File(cacheDirectory, "export-${storage.newId()}.png")
                try {
                    val deep = render.pixels.size.toLong() == render.width.toLong() * render.height * DEEP_PIXEL_BYTES
                    val result = InterchangeFiles.exportPng(render.pixels, render.width, render.height, color.dpi, file.path, deep, icc)
                    if (!result.ok) return false
                    file.inputStream().use { it.copyTo(output) }
                    return true
                } finally {
                    file.delete()
                }
            }
            ImageFormat.Jpeg -> {
                val encode = { stream: OutputStream ->
                    images.encode(render.width, render.height, render.pixels, format, options.quality, options.lossless, stream)
                }
                if (icc == null) return encode(output)
                val encoded = java.io.ByteArrayOutputStream()
                if (!encode(encoded)) return false
                output.write(JpegIcc.embed(encoded.toByteArray(), icc))
                return true
            }
            ImageFormat.WebP -> {
                val pixels = if (icc == null) render.pixels else render.pixels.copyOf()
                if (icc != null) ColorProfiles.convertRgba8(pixels, icc, null, premultiplied = false)?.let { return false }
                return images.encode(render.width, render.height, pixels, format, options.quality, options.lossless, output)
            }
        }
    }

    suspend fun fileName(
        artworkId: String,
        options: ExportOptions,
    ): String {
        val name =
            if (canvasSession.artwork?.id == artworkId) {
                canvasSession.artwork?.info?.name
            } else {
                gallery.artworkName(artworkId)
            } ?: artworkId
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "artwork" }
        return "$safe.${options.extension}"
    }

    internal suspend fun exportPackage(
        artworkId: String,
        output: OutputStream,
    ) {
        val open = canvasSession.artwork
        if (open?.id == artworkId) {
            val persistence = canvasSession.persistence
            val saved = CompletableDeferred<Boolean>()
            persistence.saveNow(withThumbnail = true) { saved.complete(it) }
            if (!saved.await()) throw GalleryException(GalleryError.WriteFailed, persistence.lastError ?: "")
            persistence.pause()
            try {
                val fonts = embeddedFonts(canvasSession.documentSession?.state)
                withContext(Dispatchers.IO) { DocumentArchive.write(open.folder, output, fonts) }
            } finally {
                persistence.resume()
            }
            return
        }
        withContext(Dispatchers.IO) {
            val result = storage.read(artworkId)
            if (result !is ArtworkReadResult.Success) throw GalleryException(GalleryError.Unreadable, artworkId)
            DocumentArchive.write(result.artwork.folder, output, embeddedFonts(result.artwork.state))
        }
    }

    private fun embeddedFonts(state: DocumentState?): Map<String, File> {
        val fontIds =
            state
                ?.textModels()
                ?.values
                ?.flatMap { TextLayerContent(it).fontIds }
                .orEmpty()
        return canvasSession.fontLibrary?.embeddableFiles(fontIds.toSet()).orEmpty()
    }

    private suspend fun exportInterchange(
        artworkId: String,
        format: InterchangeFormat,
        options: ExportOptions,
        output: OutputStream,
    ) {
        val file = File(cacheDirectory, "export-${storage.newId()}.${format.extension}")
        try {
            val warnings =
                if (canvasSession.artwork?.id == artworkId) {
                    val persistence = canvasSession.persistence
                    val saved = CompletableDeferred<Boolean>()
                    persistence.saveNow(withThumbnail = false) { saved.complete(it) }
                    if (!saved.await()) throw GalleryException(GalleryError.WriteFailed, persistence.lastError ?: "")
                    persistence.pause()
                    try {
                        val artwork = savedArtwork(artworkId)
                        writeInterchange(artwork, readComposite(deepFor(artwork, format)), options, format, file)
                    } finally {
                        persistence.resume()
                    }
                } else {
                    val artwork = savedArtwork(artworkId)
                    val render = composite(artworkId, deepFor(artwork, format))
                    writeInterchange(artwork, render, options, format, file)
                }
            withContext(Dispatchers.IO) { file.inputStream().use { it.copyTo(output) } }
            if (warnings.isNotEmpty()) report = TransferReport(export = true, warnings)
        } finally {
            file.delete()
        }
    }

    private suspend fun cmykProfileBytes(id: String): ByteArray {
        val entry = canvasSession.colorProfiles.entry(id) ?: throw GalleryException(GalleryError.NotFound, "No color profile $id")
        if (entry.info.colorSpace != ProfileColorSpace.Cmyk || !entry.info.convertsTo) {
            throw GalleryException(GalleryError.Unsupported, "${entry.name} is not a CMYK output profile")
        }
        return canvasSession.colorProfiles.bytes(id) ?: throw GalleryException(GalleryError.NotFound, "No color profile $id")
    }

    private fun deepFor(
        artwork: LoadedArtwork,
        format: InterchangeFormat,
    ): Boolean = artwork.state.pixelFormat == PixelFormat.Rgba16f && (format == InterchangeFormat.Psd || format == InterchangeFormat.Tiff)

    private suspend fun savedArtwork(artworkId: String): LoadedArtwork =
        when (val result = withContext(Dispatchers.IO) { storage.read(artworkId) }) {
            is ArtworkReadResult.Success -> result.artwork
            is ArtworkReadResult.Failure -> throw GalleryException(failureOf(result), result.detail)
        }

    private suspend fun writeInterchange(
        artwork: LoadedArtwork,
        render: SceneRender,
        options: ExportOptions,
        format: InterchangeFormat,
        file: File,
    ): List<ImportWarning> {
        val artwork = artwork.copy(state = artwork.state.withoutPrivateLayers())
        val deep = render.pixels.size.toLong() == render.width.toLong() * render.height * DEEP_PIXEL_BYTES
        val icc = artwork.info.colorProfile?.icc
        val cmyk =
            when (val id = options.cmykProfileId) {
                null -> artwork.info.cmykProfile?.icc
                ExportOptions.RGB_COLOR -> null
                else -> cmykProfileBytes(id)
            }
        val result =
            withContext(Dispatchers.IO) {
                val tree = ImportJson.write(artwork.state, artwork.uuids, artwork.info.dpi)
                when (format) {
                    InterchangeFormat.Psd ->
                        InterchangeFiles.exportPsd(
                            documentFolder = artwork.folder.path,
                            layerTree = tree,
                            backgroundLayerName = backgroundLayerName(),
                            composite = render.pixels,
                            width = render.width,
                            height = render.height,
                            outputPath = file.path,
                            deepComposite = deep,
                            iccProfile = icc,
                            cmykProfile = cmyk,
                        )
                    InterchangeFormat.Tiff ->
                        InterchangeFiles.exportTiff(
                            composite = render.pixels,
                            width = render.width,
                            height = render.height,
                            dpi = artwork.info.dpi,
                            outputPath = file.path,
                            deepComposite = deep,
                            iccProfile = icc,
                            cmykProfile = cmyk,
                        )
                    InterchangeFormat.Procreate -> throw GalleryException(GalleryError.Unsupported, "Import only")
                    InterchangeFormat.Pdf ->
                        InterchangeFiles.exportPdf(
                            documentFolder = artwork.folder.path,
                            layerTree = tree,
                            composite = render.pixels,
                            width = render.width,
                            height = render.height,
                            layersAsPages = options.layersAsPages,
                            jpeg = !options.lossless,
                            jpegQuality = options.quality,
                            title = artwork.info.name,
                            outputPath = file.path,
                            deepComposite = deep,
                            iccProfile = icc,
                            cmykProfile = cmyk,
                        )
                }
            }
        if (!result.ok) {
            val tooLarge = result.errorCode == "tooManyLayers" || result.errorCode == "tooLarge"
            val error =
                when {
                    tooLarge -> GalleryError.TooLarge
                    result.errorCode == "cmykProfileUnusable" -> GalleryError.Unsupported
                    else -> GalleryError.WriteFailed
                }
            throw GalleryException(error, "${result.errorCode} ${result.errorDetail}".trim())
        }
        return result.warnings.map { ImportWarning(it.code, it.detail) }
    }

    private suspend fun composite(
        artworkId: String,
        deep: Boolean = false,
    ): SceneRender {
        if (canvasSession.artwork?.id == artworkId) return readComposite(deep)
        if (canvasSession.documentSession != null) throw GalleryException(GalleryError.WriteFailed, "Close the canvas first")
        val loaded: LoadedArtwork =
            when (val result = withContext(Dispatchers.IO) { storage.read(artworkId) }) {
                is ArtworkReadResult.Success -> result.artwork
                is ArtworkReadResult.Failure -> throw GalleryException(failureOf(result), result.detail)
            }
        val error =
            engine.openDocumentFolder(
                loaded.state.widthPx,
                loaded.state.heightPx,
                loaded.state.toEngineLayerTree(hidePrivate = true).toString(),
                loaded.id,
                loaded.folder.path,
                ArtworkStorage.surfaceFolders(loaded.state, loaded.uuids),
            )
        if (error != null) throw GalleryException(GalleryError.Unreadable, error)
        try {
            return readComposite(deep)
        } finally {
            engine.closeDocument()
        }
    }

    private suspend fun readComposite(deep: Boolean = false): SceneRender {
        val session = canvasSession.documentSession
        session?.exportHidesPrivate = true
        try {
            val result = CompletableDeferred<SceneRender>()
            engine.readComposite(deep) { result.complete(it) }
            val render = result.await()
            render.error?.let { throw GalleryException(GalleryError.Unreadable, it) }
            return render
        } finally {
            session?.exportHidesPrivate = false
        }
    }

    fun shareFile(
        cacheDirectory: File,
        name: String,
    ): File {
        val folder = File(cacheDirectory, EXPORT_FOLDER).apply { mkdirs() }
        var file = File(folder, name)
        var copy = 2
        while (file.exists()) file = File(folder, "${name.substringBeforeLast('.')} ($copy).${name.substringAfterLast('.')}").also { copy++ }
        return file
    }

    companion object {
        const val EXPORT_FOLDER = "exports"
    }
}

internal fun failureOf(result: ArtworkReadResult.Failure): GalleryError =
    when (result.failure) {
        ArtworkFailure.Missing -> GalleryError.NotFound
        ArtworkFailure.NeedsNewerApp -> GalleryError.NeedsNewerApp
        ArtworkFailure.Unsupported -> GalleryError.Unsupported
        else -> GalleryError.Unreadable
    }

private const val PROFILE_FILE = "profile.icc"

private val ANDROID_COLOR_SPACES =
    linkedMapOf(
        "display-p3" to ColorSpace.Named.DISPLAY_P3,
        "adobe-rgb" to ColorSpace.Named.ADOBE_RGB,
        "rec2020" to ColorSpace.Named.BT2020,
    )

private const val DEEP_PIXEL_BYTES = 8

private const val MAXIMUM_LAYER_IMAGE_SIDE_PX = 8192

private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

private const val PROCREATE_ARCHIVE = "Document.archive"

private const val MAXIMUM_IMPORTED_LAYERS = 10_000

private val PSD_SIGNATURE = byteArrayOf(0x38, 0x42, 0x50, 0x53)

private val TIFF_SIGNATURES = listOf(byteArrayOf(0x49, 0x49, 0x2A, 0x00), byteArrayOf(0x4D, 0x4D, 0x00, 0x2A))

private fun interchangeError(code: String): GalleryError =
    when (code) {
        "unsupportedVersion", "unsupportedColorMode", "unsupportedDepth", "unsupportedCompression" -> GalleryError.Unsupported
        "canvasTooLarge", "tooManyLayers" -> GalleryError.TooLarge
        "writeFailed" -> GalleryError.WriteFailed
        else -> GalleryError.Unreadable
    }

private fun InputStream.readUpTo(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var total = 0
    while (total < count) {
        val read = read(bytes, total, count - total)
        if (read < 0) break
        total += read
    }
    return bytes.copyOf(total)
}
