package com.mobyle.nanquim.command

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushFiles
import com.mobyle.nanquim.color.PaletteEntry
import com.mobyle.nanquim.color.PaletteFileFormat
import com.mobyle.nanquim.color.PaletteImages
import com.mobyle.nanquim.color.PaletteImport
import com.mobyle.nanquim.color.PaletteLibrary
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.document.color.PaletteExtractor
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.interchange.ImportWarning
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteFile
import com.mobyle.nanquim.formats.palette.PaletteFormatException
import com.mobyle.nanquim.formats.palette.PaletteInterchange
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.File
import java.io.IOException
import java.util.UUID

object PaletteCommandIds {
    const val LIST = "palette.list"
    const val CREATE = "palette.create"
    const val RENAME = "palette.rename"
    const val DELETE = "palette.delete"
    const val DUPLICATE = "palette.duplicate"
    const val SET_DEFAULT = "palette.setDefault"
    const val MOVE = "palette.move"
    const val SET_SWATCH = "palette.setSwatch"
    const val EXPORT = "palette.export"
    const val IMPORT = "palette.import"
    const val ADD = "palette.add"
    const val CREATE_FROM_IMAGE = "palette.createFromImage"
}

fun registerPaletteCommands(
    registry: CommandRegistry,
    library: PaletteLibrary,
    contentResolver: ContentResolver,
    shareSheet: ShareSheet,
    cacheDirectory: File,
    onImportReport: (List<ImportWarning>) -> Unit,
    cmykProfile: suspend () -> ByteArray? = { null },
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Color, parameters = parameters, undoable = false),
    ) { _, arguments -> handler(arguments) }

    val paletteId = ParameterSpec("paletteId", ParameterType.String)
    val name = ParameterSpec("name", ParameterType.String)

    fun JsonObject.palette(): PaletteEntry? = library.entry(getValue("paletteId").jsonPrimitive.content)

    fun unknown(arguments: JsonObject) =
        CommandResult.Failure(CommandErrorCode.InvalidArguments, "No palette ${arguments.getValue("paletteId").jsonPrimitive.content}")

    fun created(entry: PaletteEntry) = CommandResult.Success(buildJsonObject { put("paletteId", entry.id) })

    register(PaletteCommandIds.LIST, R.string.command_palette_list) {
        CommandResult.Success(
            buildJsonObject {
                put("defaultId", library.defaultPalette?.id?.let(::JsonPrimitive) ?: JsonNull)
                putJsonArray("palettes") {
                    for (entry in library.entries) {
                        addJsonObject {
                            put("id", entry.id)
                            put("name", entry.palette.name)
                            put("columns", entry.palette.columns)
                            putJsonArray("swatches") {
                                for (swatch in entry.palette.swatches) {
                                    if (swatch == null) {
                                        add(JsonNull)
                                    } else {
                                        addJsonObject {
                                            putJsonArray("color") {
                                                with(swatch.color) { listOf(red, green, blue, alpha).forEach { add(it) } }
                                            }
                                            put("name", swatch.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    register(
        PaletteCommandIds.CREATE,
        R.string.command_palette_create,
        listOf(
            name,
            ParameterSpec(
                "columns",
                ParameterType.Integer,
                JsonPrimitive(Palette.DEFAULT_COLUMNS),
                1.0,
                Palette.MAXIMUM_COLUMNS.toDouble(),
            ),
        ),
    ) { arguments ->
        created(library.create(arguments.getValue("name").jsonPrimitive.content, arguments.getValue("columns").jsonPrimitive.int))
    }
    register(PaletteCommandIds.RENAME, R.string.command_palette_rename, listOf(paletteId, name)) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        library.rename(entry.id, arguments.getValue("name").jsonPrimitive.content)
        CommandResult.Success()
    }
    register(PaletteCommandIds.DELETE, R.string.command_palette_delete, listOf(paletteId)) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        library.delete(entry.id)
        CommandResult.Success()
    }
    register(
        PaletteCommandIds.DUPLICATE,
        R.string.command_palette_duplicate,
        listOf(paletteId, ParameterSpec("name", ParameterType.String, JsonNull)),
    ) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        created(library.duplicate(entry.id, (arguments["name"] as? JsonPrimitive)?.contentOrNull))
    }
    register(PaletteCommandIds.SET_DEFAULT, R.string.command_palette_set_default, listOf(paletteId)) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        library.setDefault(entry.id)
        CommandResult.Success()
    }
    register(
        PaletteCommandIds.MOVE,
        R.string.command_palette_move,
        listOf(paletteId, ParameterSpec("index", ParameterType.Integer, minimum = 0.0)),
    ) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        library.move(entry.id, arguments.getValue("index").jsonPrimitive.int)
        CommandResult.Success()
    }
    register(
        PaletteCommandIds.SET_SWATCH,
        R.string.command_palette_set_swatch,
        listOf(
            paletteId,
            ParameterSpec("index", ParameterType.Integer, minimum = 0.0, maximum = (Palette.MAXIMUM_SWATCHES - 1).toDouble()),
            ParameterSpec("color", ParameterType.Array, JsonNull),
            ParameterSpec("name", ParameterType.String, JsonPrimitive("")),
        ),
    ) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        val index = arguments.getValue("index").jsonPrimitive.int
        if (index > entry.palette.swatches.size) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Slot $index is past the end")
        }
        val swatch =
            when (val color = arguments["color"]) {
                null, JsonNull -> null
                else -> {
                    val components =
                        (color as JsonArray)
                            .map { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                            .takeIf { values -> values.size in 3..4 && values.all { it != null && it in 0f..1f } }
                            ?.map { it!! }
                            ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A color is 3 or 4 values 0-1")
                    PaletteSwatch(
                        RgbaColor(components[0], components[1], components[2], components.getOrElse(3) { 1f }),
                        arguments.getValue("name").jsonPrimitive.content,
                    )
                }
            }
        library.setSwatch(entry.id, index, swatch)
        CommandResult.Success()
    }
    register(
        PaletteCommandIds.ADD,
        R.string.command_palette_add,
        listOf(
            name,
            ParameterSpec("colors", ParameterType.Array),
            ParameterSpec(
                "columns",
                ParameterType.Integer,
                JsonPrimitive(Palette.DEFAULT_COLUMNS),
                1.0,
                Palette.MAXIMUM_COLUMNS.toDouble(),
            ),
        ),
    ) { arguments ->
        val colors = arguments.getValue("colors") as JsonArray
        if (colors.size > Palette.MAXIMUM_SWATCHES) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "At most ${Palette.MAXIMUM_SWATCHES} colors")
        }
        val swatches =
            colors.map { color ->
                val components =
                    (color as? JsonArray)
                        ?.map { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                        ?.takeIf { values -> values.size in 3..4 && values.all { it != null && it in 0f..1f } }
                        ?.map { it!! }
                        ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A color is 3 or 4 values 0-1")
                PaletteSwatch(RgbaColor(components[0], components[1], components[2], components.getOrElse(3) { 1f }))
            }
        val palette = Palette(arguments.getValue("name").jsonPrimitive.content, swatches, arguments.getValue("columns").jsonPrimitive.int)
        created(library.add(PaletteEntry(UUID.randomUUID().toString(), palette)))
    }
    register(
        PaletteCommandIds.CREATE_FROM_IMAGE,
        R.string.command_palette_create_from_image,
        listOf(
            ParameterSpec("uri", ParameterType.String),
            name,
            ParameterSpec(
                "count",
                ParameterType.Integer,
                JsonPrimitive(PaletteExtractor.DEFAULT_COUNT),
                1.0,
                Palette.MAXIMUM_SWATCHES.toDouble(),
            ),
        ),
    ) { arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val count = arguments.getValue("count").jsonPrimitive.int
        val colors =
            try {
                withContext(Dispatchers.Default) {
                    val bitmap = PaletteImages.decode(contentResolver, uri)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    PaletteExtractor.extract(pixels, bitmap.width, bitmap.height, count).also { bitmap.recycle() }
                }
            } catch (exception: IOException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Cannot read the image: ${exception.message}")
            }
        val palette =
            Palette(
                arguments.getValue("name").jsonPrimitive.content,
                colors.map { PaletteSwatch(RgbaColor(it.red, it.green, it.blue, 1f)) },
            )
        created(library.add(PaletteEntry(UUID.randomUUID().toString(), palette)))
    }
    register(
        PaletteCommandIds.IMPORT,
        R.string.command_palette_import,
        listOf(ParameterSpec("uri", ParameterType.String), ParameterSpec("name", ParameterType.String, JsonNull)),
    ) { arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val profile = cmykProfile()
        val (palette, warnings) =
            try {
                withContext(Dispatchers.IO) {
                    val fallbackName =
                        (arguments["name"] as? JsonPrimitive)?.contentOrNull
                            ?: contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                                ?.substringBeforeLast('.')
                                .orEmpty()
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
                    PaletteImport.read(input.use(PaletteImport::readLimited), fallbackName, profile)
                }
            } catch (exception: PaletteImport.TooLargeException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The file is too large")
            } catch (exception: PaletteFormatException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Not a palette: ${exception.message}")
            }
        val entry = library.add(PaletteEntry(UUID.randomUUID().toString(), palette))
        if (warnings.isNotEmpty()) onImportReport(warnings)
        CommandResult.Success(
            buildJsonObject {
                put("paletteId", entry.id)
                putJsonArray("warnings") { warnings.forEach { warning -> add(warning.code) } }
            },
        )
    }
    register(
        PaletteCommandIds.EXPORT,
        R.string.command_palette_export,
        listOf(
            paletteId,
            ParameterSpec("uri", ParameterType.String, JsonNull),
            ParameterSpec(
                "format",
                ParameterType.Enumeration,
                JsonPrimitive(PaletteFileFormat.Nanquim.id),
                allowedValues = PaletteFileFormat.entries.map { it.id },
            ),
        ),
    ) { arguments ->
        val entry = arguments.palette() ?: return@register unknown(arguments)
        val uri = (arguments["uri"] as? JsonPrimitive)?.contentOrNull?.toUri()
        if (uri != null && uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val format = PaletteFileFormat.fromId(arguments.getValue("format").jsonPrimitive.content)!!
        val bytes =
            when (format) {
                PaletteFileFormat.Nanquim -> PaletteFile.write(entry.id, entry.palette).toByteArray(Charsets.UTF_8)
                PaletteFileFormat.Ase -> PaletteInterchange.writeAse(entry.palette)
                PaletteFileFormat.Gpl -> PaletteInterchange.writeGpl(entry.palette).toByteArray(Charsets.UTF_8)
            }
        val fileName = BrushFiles.fileName(entry.palette.name, format.extension)
        withContext(Dispatchers.IO) {
            if (uri != null) {
                val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
                output.use { it.write(bytes) }
            } else {
                val folder = File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).apply { mkdirs() }
                val file = File(folder, fileName)
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) { shareSheet.share(listOf(file), format.mimeType) }
            }
        }
        CommandResult.Success(buildJsonObject { put("fileName", fileName) })
    }
}
