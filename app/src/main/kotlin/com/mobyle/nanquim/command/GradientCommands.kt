package com.mobyle.nanquim.command

import android.content.ContentResolver
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.BrushFiles
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.adjust.GradientEntry
import com.mobyle.nanquim.canvas.adjust.GradientLibrary
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.AdjustmentFilter
import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.formats.gradient.GradientDefinition
import com.mobyle.nanquim.formats.gradient.GradientFile
import com.mobyle.nanquim.formats.gradient.GradientFormatException
import com.mobyle.nanquim.formats.gradient.GradientStop
import java.io.File
import java.io.IOException

object GradientCommandIds {
    const val CREATE = "gradient.create"
    const val DUPLICATE = "gradient.duplicate"
    const val UPDATE = "gradient.update"
    const val DELETE = "gradient.delete"
    const val IMPORT = "gradient.import"
    const val EXPORT = "gradient.export"
}

private fun JsonElement.gradientStops(): List<GradientStop> =
    jsonArray
        .map { stop ->
            val values = stop.jsonArray.map { it.jsonPrimitive.double.toFloat() }
            require(values.size == 4) { "Gradient stops are [position, red, green, blue]" }
            GradientStop(values[0], values[1], values[2], values[3])
        }.sortedBy { it.position }

private fun JsonObject.optionalString(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

fun registerGradientCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
    library: GradientLibrary,
    contentResolver: ContentResolver,
    shareSheet: ShareSheet,
    cacheDirectory: File,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec>,
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Adjust, parameters = parameters, undoable = false),
    ) { _, arguments ->
        try {
            handler(arguments)
        } catch (exception: IllegalArgumentException) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "Invalid arguments")
        } catch (exception: GradientFormatException) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "InvalidGradient: ${exception.message}")
        } catch (exception: IOException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "WriteFailed: ${exception.message}")
        }
    }

    suspend fun save(
        definition: GradientDefinition,
        id: String? = null,
    ): CommandResult {
        val entry = withContext(Dispatchers.IO) { if (id == null) library.write(definition) else library.write(definition, id) }
        library.add(entry)
        canvas.useGradient(entry)
        return CommandResult.Success(JsonObject(mapOf("gradientId" to JsonPrimitive(entry.id))))
    }

    register(
        GradientCommandIds.CREATE,
        R.string.command_gradient_create,
        listOf(ParameterSpec("name", ParameterType.String), ParameterSpec("stops", ParameterType.Array, JsonNull)),
    ) { arguments ->
        val stops =
            (arguments["stops"] as? JsonArray)?.gradientStops()
                ?: listOf(GradientStop(0f, 0f, 0f, 0f), GradientStop(1f, 1f, 1f, 1f))
        save(GradientDefinition(arguments.getValue("name").jsonPrimitive.content, stops))
    }
    register(
        GradientCommandIds.DUPLICATE,
        R.string.command_gradient_duplicate,
        listOf(ParameterSpec("gradientId", ParameterType.String), ParameterSpec("name", ParameterType.String, JsonNull)),
    ) { arguments ->
        val source = library.entry(arguments.getValue("gradientId").jsonPrimitive.content) ?: return@register unknownGradient(arguments)
        save(source.definition.copy(name = arguments.optionalString("name") ?: source.definition.name))
    }
    register(
        GradientCommandIds.UPDATE,
        R.string.command_gradient_update,
        listOf(
            ParameterSpec("gradientId", ParameterType.String),
            ParameterSpec("name", ParameterType.String, JsonNull),
            ParameterSpec("stops", ParameterType.Array, JsonNull),
        ),
    ) { arguments ->
        val entry = library.entry(arguments.getValue("gradientId").jsonPrimitive.content) ?: return@register unknownGradient(arguments)
        if (entry.builtIn) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Built-in gradients cannot be changed")
        val definition =
            GradientDefinition(
                arguments.optionalString("name") ?: entry.definition.name,
                (arguments["stops"] as? JsonArray)?.gradientStops() ?: entry.definition.stops,
            )
        save(definition, entry.id)
    }
    register(
        GradientCommandIds.DELETE,
        R.string.command_gradient_delete,
        listOf(ParameterSpec("gradientId", ParameterType.String)),
    ) { arguments ->
        val entry = library.entry(arguments.getValue("gradientId").jsonPrimitive.content) ?: return@register unknownGradient(arguments)
        if (entry.builtIn) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Built-in gradients cannot be deleted")
        withContext(Dispatchers.IO) { library.deleteFile(entry.id) }
        library.remove(entry.id)
        CommandResult.Success()
    }
    register(
        GradientCommandIds.IMPORT,
        R.string.command_gradient_import,
        listOf(ParameterSpec("uri", ParameterType.String)),
    ) { arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val text =
            withContext(Dispatchers.IO) {
                val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
                input.use { stream -> String(stream.readAtMost(MAXIMUM_FILE_BYTES + 1), Charsets.UTF_8) }
            }
        if (text.length >
            MAXIMUM_FILE_BYTES
        ) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The file is too large")
        }
        save(GradientFile.read(text))
    }
    register(
        GradientCommandIds.EXPORT,
        R.string.command_gradient_export,
        listOf(
            ParameterSpec("gradientId", ParameterType.String),
            ParameterSpec("uri", ParameterType.String, JsonNull),
            ParameterSpec("name", ParameterType.String, JsonNull),
        ),
    ) { arguments ->
        val entry = library.entry(arguments.getValue("gradientId").jsonPrimitive.content) ?: return@register unknownGradient(arguments)
        val uri = arguments.optionalString("uri")?.toUri()
        if (uri != null && uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val name = arguments.optionalString("name") ?: entry.definition.name
        val bytes = GradientFile.write(entry.definition.copy(name = name)).toByteArray(Charsets.UTF_8)
        val fileName = BrushFiles.fileName(name, NanquimFileType.Gradient.extension)
        withContext(Dispatchers.IO) {
            if (uri != null) {
                val output = contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
                output.use { it.write(bytes) }
            } else {
                val folder = File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).apply { mkdirs() }
                val file = File(folder, fileName)
                file.writeBytes(bytes)
                withContext(Dispatchers.Main) { shareSheet.share(listOf(file), NanquimFileType.Gradient.mimeType) }
            }
        }
        CommandResult.Success(JsonObject(mapOf("fileName" to JsonPrimitive(fileName))))
    }
}

private fun CanvasSession.useGradient(entry: GradientEntry) {
    val editor = adjustmentEditor?.takeIf { it.filter == AdjustmentFilter.GradientMap } ?: return
    editor.setGradient(entry.id, entry.definition)
    adjustmentChanged()
}

private fun unknownGradient(arguments: JsonObject) =
    CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown gradient ${arguments["gradientId"]}")

private const val MAXIMUM_FILE_BYTES = 1 shl 20
