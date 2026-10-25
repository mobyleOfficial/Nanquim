package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
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
import com.mobyle.nanquim.document.history.HistoryEntry
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.session.DocumentSession

object DocumentCommandIds {
    const val UNDO = "edit.undo"
    const val REDO = "edit.redo"
    const val LAYER_NEW = "layer.new"
    const val LAYER_DUPLICATE = "layer.duplicate"
    const val LAYER_DELETE = "layer.delete"
    const val LAYER_RENAME = "layer.rename"
    const val LAYER_SET_OPACITY = "layer.setOpacity"
    const val LAYER_SET_BLEND = "layer.setBlend"
    const val LAYER_SET_HIDDEN = "layer.setHidden"
    const val LAYER_LOCK = "layer.lock"
    const val LAYER_ALPHA_LOCK = "layer.alphaLock"
    const val LAYER_SET_ASSISTED = "layer.setAssisted"
    const val LAYER_SET_PRIVATE = "layer.setPrivate"
    const val LAYER_SELECT_AT = "layer.selectAt"
    const val LAYER_CLIPPING = "layer.clipping"
    const val LAYER_MASK_ADD = "layer.mask.add"
    const val LAYER_MASK_DELETE = "layer.mask.delete"
    const val LAYER_MASK_APPLY = "layer.mask.apply"
    const val LAYER_MASK_SET_ENABLED = "layer.mask.setEnabled"
    const val LAYER_MASK_SET_INVERTED = "layer.mask.setInverted"
    const val LAYER_MERGE_DOWN = "layer.mergeDown"
    const val LAYER_MERGE = "layer.merge"
    const val LAYER_COMBINE_DOWN = "layer.combineDown"
    const val LAYER_GROUP = "layer.group"
    const val LAYER_UNGROUP = "layer.ungroup"
    const val LAYER_FLATTEN = "layer.flatten"
    const val LAYER_FILL = "layer.fill"
    const val LAYER_CLEAR = "layer.clear"
    const val LAYER_INVERT = "layer.invert"
    const val LAYER_SELECT = "layer.select"
    const val LAYER_TOGGLE_SELECTED = "layer.toggleSelected"
    const val LAYER_SET_REFERENCE = "layer.setReference"
    const val LAYER_SOLO = "layer.solo"
    const val LAYER_MOVE = "layer.move"
    const val LAYER_SET_COLLAPSED = "layer.setCollapsed"
    const val EDIT_CUT = "edit.cut"
    const val EDIT_COPY = "edit.copy"
    const val EDIT_COPY_MERGED = "edit.copyMerged"
    const val EDIT_PASTE = "edit.paste"
    const val SELECTION_COPY_PASTE = "selection.copyPaste"
    const val SELECTION_ALL = "selection.all"
    const val SELECTION_CLEAR = "selection.clear"
    const val SELECTION_INVERT = "selection.invert"
    const val SELECTION_CONTENTS = "selection.contents"
    const val SELECTION_SAVE = "selection.save"
    const val SELECTION_LOAD = "selection.load"
    const val SELECTION_DELETE_SAVED = "selection.deleteSaved"
    const val CANVAS_SET_BACKGROUND = "canvas.setBackground"
    const val LAYERS_PANEL = "view.layersPanel"
    const val EYEDROPPER = "color.eyedropper"
    const val EYEDROPPER_ARM = "color.eyedropper.arm"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

private fun JsonObject.optionalInt(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

private fun JsonObject.boolean(name: String) = getValue(name).jsonPrimitive.boolean

private fun JsonObject.float(name: String) = getValue(name).jsonPrimitive.double.toFloat()

private fun JsonObject.ids(name: String): List<Int>? = (get(name) as? JsonArray)?.map { it.jsonPrimitive.int }

private fun HistoryEntry.toResult(): JsonObject = buildJsonObject { put("label", label.name) }

fun registerDocumentCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    fun register(
        id: String,
        titleRes: Int,
        category: CommandCategory = CommandCategory.Layer,
        parameters: List<ParameterSpec> = emptyList(),
        undoable: Boolean = true,
        action: (DocumentSession, JsonObject) -> JsonObject?,
    ) = registry.register(
        CommandSpec(id = id, titleRes = titleRes, category = category, parameters = parameters, undoable = undoable, availability = requiresDocument),
    ) { _, arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No open document")
        try {
            CommandResult.Success(action(session, arguments) ?: JsonObject(emptyMap()))
        } catch (exception: LayerOperationException) {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, exception.message.orEmpty())
        }
    }

    val layerId = ParameterSpec("layerId", ParameterType.Integer, minimum = 1.0)
    val optionalLayerId = ParameterSpec("layerId", ParameterType.Integer, defaultValue = JsonNull, minimum = 1.0)
    val layerIds = ParameterSpec("layerIds", ParameterType.Array, defaultValue = JsonNull)

    fun DocumentSession.target(arguments: JsonObject) = arguments.optionalInt("layerId") ?: state.activeLayerId ?: throw LayerOperationException("No active layer")

    fun DocumentSession.targets(arguments: JsonObject) = arguments.ids("layerIds") ?: selectedIds.toList()

    fun id(value: Int) = buildJsonObject { put("layerId", value) }

    register(DocumentCommandIds.UNDO, R.string.command_edit_undo, CommandCategory.Canvas, undoable = false) { _, _ ->
        canvas.undo()?.toResult()
    }
    register(DocumentCommandIds.REDO, R.string.command_edit_redo, CommandCategory.Canvas, undoable = false) { _, _ ->
        canvas.redo()?.toResult()
    }
    register(
        DocumentCommandIds.LAYERS_PANEL,
        R.string.command_view_layers_panel,
        CommandCategory.View,
        parameters = listOf(ParameterSpec("visible", ParameterType.Boolean, defaultValue = JsonNull)),
        undoable = false,
    ) { _, arguments ->
        val visible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: !canvas.layersPanelVisible
        canvas.showLayersPanel(visible)
        null
    }
    register(
        DocumentCommandIds.EYEDROPPER_ARM,
        R.string.command_color_eyedropper_arm,
        CommandCategory.Color,
        parameters = listOf(ParameterSpec("armed", ParameterType.Boolean, defaultValue = JsonNull)),
        undoable = false,
    ) { _, arguments ->
        canvas.armEyedropper((arguments["armed"] as? JsonPrimitive)?.booleanOrNull ?: !canvas.eyedropperArmed)
        null
    }
    registry.register(
        CommandSpec(
            id = DocumentCommandIds.EYEDROPPER,
            titleRes = R.string.command_color_eyedropper,
            category = CommandCategory.Color,
            parameters =
                listOf(
                    ParameterSpec("x", ParameterType.Number),
                    ParameterSpec("y", ParameterType.Number),
                    ParameterSpec("sampleSize", ParameterType.Integer, defaultValue = JsonPrimitive(1), minimum = 1.0, maximum = 3.0),
                ),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val done = CompletableDeferred<com.mobyle.nanquim.canvas.SrgbColor?>()
        canvas.pickColor(
            arguments.getValue("x").jsonPrimitive.double,
            arguments.getValue("y").jsonPrimitive.double,
            arguments.int("sampleSize"),
        ) { done.complete(it) }
        val color = done.await() ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "The point is outside the canvas")
        CommandResult.Success(
            buildJsonObject {
                put("red", color.red)
                put("green", color.green)
                put("blue", color.blue)
            },
        )
    }
    register(DocumentCommandIds.LAYER_NEW, R.string.command_layer_new) { session, _ -> id(session.newLayer()) }
    register(DocumentCommandIds.LAYER_DUPLICATE, R.string.command_layer_duplicate, parameters = listOf(optionalLayerId)) { session, arguments ->
        id(session.duplicate(session.target(arguments)))
    }
    register(DocumentCommandIds.LAYER_DELETE, R.string.command_layer_delete, parameters = listOf(layerIds)) { session, arguments ->
        session.delete(session.targets(arguments))
        null
    }
    register(
        DocumentCommandIds.LAYER_RENAME,
        R.string.command_layer_rename,
        parameters = listOf(layerId, ParameterSpec("name", ParameterType.String)),
    ) { session, arguments ->
        session.rename(arguments.int("layerId"), arguments.getValue("name").jsonPrimitive.content)
        null
    }
    register(
        DocumentCommandIds.LAYER_SET_OPACITY,
        R.string.command_layer_set_opacity,
        parameters =
            listOf(
                layerId,
                ParameterSpec("opacity", ParameterType.Number, minimum = 0.0, maximum = 1.0),
                ParameterSpec("final", ParameterType.Boolean, defaultValue = JsonPrimitive(true)),
            ),
    ) { session, arguments ->
        session.setOpacity(arguments.int("layerId"), arguments.float("opacity"), arguments.boolean("final"))
        null
    }
    register(
        DocumentCommandIds.LAYER_SET_BLEND,
        R.string.command_layer_set_blend,
        parameters = listOf(layerId, ParameterSpec("blend", ParameterType.Enumeration, allowedValues = BlendMode.GROUP_MODES.map { it.id })),
    ) { session, arguments ->
        session.setBlend(arguments.int("layerId"), BlendMode.fromId(arguments.getValue("blend").jsonPrimitive.content)!!)
        null
    }

    fun registerFlag(
        id: String,
        titleRes: Int,
        name: String,
        apply: DocumentSession.(Int, Boolean) -> Unit,
    ) = register(id, titleRes, parameters = listOf(layerId, ParameterSpec(name, ParameterType.Boolean))) { session, arguments ->
        session.apply(arguments.int("layerId"), arguments.boolean(name))
        null
    }
    registerFlag(DocumentCommandIds.LAYER_SET_HIDDEN, R.string.command_layer_set_hidden, "hidden") { layer, value -> setHidden(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_LOCK, R.string.command_layer_lock, "locked") { layer, value -> setLocked(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_ALPHA_LOCK, R.string.command_layer_alpha_lock, "enabled") { layer, value -> setAlphaLock(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_SET_ASSISTED, R.string.command_layer_set_assisted, "enabled") { layer, value -> setAssisted(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_SET_PRIVATE, R.string.command_layer_set_private, "enabled") { layer, value -> setPrivate(layer, value) }
    registry.register(
        CommandSpec(
            id = DocumentCommandIds.LAYER_SELECT_AT,
            titleRes = R.string.command_layer_select_at,
            category = CommandCategory.Layer,
            parameters = listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number)),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val picked = kotlinx.coroutines.CompletableDeferred<Int?>()
        canvas.selectLayerAt(arguments.getValue("x").jsonPrimitive.double, arguments.getValue("y").jsonPrimitive.double) { picked.complete(it) }
        val id = picked.await()
        CommandResult.Success(JsonObject(mapOf("layerId" to (id?.let(::JsonPrimitive) ?: JsonNull))))
    }
    registerFlag(DocumentCommandIds.LAYER_CLIPPING, R.string.command_layer_clipping, "enabled") { layer, value -> setClipping(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_SET_REFERENCE, R.string.command_layer_set_reference, "enabled") { layer, value -> setReference(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_MASK_SET_ENABLED, R.string.command_layer_mask_set_enabled, "enabled") { layer, value -> setMaskEnabled(layer, value) }
    registerFlag(DocumentCommandIds.LAYER_MASK_SET_INVERTED, R.string.command_layer_mask_set_inverted, "inverted") { layer, value -> setMaskInverted(layer, value) }
    register(DocumentCommandIds.LAYER_SET_COLLAPSED, R.string.command_layer_set_collapsed, parameters = listOf(layerId, ParameterSpec("collapsed", ParameterType.Boolean)), undoable = false) { session, arguments ->
        session.setCollapsed(arguments.int("layerId"), arguments.boolean("collapsed"))
        null
    }

    register(DocumentCommandIds.LAYER_MASK_ADD, R.string.command_layer_mask_add, parameters = listOf(optionalLayerId)) { session, arguments ->
        buildJsonObject { put("maskId", session.addMask(session.target(arguments))) }
    }
    register(DocumentCommandIds.LAYER_MASK_DELETE, R.string.command_layer_mask_delete, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.deleteMask(session.target(arguments))
        null
    }
    register(DocumentCommandIds.LAYER_MASK_APPLY, R.string.command_layer_mask_apply, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.applyMask(session.target(arguments))
        null
    }
    register(DocumentCommandIds.LAYER_MERGE_DOWN, R.string.command_layer_merge_down, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.mergeDown(session.target(arguments))
        null
    }
    register(DocumentCommandIds.LAYER_MERGE, R.string.command_layer_merge, parameters = listOf(layerIds)) { session, arguments ->
        session.mergeLayers(session.targets(arguments))
        null
    }
    register(DocumentCommandIds.LAYER_COMBINE_DOWN, R.string.command_layer_combine_down, parameters = listOf(optionalLayerId)) { session, arguments ->
        id(session.combineDown(session.target(arguments)))
    }
    register(DocumentCommandIds.LAYER_GROUP, R.string.command_layer_group, parameters = listOf(layerIds)) { session, arguments ->
        id(session.group(session.targets(arguments)))
    }
    register(DocumentCommandIds.LAYER_UNGROUP, R.string.command_layer_ungroup, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.ungroup(session.target(arguments))
        null
    }
    register(
        DocumentCommandIds.LAYER_FLATTEN,
        R.string.command_layer_flatten,
        parameters = listOf(ParameterSpec("groupId", ParameterType.Integer, defaultValue = JsonNull, minimum = 1.0)),
    ) { session, arguments ->
        val groupId = arguments.optionalInt("groupId")
        if (groupId != null) session.flattenGroup(groupId) else session.flattenDocument()
        null
    }
    register(
        DocumentCommandIds.LAYER_FILL,
        R.string.command_layer_fill,
        parameters = listOf(optionalLayerId, ParameterSpec("color", ParameterType.Array, defaultValue = JsonNull)),
    ) { session, arguments ->
        val color =
            (arguments["color"] as? JsonArray)?.let { values ->
                val components = values.map { it.jsonPrimitive.double.toFloat().coerceIn(0f, 1f) }
                if (components.size !in 3..4) throw LayerOperationException("A color has 3 or 4 components")
                RgbaColor(components[0], components[1], components[2], components.getOrElse(3) { 1f })
            }
        session.fill(color ?: canvas.primaryColor.let { RgbaColor(it.red, it.green, it.blue) }, arguments.optionalInt("layerId"))
        if (color == null) canvas.recordColorUse()
        null
    }
    register(DocumentCommandIds.EDIT_COPY, R.string.command_edit_copy, undoable = false) { session, _ ->
        session.copy()
        null
    }
    register(DocumentCommandIds.EDIT_COPY_MERGED, R.string.command_edit_copy_merged, undoable = false) { session, _ ->
        session.copy(merged = true)
        null
    }
    register(DocumentCommandIds.EDIT_CUT, R.string.command_edit_cut) { session, _ ->
        session.cut()
        null
    }
    register(DocumentCommandIds.EDIT_PASTE, R.string.command_edit_paste) { session, _ ->
        buildJsonObject { put("layerId", session.paste()) }
    }
    register(DocumentCommandIds.SELECTION_COPY_PASTE, R.string.command_selection_copy_paste, CommandCategory.Selection) { session, _ ->
        buildJsonObject { put("layerId", session.copyToNewLayer()) }
    }
    register(DocumentCommandIds.SELECTION_ALL, R.string.command_selection_all, CommandCategory.Selection) { session, _ ->
        session.selectAll()
        null
    }
    register(DocumentCommandIds.SELECTION_CLEAR, R.string.command_selection_clear, CommandCategory.Selection) { session, _ ->
        session.deselect()
        null
    }
    register(DocumentCommandIds.SELECTION_INVERT, R.string.command_selection_invert, CommandCategory.Selection) { session, _ ->
        session.invertSelection()
        null
    }
    register(
        DocumentCommandIds.SELECTION_CONTENTS,
        R.string.command_selection_contents,
        CommandCategory.Selection,
        parameters = listOf(optionalLayerId),
    ) { session, arguments ->
        session.selectContents(arguments.optionalInt("layerId"))
        null
    }
    register(
        DocumentCommandIds.SELECTION_SAVE,
        R.string.command_selection_save,
        CommandCategory.Selection,
        parameters = listOf(ParameterSpec("name", ParameterType.String, defaultValue = JsonNull)),
    ) { session, arguments ->
        val id = session.saveSelection((arguments["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        buildJsonObject { put("selectionId", id) }
    }
    val savedSelectionId = ParameterSpec("selectionId", ParameterType.Integer)
    register(
        DocumentCommandIds.SELECTION_LOAD,
        R.string.command_selection_load,
        CommandCategory.Selection,
        parameters = listOf(savedSelectionId),
    ) { session, arguments ->
        session.loadSelection(arguments.int("selectionId"))
        null
    }
    register(
        DocumentCommandIds.SELECTION_DELETE_SAVED,
        R.string.command_selection_delete_saved,
        CommandCategory.Selection,
        parameters = listOf(savedSelectionId),
    ) { session, arguments ->
        session.deleteSavedSelection(arguments.int("selectionId"))
        null
    }
    register(DocumentCommandIds.LAYER_CLEAR, R.string.command_layer_clear, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.clear(arguments.optionalInt("layerId"))
        null
    }
    register(DocumentCommandIds.LAYER_INVERT, R.string.command_layer_invert, parameters = listOf(optionalLayerId)) { session, arguments ->
        session.invert(arguments.optionalInt("layerId"))
        null
    }
    register(
        DocumentCommandIds.LAYER_SELECT,
        R.string.command_layer_select,
        parameters = listOf(layerId, ParameterSpec("mask", ParameterType.Boolean, defaultValue = JsonPrimitive(false))),
        undoable = false,
    ) { session, arguments ->
        session.select(arguments.int("layerId"), arguments.boolean("mask"))
        null
    }
    register(DocumentCommandIds.LAYER_TOGGLE_SELECTED, R.string.command_layer_toggle_selected, parameters = listOf(layerId), undoable = false) { session, arguments ->
        session.toggleSelected(arguments.int("layerId"))
        null
    }
    register(DocumentCommandIds.LAYER_SOLO, R.string.command_layer_solo, parameters = listOf(optionalLayerId), undoable = false) { session, arguments ->
        session.setSolo(arguments.optionalInt("layerId"))
        null
    }
    register(
        DocumentCommandIds.LAYER_MOVE,
        R.string.command_layer_move,
        parameters =
            listOf(
                layerId,
                ParameterSpec("parentId", ParameterType.Integer, defaultValue = JsonNull, minimum = 1.0),
                ParameterSpec("index", ParameterType.Integer, minimum = 0.0),
            ),
    ) { session, arguments ->
        session.move(arguments.int("layerId"), arguments.optionalInt("parentId"), arguments.int("index"))
        null
    }
    register(
        DocumentCommandIds.CANVAS_SET_BACKGROUND,
        R.string.command_canvas_set_background,
        CommandCategory.Canvas,
        parameters =
            listOf(
                ParameterSpec("red", ParameterType.Number, minimum = 0.0, maximum = 1.0),
                ParameterSpec("green", ParameterType.Number, minimum = 0.0, maximum = 1.0),
                ParameterSpec("blue", ParameterType.Number, minimum = 0.0, maximum = 1.0),
                ParameterSpec("hidden", ParameterType.Boolean, defaultValue = JsonPrimitive(false)),
            ),
    ) { session, arguments ->
        val color = RgbaColor(arguments.float("red"), arguments.float("green"), arguments.float("blue"))
        session.setBackground(Background(color, arguments.boolean("hidden")))
        null
    }
}
