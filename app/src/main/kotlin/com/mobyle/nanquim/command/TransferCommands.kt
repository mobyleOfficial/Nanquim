package com.mobyle.nanquim.command

import kotlinx.serialization.json.int

import kotlinx.serialization.json.addJsonArray

import com.mobyle.nanquim.gallery.AnimationDecoder

import com.mobyle.nanquim.BuildConfig

import com.mobyle.nanquim.engine.AnimationFileWriter

import com.mobyle.nanquim.gallery.AnimationExportSettings

import com.mobyle.nanquim.gallery.AnimationFormat

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.ImageFormat
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.ExportOptions
import com.mobyle.nanquim.gallery.GalleryException
import com.mobyle.nanquim.gallery.ImportTarget
import com.mobyle.nanquim.gallery.InterchangeFormat
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.formats.interchange.ImportWarning
import java.io.File
import java.io.IOException

object TransferCommandIds {
    const val IMPORT = "gallery.import"
    const val SHARE = "gallery.share"
    const val EXPORT_IMAGE = "export.image"
    const val EXPORT_DOCUMENT = "export.document"
    const val DEBUG_DECODE_ANIMATION = "debug.animation.decode"
}

class ShareSheet(
    private val context: Context,
) {
    fun share(
        files: List<File>,
        mimeType: String,
    ) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val intent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }.apply {
                type = mimeType
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}

private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

private fun JsonObject.optionalString(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.contentUri(name: String): Uri? = string(name).toUri().takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }

private const val MAXIMUM_TRACE_CHARACTERS = 4000

private val formatIds =
    ImageFormat.entries.map { it.id } + InterchangeFormat.entries.map { it.id } + AnimationFormat.entries.map { it.id } +
        ExportOptions.NANQUIM_FORMAT_ID

private fun JsonObject.exportOptions(): ExportOptions {
    val format = string("format")
    return ExportOptions(
        imageFormat = ImageFormat.fromId(format),
        interchangeFormat = InterchangeFormat.fromId(format),
        quality = (get("quality") as? JsonPrimitive)?.intOrNull ?: ExportOptions.DEFAULT_QUALITY,
        lossless = (get("lossless") as? JsonPrimitive)?.contentOrNull != "false",
        layersAsPages = (get("layersAsPages") as? JsonPrimitive)?.contentOrNull == "true",
        cmykProfileId = optionalString("cmykProfile")?.takeIf { it.isNotEmpty() },
        animationFormat = AnimationFormat.fromId(format),
        animation =
            AnimationExportSettings(
                firstFrame = optionalInt("firstFrame") ?: 1,
                lastFrame = optionalInt("lastFrame") ?: Int.MAX_VALUE,
                framesPerSecond = optionalInt("framesPerSecond"),
                scalePercent = optionalInt("scale")?.takeIf { it in AnimationExportSettings.SCALES } ?: 100,
                plays = optionalInt("plays"),
                includeBackground = optionalString("includeBackground") != "false",
                dither = AnimationFileWriter.Dither.entries.firstOrNull { it.name.equals(optionalString("dither"), ignoreCase = true) }
                    ?: AnimationFileWriter.Dither.FloydSteinberg,
                hevc = optionalString("hevc") == "true",
            ),
    )
}

private fun JsonObject.optionalInt(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

private val exportParameters =
    listOf(
        ParameterSpec("format", ParameterType.Enumeration, JsonPrimitive("png"), allowedValues = formatIds),
        ParameterSpec("quality", ParameterType.Integer, JsonPrimitive(ExportOptions.DEFAULT_QUALITY), minimum = 1.0, maximum = 100.0),
        ParameterSpec("lossless", ParameterType.Boolean, JsonPrimitive(true)),
        ParameterSpec("layersAsPages", ParameterType.Boolean, JsonPrimitive(false)),
        ParameterSpec("cmykProfile", ParameterType.String, JsonNull),
        ParameterSpec("firstFrame", ParameterType.Integer, JsonNull, minimum = 1.0),
        ParameterSpec("lastFrame", ParameterType.Integer, JsonNull, minimum = 1.0),
        ParameterSpec("framesPerSecond", ParameterType.Integer, JsonNull, minimum = 1.0, maximum = 60.0),
        ParameterSpec("scale", ParameterType.Integer, JsonNull),
        ParameterSpec("plays", ParameterType.Integer, JsonNull, minimum = 0.0),
        ParameterSpec("includeBackground", ParameterType.Boolean, JsonPrimitive(true)),
        ParameterSpec("dither", ParameterType.String, JsonNull),
        ParameterSpec("hevc", ParameterType.Boolean, JsonPrimitive(false)),
    )

fun registerTransferCommands(
    registry: CommandRegistry,
    transfer: ArtworkTransfer,
    gallery: Gallery,
    canvasSession: CanvasSession,
    contentResolver: ContentResolver,
    shareSheet: ShareSheet,
    cacheDirectory: File,
    brushUi: BrushUiState,
    onBrushImportReport: (List<ImportWarning>) -> Unit,
) {
    fun register(
        id: String,
        titleRes: Int,
        category: CommandCategory,
        parameters: List<ParameterSpec>,
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(CommandSpec(id, titleRes, category, parameters, undoable = false)) { _, arguments ->
        try {
            handler(arguments)
        } catch (error: GalleryException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "${error.error}: ${error.message}")
        } catch (error: IOException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "WriteFailed: ${error.message}")
        }
    }

    if (BuildConfig.DEVELOPER_TOOLS) {
        register(
            TransferCommandIds.DEBUG_DECODE_ANIMATION,
            R.string.command_debug_decode_animation,
            CommandCategory.Export,
            listOf(
                ParameterSpec("name", ParameterType.String, JsonNull),
                ParameterSpec("timelapseSegment", ParameterType.Integer, JsonNull),
                ParameterSpec("frames", ParameterType.Array, JsonNull),
                ParameterSpec("points", ParameterType.Array, JsonNull),
            ),
        ) { arguments ->
            val segment = (arguments["timelapseSegment"] as? JsonPrimitive)?.intOrNull
            val folder =
                if (segment != null) {
                    File(canvasSession.artwork?.folder ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork"), "timelapse")
                } else {
                    File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER)
                }
            val name = if (segment != null) "segment-$segment.mp4" else arguments.optionalString("name").orEmpty()
            val file = File(folder, name)
            if (file.parentFile?.canonicalPath != folder.canonicalPath || !file.isFile) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No file named $name")
            }
            val frames = (arguments["frames"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: listOf(0)
            val points =
                (arguments["points"] as? JsonArray)?.map { point -> (point as JsonArray).let { it[0].jsonPrimitive.int to it[1].jsonPrimitive.int } }
                    ?: emptyList()
            val decoded =
                try {
                    withContext(Dispatchers.IO) { AnimationDecoder.decode(file, frames, points) }
                } catch (exception: Exception) {
                    return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, exception.stackTraceToString().take(MAXIMUM_TRACE_CHARACTERS))
                }
            CommandResult.Success(
                buildJsonObject {
                    put("format", decoded.format)
                    put("mime", decoded.mime)
                    put("width", decoded.width)
                    put("height", decoded.height)
                    put("frames", decoded.frames)
                    put("durationMilliseconds", decoded.durationMilliseconds)
                    putJsonArray("colors") {
                        decoded.colors.forEach { frame -> addJsonArray { frame.forEach { color -> addJsonArray { color.forEach { add(JsonPrimitive(it)) } } } } }
                    }
                },
            )
        }
    }

    register(
        TransferCommandIds.IMPORT,
        R.string.command_gallery_import,
        CommandCategory.Gallery,
        listOf(
            ParameterSpec("uri", ParameterType.String),
            ParameterSpec("target", ParameterType.Enumeration, JsonPrimitive(ImportTarget.NewArtwork.id), allowedValues = ImportTarget.entries.map { it.id }),
            ParameterSpec("private", ParameterType.Boolean, JsonPrimitive(false)),
        ),
    ) { arguments ->
        val uri = arguments.contentUri("uri") ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        if (transfer.isBrushFile(uri)) return@register importBrushFile(brushUi, contentResolver, uri, onBrushImportReport)
        if (transfer.isDocumentPackage(uri)) {
            val id = gallery.whileBusy { transfer.importDocument(uri) }
            return@register CommandResult.Success(buildJsonObject { put("id", id) })
        }
        val layerTarget = ImportTarget.fromId(arguments.string("target")) == ImportTarget.Layer
        transfer.interchangeFormatOf(uri)?.takeUnless { it == InterchangeFormat.Tiff && layerTarget }?.let { format ->
            val imported = gallery.whileBusy { transfer.importInterchange(uri, format) }
            return@register CommandResult.Success(
                buildJsonObject {
                    put("id", imported.artworkId)
                    putJsonArray("warnings") {
                        imported.warnings.forEach { warning ->
                            addJsonObject {
                                put("code", warning.code)
                                put("detail", warning.detail)
                            }
                        }
                    }
                },
            )
        }
        when (ImportTarget.fromId(arguments.string("target"))) {
            ImportTarget.Layer -> {
                val layerId = transfer.importImageAsLayer(uri)
                if (arguments.optionalString("private") == "true") canvasSession.documentSession?.setPrivate(layerId, true)
                CommandResult.Success(buildJsonObject { put("layerId", layerId) })
            }
            else -> {
                val id = gallery.whileBusy { transfer.importImageAsArtwork(uri) }
                CommandResult.Success(buildJsonObject { put("id", id) })
            }
        }
    }

    register(
        TransferCommandIds.EXPORT_IMAGE,
        R.string.command_export_image,
        CommandCategory.Export,
        listOf(ParameterSpec("uri", ParameterType.String), ParameterSpec("id", ParameterType.String, JsonNull)) + exportParameters,
    ) { arguments ->
        val uri = arguments.contentUri("uri") ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        val id = arguments.optionalString("id") ?: canvasSession.artwork?.id ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No artwork")
        val output = withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt") } ?: return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Cannot write $uri")
        output.use { transfer.export(id, arguments.exportOptions(), it) }
        CommandResult.Success()
    }

    register(
        TransferCommandIds.EXPORT_DOCUMENT,
        R.string.command_export_document,
        CommandCategory.Export,
        listOf(ParameterSpec("uri", ParameterType.String), ParameterSpec("id", ParameterType.String, JsonNull)),
    ) { arguments ->
        val uri = arguments.contentUri("uri") ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        val id = arguments.optionalString("id") ?: canvasSession.artwork?.id ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No artwork")
        val output = withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt") } ?: return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Cannot write $uri")
        output.use { transfer.export(id, ExportOptions(imageFormat = null), it) }
        CommandResult.Success()
    }

    register(
        TransferCommandIds.SHARE,
        R.string.command_gallery_share,
        CommandCategory.Gallery,
        listOf(ParameterSpec("ids", ParameterType.Array, JsonNull)) + exportParameters,
    ) { arguments ->
        val ids =
            (arguments["ids"] as? JsonArray)?.map { it.jsonPrimitive.content }
                ?: listOfNotNull(canvasSession.artwork?.id)
        if (ids.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Nothing to share")
        val options = arguments.exportOptions()
        withContext(Dispatchers.IO) { File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).deleteRecursively() }
        val files =
            ids.map { id ->
                val file = transfer.shareFile(cacheDirectory, transfer.fileName(id, options))
                withContext(Dispatchers.IO) { file.outputStream() }.use { transfer.export(id, options, it) }
                file
            }
        shareSheet.share(files, options.mimeType)
        CommandResult.Success()
    }
}
