package com.mobyle.nanquim.command

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
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
import com.mobyle.nanquim.document.layer.CanvasAnchor
import com.mobyle.nanquim.document.layer.CanvasOperation
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.PixelFormat
import kotlin.coroutines.resume

object CanvasSizeCommandIds {
    const val CROP = "canvas.crop"
    const val RESIZE = "canvas.resize"
    const val ROTATE = "canvas.rotate"
    const val FLIP_HORIZONTAL = "canvas.flipHorizontal"
    const val FLIP_VERTICAL = "canvas.flipVertical"
    const val INFO = "canvas.info"
    const val COLOR_DEPTH = "canvas.colorDepth"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private val ANCHORS = CanvasAnchor.entries.associateBy { it.name.replaceFirstChar { first -> first.lowercase() } }

fun registerCanvasSizeCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        undoable: Boolean = true,
        action: (JsonObject) -> JsonObject?,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = CommandCategory.Canvas,
            parameters = parameters,
            undoable = undoable,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        if (canvas.documentSession == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open document")
        try {
            CommandResult.Success(action(arguments) ?: JsonObject(emptyMap()))
        } catch (exception: LayerOperationException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, exception.message.orEmpty())
        }
    }

    fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

    register(
        CanvasSizeCommandIds.CROP,
        R.string.canvas_crop,
        listOf("x", "y", "width", "height").map { ParameterSpec(it, ParameterType.Integer) },
    ) { arguments ->
        canvas.canvasOperation(
            CanvasOperation.Crop(arguments.int("x"), arguments.int("y"), arguments.int("width"), arguments.int("height")),
        )
        null
    }
    register(
        CanvasSizeCommandIds.RESIZE,
        R.string.canvas_size,
        listOf(
            ParameterSpec("width", ParameterType.Integer, minimum = 1.0),
            ParameterSpec("height", ParameterType.Integer, minimum = 1.0),
            ParameterSpec(
                "anchor",
                ParameterType.Enumeration,
                allowedValues = ANCHORS.keys.toList(),
                defaultValue = JsonPrimitive("center"),
            ),
            ParameterSpec("resample", ParameterType.Boolean, defaultValue = JsonPrimitive(false)),
            ParameterSpec("dpi", ParameterType.Number, defaultValue = JsonNull),
        ),
    ) { arguments ->
        val anchor = (arguments["anchor"] as? JsonPrimitive)?.content?.let(ANCHORS::get) ?: CanvasAnchor.Center
        val resample = (arguments["resample"] as? JsonPrimitive)?.booleanOrNull == true
        canvas.canvasOperation(CanvasOperation.Resize(arguments.int("width"), arguments.int("height"), anchor, resample))
        (arguments["dpi"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let(canvas::setDpi)
        null
    }
    register(
        CanvasSizeCommandIds.ROTATE,
        R.string.canvas_rotate_right,
        listOf(ParameterSpec("quarterTurns", ParameterType.Integer, minimum = -3.0, maximum = 3.0)),
    ) { arguments ->
        canvas.canvasOperation(CanvasOperation.Rotate(arguments.int("quarterTurns")))
        null
    }
    register(CanvasSizeCommandIds.FLIP_HORIZONTAL, R.string.canvas_flip_horizontal) { _ ->
        canvas.canvasOperation(CanvasOperation.Flip(horizontal = true))
        null
    }
    register(CanvasSizeCommandIds.FLIP_VERTICAL, R.string.canvas_flip_vertical) { _ ->
        canvas.canvasOperation(CanvasOperation.Flip(horizontal = false))
        null
    }
    registry.register(
        CommandSpec(
            id = CanvasSizeCommandIds.COLOR_DEPTH,
            titleRes = R.string.canvas_color_depth,
            category = CommandCategory.Canvas,
            parameters =
                listOf(ParameterSpec("bitsPerChannel", ParameterType.Integer, minimum = 8.0, maximum = 16.0)),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val format =
            PixelFormat.fromBits(arguments.int("bitsPerChannel"))
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "bitsPerChannel is 8 or 16")
        if (canvas.artwork == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open artwork")
        val error = suspendCancellableCoroutine { continuation -> canvas.changePixelFormat(format) { continuation.resume(it) } }
        if (error != null) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, error)
        } else {
            CommandResult.Success(buildJsonObject { put("bitsPerChannel", format.bitsPerChannel) })
        }
    }
    register(CanvasSizeCommandIds.INFO, R.string.command_canvas_info, undoable = false) { _ ->
        val state = canvas.document!!
        buildJsonObject {
            put("width", state.widthPx)
            put("height", state.heightPx)
            canvas.artwork
                ?.info
                ?.dpi
                ?.let { put("dpi", it) }
            put("layerLimit", canvas.layerLimitFor(state.widthPx, state.heightPx))
            put("bitsPerChannel", state.pixelFormat.bitsPerChannel)
        }
    }
}
