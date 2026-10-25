package com.mobyle.nanquim.command

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.color.ColorProfileEntry
import com.mobyle.nanquim.color.ColorProfileException
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.isPrintProfile
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.ColorProfiles
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import java.io.IOException
import kotlin.coroutines.resume

object ColorProfileCommandIds {
    const val LIST = "colorProfile.list"
    const val IMPORT = "colorProfile.import"
    const val DELETE = "colorProfile.delete"
    const val ASSIGN = "canvas.assignProfile"
    const val CONVERT = "canvas.convertProfile"
    const val SET_CMYK = "canvas.setCmykProfile"
}

fun registerColorProfileCommands(
    registry: CommandRegistry,
    library: ColorProfileLibrary,
    canvas: CanvasSession,
    contentResolver: ContentResolver,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        category: CommandCategory = CommandCategory.Color,
        needsArtwork: Boolean = false,
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = category,
            parameters = parameters,
            undoable = false,
            availability = { state ->
                if (!needsArtwork || state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
            },
        ),
    ) { _, arguments -> handler(arguments) }

    val profileId = ParameterSpec("profileId", ParameterType.String)

    fun describe(entry: ColorProfileEntry) =
        buildJsonObject {
            put("id", entry.id)
            put("name", entry.name)
            put(
                "colorSpace",
                entry.info.colorSpace.name
                    .lowercase(),
            )
            put("builtIn", entry.builtIn)
            put("documentProfile", entry.info.isDocumentProfile)
            put("printProfile", entry.isPrintProfile)
        }

    fun documentEntry(arguments: JsonObject): Pair<ColorProfileEntry?, CommandResult.Failure?> {
        val id = arguments.getValue("profileId").jsonPrimitive.content
        val entry = library.entry(id) ?: return null to CommandResult.Failure(CommandErrorCode.InvalidArguments, "No profile $id")
        if (!entry.info.isDocumentProfile) {
            return null to
                CommandResult.Failure(CommandErrorCode.InvalidArguments, "${entry.name} is not an RGB profile a document can use")
        }
        return entry to null
    }

    register(ColorProfileCommandIds.LIST, R.string.command_color_profile_list) {
        CommandResult.Success(
            buildJsonObject {
                put(
                    "documentProfile",
                    if (canvas.artwork !=
                        null
                    ) {
                        JsonPrimitive(canvas.colorProfile?.name ?: NanquimManifest.SRGB_PROFILE_NAME)
                    } else {
                        JsonNull
                    },
                )
                put("cmykProfile", canvas.cmykProfile?.name?.let(::JsonPrimitive) ?: JsonNull)
                putJsonArray("profiles") { library.entries.forEach { add(describe(it)) } }
                canvas.displayColor.let { display ->
                    put(
                        "display",
                        buildJsonObject {
                            put("space", if (display.displayP3) "display-p3" else "srgb")
                            put("converted", display.converted)
                            display.error?.let { put("error", it) }
                        },
                    )
                }
            },
        )
    }
    register(
        ColorProfileCommandIds.IMPORT,
        R.string.command_color_profile_import,
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
        val (bytes, fileName) =
            try {
                withContext(Dispatchers.IO) {
                    val name =
                        contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                            ?.substringBeforeLast('.')
                            .orEmpty()
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")
                    val bytes = input.use { it.readNBytesLimited(ColorProfiles.MAXIMUM_BYTES + 1) }
                    bytes to name
                }
            } catch (exception: IOException) {
                return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, exception.message.orEmpty())
            }
        if (bytes.size > ColorProfiles.MAXIMUM_BYTES) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The file is too large")
        }
        val entry =
            try {
                library.import(bytes, fileName)
            } catch (exception: ColorProfileException) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Not an ICC profile: ${exception.message}")
            }
        CommandResult.Success(describe(entry))
    }
    register(ColorProfileCommandIds.DELETE, R.string.command_color_profile_delete, listOf(profileId)) { arguments ->
        if (library.delete(arguments.getValue("profileId").jsonPrimitive.content)) {
            CommandResult.Success()
        } else {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, "Not an imported profile")
        }
    }
    register(
        ColorProfileCommandIds.ASSIGN,
        R.string.canvas_assign_profile,
        listOf(profileId),
        CommandCategory.Canvas,
        needsArtwork = true,
    ) { arguments ->
        if (canvas.artwork == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
        val (entry, failure) = documentEntry(arguments)
        if (failure != null) return@register failure
        canvas.assignColorProfile(library.documentProfile(entry!!.id))
        CommandResult.Success(buildJsonObject { put("profile", entry.name) })
    }
    register(
        ColorProfileCommandIds.SET_CMYK,
        R.string.canvas_set_cmyk_profile,
        listOf(ParameterSpec("profileId", ParameterType.String, JsonNull)),
        CommandCategory.Canvas,
        needsArtwork = true,
    ) { arguments ->
        if (canvas.artwork == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
        val id = (arguments["profileId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null) {
            canvas.assignCmykProfile(null)
            return@register CommandResult.Success(buildJsonObject { put("cmykProfile", JsonNull) })
        }
        val entry = library.entry(id) ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No profile $id")
        if (!entry.isPrintProfile) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "${entry.name} is not a CMYK profile that converts both ways")
        }
        canvas.assignCmykProfile(library.printProfile(id))
        CommandResult.Success(buildJsonObject { put("cmykProfile", entry.name) })
    }
    register(
        ColorProfileCommandIds.CONVERT,
        R.string.canvas_convert_profile,
        listOf(profileId),
        CommandCategory.Canvas,
        needsArtwork = true,
    ) { arguments ->
        if (canvas.artwork == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
        val (entry, failure) = documentEntry(arguments)
        if (failure != null) return@register failure
        val profile = library.documentProfile(entry!!.id)
        val error = suspendCancellableCoroutine { continuation -> canvas.convertColorProfile(profile) { continuation.resume(it) } }
        if (error != null) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, error)
        } else {
            CommandResult.Success(buildJsonObject { put("profile", entry.name) })
        }
    }
}

private fun java.io.InputStream.readNBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
