package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.command.TextKey
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.platform.ExternalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object VideoCommandIds {
    const val IMPORT = "video.import"
    const val SET_TIME = "video.setTime"
    const val RASTERIZE = "video.rasterize"
}

fun registerVideoCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
    files: ExternalFiles,
) {
    val artwork: (AppState) -> Availability = { state ->
        if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
    }

    fun register(
        id: String,
        titleKey: TextKey,
        parameters: List<ParameterSpec>,
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleKey = titleKey,
            category = CommandCategory.Layer,
            parameters = parameters,
            undoable = true,
            availability = artwork,
        ),
    ) { _, arguments ->
        try {
            handler(arguments)
        } catch (error: LayerOperationException) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, error.message ?: "")
        }
    }

    fun JsonObject.layerId(): Int? = (this["layerId"] as? JsonPrimitive)?.intOrNull ?: canvas.documentSession?.state?.activeLayerId

    register(VideoCommandIds.IMPORT, TextKey("command_video_import"), listOf(ParameterSpec("uri", ParameterType.String))) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        val uri = (arguments["uri"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (!files.accepts(uri)) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
        }
        val name =
            withContext(Dispatchers.IO) {
                files.displayName(uri)
            } ?: "Video"
        val imported =
            canvas.videoLayers.import(uri, name)
                ?: return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Not a readable video (or larger than 256 MB)")
        val id = session.addVideoLayer(imported.name, imported.content)
        CommandResult.Success(
            buildJsonObject {
                put("layerId", id)
                put("durationMicroseconds", imported.content.durationMicroseconds)
            },
        )
    }
    register(
        VideoCommandIds.SET_TIME,
        TextKey("command_video_set_time"),
        listOf(
            ParameterSpec("layerId", ParameterType.Integer, JsonNull),
            ParameterSpec("timeMicroseconds", ParameterType.Integer),
            ParameterSpec("final", ParameterType.Boolean, JsonPrimitive(true)),
            ParameterSpec("wait", ParameterType.Boolean, JsonPrimitive(false)),
        ),
    ) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        val id = arguments.layerId() ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No layer")
        val time = (arguments["timeMicroseconds"] as? JsonPrimitive)?.longOrNull ?: 0L
        session.setVideoTime(id, time, final = (arguments["final"] as? JsonPrimitive)?.booleanOrNull != false)
        if ((arguments["wait"] as? JsonPrimitive)?.booleanOrNull == true) canvas.videoLayers.idle()
        CommandResult.Success()
    }
    register(
        VideoCommandIds.RASTERIZE,
        TextKey("command_video_rasterize"),
        listOf(ParameterSpec("layerId", ParameterType.Integer, JsonNull)),
    ) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        session.rasterizeVideo(arguments.layerId() ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No layer"))
        CommandResult.Success()
    }
}
