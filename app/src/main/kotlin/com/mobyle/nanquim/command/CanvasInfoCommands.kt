package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.formats.nanquim.ArtistInfo

object CanvasInfoCommandIds {
    const val SHOW = "canvas.info.show"
    const val GET = "canvas.info.get"
    const val SET_ARTIST = "canvas.setArtist"
}

private val requiresArtwork: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

fun registerCanvasInfoCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    registry.register(
        CommandSpec(
            CanvasInfoCommandIds.SHOW,
            R.string.command_canvas_info_show,
            CommandCategory.Canvas,
            listOf(ParameterSpec("visible", ParameterType.Boolean, JsonNull)),
            undoable = false,
            availability = requiresArtwork,
        ),
    ) { _, arguments ->
        canvas.canvasInfoVisible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: !canvas.canvasInfoVisible
        CommandResult.Success(JsonObject(mapOf("visible" to JsonPrimitive(canvas.canvasInfoVisible))))
    }
    registry.register(
        CommandSpec(
            CanvasInfoCommandIds.GET,
            R.string.command_canvas_info_get,
            CommandCategory.Canvas,
            undoable = false,
            availability = requiresArtwork,
        ),
    ) { _, _ ->
        canvasInfoJson(canvas)?.let { CommandResult.Success(it) } ?: CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
    }
    registry.register(
        CommandSpec(
            CanvasInfoCommandIds.SET_ARTIST,
            R.string.command_canvas_set_artist,
            CommandCategory.Canvas,
            listOf(ParameterSpec("name", ParameterType.String, JsonNull), ParameterSpec("signature", ParameterType.Array, JsonNull)),
            undoable = false,
            availability = requiresArtwork,
        ),
    ) { _, arguments ->
        val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
        val current = open.info.artist ?: ArtistInfo()
        val name = (arguments["name"] as? JsonPrimitive)?.contentOrNull ?: current.name
        val signature =
            (arguments["signature"] as? JsonArray)?.let { strokes ->
                ArtistInfo.read(JsonObject(mapOf("signature" to strokes)))?.signature
                    ?: if (strokes.isEmpty()) {
                        emptyList()
                    } else {
                        return@register CommandResult.Failure(
                            CommandErrorCode.InvalidArguments,
                            "Unreadable signature",
                        )
                    }
            } ?: current.signature
        canvas.setArtist(ArtistInfo(name.trim(), signature))
        CommandResult.Success(canvasInfoJson(canvas) ?: JsonObject(emptyMap()))
    }
}

fun canvasInfoJson(canvas: CanvasSession): JsonObject? {
    val open = canvas.artwork ?: return null
    val state = canvas.documentSession?.state ?: return null
    val info = open.info
    return buildJsonObject {
        put("name", info.name)
        put("width", state.widthPx)
        put("height", state.heightPx)
        put("dpi", info.dpi)
        put("unit", info.unit.id)
        put("layers", state.layerCount)
        put("maximumLayers", canvas.layerLimitFor(state.widthPx, state.heightPx))
        put("colorProfile", info.colorProfile?.name ?: "sRGB")
        put("bitsPerChannel", state.pixelFormat.bitsPerChannel)
        put(
            "timelapse",
            info.timelapse?.let { timelapse ->
                buildJsonObject {
                    put("enabled", timelapse.enabled)
                    put("resolution", timelapse.resolution.id)
                    put("hevc", timelapse.hevc)
                }
            } ?: JsonNull,
        )
        put("timeSpentSeconds", info.timeSpentSeconds)
        put("strokes", info.strokeCount)
        put("createdAt", info.createdAt.toString())
        put("modifiedAt", info.modifiedAt.toString())
        put("artist", info.artist?.name ?: "")
        put("signatureStrokes", info.artist?.signature?.size ?: 0)
    }
}
