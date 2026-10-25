package com.mobyle.nanquim.command

import android.content.ContentResolver
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.text.FontEntry
import com.mobyle.nanquim.text.FontImportFailure
import com.mobyle.nanquim.text.FontImportResult
import com.mobyle.nanquim.text.FontLibrary
import com.mobyle.nanquim.text.FontSource
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import java.io.IOException

object FontCommandIds {
    const val LIST = "fonts.list"
    const val IMPORT = "fonts.import"
    const val DELETE = "fonts.delete"
}

fun registerFontCommands(
    registry: CommandRegistry,
    library: FontLibrary,
    contentResolver: ContentResolver,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Text, parameters = parameters, undoable = false),
    ) { _, arguments -> handler(arguments) }

    fun FontEntry.json() =
        buildJsonObject {
            put("id", id)
            put("family", info.family)
            put("style", info.style)
            put("weight", info.weight)
            put("italic", info.italic)
            put(
                "source",
                when (source) {
                    FontSource.Bundled -> "bundled"
                    FontSource.System -> "system"
                    FontSource.Imported -> "imported"
                    FontSource.Document -> "document"
                },
            )
            put("embeddable", info.embeddable)
        }

    register(FontCommandIds.LIST, R.string.command_fonts_list) {
        library.awaitLoaded()
        CommandResult.Success(buildJsonObject { putJsonArray("fonts") { library.fonts.forEach { add(it.json()) } } })
    }
    register(FontCommandIds.IMPORT, R.string.command_fonts_import, listOf(ParameterSpec("uri", ParameterType.String))) { arguments ->
        val uri =
            arguments
                .getValue("uri")
                .jsonPrimitive.content
                .toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        library.awaitLoaded()
        val bytes =
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
                    input.use { stream ->
                        val read = stream.readAtMost(FontLibrary.MAXIMUM_FONT_BYTES + 1)
                        read.takeIf { it.size <= FontLibrary.MAXIMUM_FONT_BYTES }
                    }
                }
            } catch (exception: IOException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "Cannot read the file")
            } ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The file is too large")
        when (val result = library.import(bytes)) {
            is FontImportResult.Imported ->
                CommandResult.Success(buildJsonObject { putJsonArray("fonts") { result.fonts.forEach { add(it.json()) } } })
            is FontImportResult.Failed ->
                CommandResult.Failure(
                    CommandErrorCode.InvalidArguments,
                    when (result.reason) {
                        FontImportFailure.NotAFont -> "Not a font file"
                        FontImportFailure.TooLarge -> "The file is too large"
                        FontImportFailure.Unreadable -> "The font could not be stored"
                    },
                )
        }
    }
    register(FontCommandIds.DELETE, R.string.command_fonts_delete, listOf(ParameterSpec("fontId", ParameterType.String))) { arguments ->
        val id = arguments.getValue("fontId").jsonPrimitive.content
        if (library.delete(id)) {
            CommandResult.Success()
        } else {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "No imported font $id")
        }
    }
}
