package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.LayerOperationException

object TextCommandIds {
    const val PLACE = "text.place"
    const val EDIT = "text.edit"
    const val INSERT = "text.insert"
    const val SELECT = "text.select"
    const val DELETE_BACKWARD = "text.deleteBackward"
    const val SET_STYLE = "text.setStyle"
    const val SET_PARAGRAPH = "text.setParagraph"
    const val SET_BOX = "text.setBox"
    const val DONE = "text.done"
    const val RASTERIZE = "text.rasterize"
}

fun registerTextCommands(
    registry: CommandRegistry,
    session: CanvasSession,
) {
    val editor = session.textEditor
    val editing: (com.mobyle.nanquim.document.command.AppState) -> Availability = {
        if (editor.editing != null) Availability.Enabled else Availability.Disabled("No text is being edited")
    }
    val document: (com.mobyle.nanquim.document.command.AppState) -> Availability = { state ->
        if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
    }

    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        availability: (com.mobyle.nanquim.document.command.AppState) -> Availability = editing,
        undoable: Boolean = false,
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = CommandCategory.Text,
            parameters = parameters,
            undoable = undoable,
            availability = availability,
        ),
    ) { _, arguments -> handler(arguments) }

    fun optional(
        name: String,
        type: ParameterType,
    ) = ParameterSpec(name, type, JsonNull)

    register(
        TextCommandIds.PLACE,
        R.string.command_text_place,
        listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number)),
        document,
    ) { arguments ->
        val color = session.primaryColor
        editor.place(
            arguments.getValue("x").jsonPrimitive.doubleOrNull ?: 0.0,
            arguments.getValue("y").jsonPrimitive.doubleOrNull ?: 0.0,
            com.mobyle.nanquim.document.layer
                .RgbaColor(color.red, color.green, color.blue, 1f),
        )
        CommandResult.Success()
    }
    register(
        TextCommandIds.EDIT,
        R.string.command_text_edit,
        listOf(ParameterSpec("layerId", ParameterType.Integer)),
        document,
    ) { arguments ->
        val id = arguments.getValue("layerId").jsonPrimitive.intOrNull ?: 0
        if (session.documentSession
                ?.state
                ?.raster(id)
                ?.text == null
        ) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Layer $id is not a text layer")
        }
        session.canvasTool = com.mobyle.nanquim.canvas.input.CanvasTool.Text
        editor.edit(id)
        CommandResult.Success()
    }
    register(TextCommandIds.INSERT, R.string.command_text_insert, listOf(ParameterSpec("text", ParameterType.String)), undoable = true) {
        editor.insert(it.getValue("text").jsonPrimitive.content)
        CommandResult.Success(buildJsonObject { put("layerId", editor.editing?.layerId?.let(::JsonPrimitive) ?: JsonNull) })
    }
    register(
        TextCommandIds.SELECT,
        R.string.command_text_select,
        listOf(ParameterSpec("start", ParameterType.Integer), ParameterSpec("end", ParameterType.Integer)),
    ) {
        editor.setSelection(it.getValue("start").jsonPrimitive.intOrNull ?: 0, it.getValue("end").jsonPrimitive.intOrNull ?: 0)
        CommandResult.Success()
    }
    register(TextCommandIds.DELETE_BACKWARD, R.string.command_text_delete_backward, undoable = true) {
        editor.deleteBackward()
        CommandResult.Success()
    }
    register(
        TextCommandIds.SET_STYLE,
        R.string.command_text_set_style,
        listOf(
            optional("fontId", ParameterType.String),
            optional("sizePt", ParameterType.Number),
            optional("color", ParameterType.Json),
            optional("tracking", ParameterType.Number),
            optional("kerning", ParameterType.Boolean),
            optional("baselineShift", ParameterType.Number),
            optional("underline", ParameterType.Boolean),
            optional("strikethrough", ParameterType.Boolean),
            optional("allCaps", ParameterType.Boolean),
            optional("outlineWidth", ParameterType.Number),
            optional("outlineColor", ParameterType.Json),
        ),
        undoable = true,
    ) { arguments ->
        val changes = mutableMapOf<String, JsonElement>()
        for (key in listOf("fontId", "sizePt", "tracking", "baselineShift", "underline", "strikethrough", "allCaps", "color")) {
            arguments[key]?.takeIf { it != JsonNull }?.let { changes[key] = it }
        }
        arguments["kerning"]?.takeIf { it != JsonNull }?.let {
            changes["kerning"] =
                JsonPrimitive(if (it.jsonPrimitive.booleanOrNull != false) "auto" else "none")
        }
        val outlineWidth = arguments["outlineWidth"]?.takeIf { it != JsonNull }
        val outlineColor = arguments["outlineColor"]?.takeIf { it != JsonNull }
        if (outlineWidth != null || outlineColor != null) {
            val current = editor.currentStyle()?.get("outline") as? JsonObject ?: JsonObject(emptyMap())
            changes["outline"] =
                JsonObject(current + listOfNotNull(outlineWidth?.let { "width" to it }, outlineColor?.let { "color" to it }))
        }
        if (changes["color"] != null && (changes["color"] as? JsonArray)?.size !in 3..4) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "color is [r, g, b(, a)]")
        }
        editor.applyStyle(JsonObject(changes))
        CommandResult.Success()
    }
    register(
        TextCommandIds.SET_PARAGRAPH,
        R.string.command_text_set_paragraph,
        listOf(
            optional("alignment", ParameterType.String),
            optional("lineHeight", ParameterType.Number),
            optional("vertical", ParameterType.Boolean),
            optional("direction", ParameterType.String),
        ),
        undoable = true,
    ) { arguments ->
        val changes =
            listOf("alignment", "lineHeight", "vertical", "direction").mapNotNull { key ->
                arguments[key]?.takeIf { it != JsonNull }?.let {
                    key to
                        it
                }
            }
        val alignment = (changes.toMap()["alignment"] as? JsonPrimitive)?.contentOrNull
        if (alignment != null && alignment !in listOf("left", "center", "right")) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "alignment is left, center or right")
        }
        editor.setParagraph(JsonObject(changes.toMap()))
        CommandResult.Success()
    }
    register(
        TextCommandIds.SET_BOX,
        R.string.command_text_set_box,
        listOf(
            ParameterSpec("mode", ParameterType.String),
            optional("width", ParameterType.Number),
            optional("height", ParameterType.Number),
        ),
        undoable = true,
    ) { arguments ->
        val mode = arguments.getValue("mode").jsonPrimitive.content
        if (mode !in
            listOf("auto", "fixed")
        ) {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "mode is auto or fixed")
        }
        val changes = mutableMapOf<String, JsonElement>("mode" to JsonPrimitive(mode))
        arguments["width"]?.takeIf { it != JsonNull }?.let { changes["width"] = it }
        arguments["height"]?.takeIf { it != JsonNull }?.let { changes["height"] = it }
        editor.setBox(JsonObject(changes))
        CommandResult.Success()
    }
    register(TextCommandIds.DONE, R.string.command_text_done) {
        editor.finish()
        CommandResult.Success()
    }
    register(
        TextCommandIds.RASTERIZE,
        R.string.command_text_rasterize,
        listOf(ParameterSpec("layerId", ParameterType.Integer)),
        document,
        undoable = true,
    ) {
        val id = it.getValue("layerId").jsonPrimitive.intOrNull ?: 0
        if (editor.editing?.layerId == id) editor.finish()
        try {
            session.documentSession?.rasterizeText(id)
            CommandResult.Success()
        } catch (exception: LayerOperationException) {
            CommandResult.Failure(CommandErrorCode.InvalidArguments, exception.message ?: "Cannot rasterize")
        }
    }
}
