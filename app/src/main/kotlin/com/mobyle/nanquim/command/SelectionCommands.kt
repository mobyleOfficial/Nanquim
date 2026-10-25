package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.selection.PathNode
import com.mobyle.nanquim.canvas.selection.SelectionGeometry
import com.mobyle.nanquim.canvas.selection.SelectionMode
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.session.SelectionCombine
import com.mobyle.nanquim.engine.Point

object SelectionCommandIds {
    const val SET_MODE = "selection.setMode"
    const val SET_COMBINE = "selection.setCombine"
    const val RECT = "selection.rect"
    const val ELLIPSE = "selection.ellipse"
    const val FREEHAND = "selection.freehand"
    const val FEATHER = "selection.feather"
    const val APPLY_PENDING = "selection.applyPending"
    const val DISCARD_PENDING = "selection.discardPending"
    const val AUTO = "selection.auto"
    const val FILL = "selection.fill"
}

object FillCommandIds {
    const val DROP = "tool.fill.drop"
    const val STOP_CONTINUE = "tool.fill.stopContinue"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

private val MODES =
    mapOf(
        "automatic" to SelectionMode.Automatic,
        "freehand" to SelectionMode.Freehand,
        "rectangle" to SelectionMode.Rectangle,
        "ellipse" to SelectionMode.Ellipse,
        "paint" to SelectionMode.Paint,
    )
private val COMBINES = SelectionCombine.entries.associateBy { it.name.lowercase() }

fun SelectionMode.commandName(): String = MODES.entries.first { it.value == this }.key

fun SelectionCombine.commandName(): String = name.lowercase()

fun registerSelectionCommands(
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
            category = CommandCategory.Selection,
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

    val combine = ParameterSpec("combine", ParameterType.Enumeration, allowedValues = COMBINES.keys.toList(), defaultValue = JsonNull)

    fun JsonObject.combine(): SelectionCombine =
        (get("combine") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(COMBINES::getValue) ?: canvas.selectionCombine

    register(
        SelectionCommandIds.SET_MODE,
        R.string.command_selection_set_mode,
        parameters = listOf(ParameterSpec("mode", ParameterType.Enumeration, allowedValues = MODES.keys.toList())),
        undoable = false,
    ) { arguments ->
        canvas.changeSelectionMode(MODES.getValue(arguments.getValue("mode").jsonPrimitive.content))
        null
    }
    register(
        SelectionCommandIds.SET_COMBINE,
        R.string.command_selection_set_combine,
        parameters = listOf(ParameterSpec("combine", ParameterType.Enumeration, allowedValues = COMBINES.keys.toList())),
        undoable = false,
    ) { arguments ->
        canvas.selectionCombine = COMBINES.getValue(arguments.getValue("combine").jsonPrimitive.content)
        null
    }
    val box = listOf("x", "y", "width", "height").map { ParameterSpec(it, ParameterType.Number) } + combine
    for ((id, titleRes, ellipse) in listOf(
        Triple(SelectionCommandIds.RECT, R.string.command_selection_rect, false),
        Triple(SelectionCommandIds.ELLIPSE, R.string.command_selection_ellipse, true),
    )) {
        register(id, titleRes, parameters = box) { arguments ->
            val x = arguments.getValue("x").jsonPrimitive.double
            val y = arguments.getValue("y").jsonPrimitive.double
            val right = x + arguments.getValue("width").jsonPrimitive.double
            val bottom = y + arguments.getValue("height").jsonPrimitive.double
            val polygon = if (ellipse) SelectionGeometry.ellipse(x, y, right, bottom) else SelectionGeometry.rectangle(x, y, right, bottom)
            canvas.applyShape(polygon, arguments.combine())
            null
        }
    }
    register(
        SelectionCommandIds.FREEHAND,
        R.string.command_selection_freehand,
        parameters =
            listOf(
                ParameterSpec("points", ParameterType.Array),
                ParameterSpec("polygon", ParameterType.Boolean, defaultValue = JsonPrimitive(false)),
                combine,
            ),
    ) { arguments ->
        val polygonOnly = (arguments["polygon"] as? JsonPrimitive)?.booleanOrNull == true
        val nodes =
            (arguments.getValue("points") as? JsonArray)?.map { element ->
                val values =
                    (element as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.doubleOrNull
                            ?: (it as? JsonPrimitive)?.booleanOrNull?.let { flag -> if (flag) 1.0 else 0.0 }
                    }
                if (values == null || values.size !in 2..3 ||
                    values.any { it == null }
                ) {
                    throw LayerOperationException("points must be [[x, y], …]")
                }
                PathNode(Point(values[0]!!, values[1]!!), corner = polygonOnly || values.getOrNull(2) == 1.0)
            } ?: throw LayerOperationException("points must be an array")
        val polygon = SelectionGeometry.closedPath(nodes)
        if (polygon.size < 3) throw LayerOperationException("A shape needs at least 3 points")
        canvas.applyShape(polygon, arguments.combine())
        null
    }
    register(
        SelectionCommandIds.FEATHER,
        R.string.command_selection_feather,
        parameters = listOf(ParameterSpec("radius", ParameterType.Number, minimum = 0.0, maximum = 100.0)),
    ) { arguments ->
        canvas.documentSession?.featherSelection(
            arguments
                .getValue("radius")
                .jsonPrimitive.double
                .toFloat(),
        )
        null
    }
    val point = listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number))
    val threshold = ParameterSpec("threshold", ParameterType.Number, minimum = 0.0, maximum = 1.0, defaultValue = JsonNull)

    fun JsonObject.threshold(): Double = (get("threshold") as? JsonPrimitive)?.doubleOrNull ?: canvas.fillThreshold.toDouble()
    register(SelectionCommandIds.AUTO, R.string.command_selection_auto, parameters = point + threshold + combine) { arguments ->
        val session = canvas.documentSession!!
        session.beginAutomaticSelection(
            arguments.getValue("x").jsonPrimitive.double,
            arguments.getValue("y").jsonPrimitive.double,
            arguments.threshold(),
            arguments.combine(),
        )
        session.endFill(commit = true)
        null
    }
    register(SelectionCommandIds.FILL, R.string.command_selection_fill) { _ ->
        val color = canvas.primaryColor
        canvas.documentSession!!.fill(RgbaColor(color.red, color.green, color.blue))
        canvas.recordColorUse()
        null
    }
    register(
        FillCommandIds.DROP,
        R.string.command_fill_drop,
        parameters = point + threshold + ParameterSpec("whole", ParameterType.Boolean, defaultValue = JsonPrimitive(false)),
    ) { arguments ->
        val session = canvas.documentSession!!
        val color = canvas.primaryColor
        val whole = (arguments["whole"] as? JsonPrimitive)?.booleanOrNull == true
        session.beginFill(
            arguments.getValue("x").jsonPrimitive.double,
            arguments.getValue("y").jsonPrimitive.double,
            arguments.threshold(),
            RgbaColor(color.red, color.green, color.blue),
            whole,
        )
        session.endFill(commit = true)
        canvas.recordColorUse()
        null
    }
    register(FillCommandIds.STOP_CONTINUE, R.string.command_fill_stop_continue, undoable = false) { _ ->
        canvas.stopContinueFilling()
        null
    }
    register(SelectionCommandIds.APPLY_PENDING, R.string.command_selection_apply_pending) { _ ->
        canvas.applyPendingSelection()
        null
    }
    register(SelectionCommandIds.DISCARD_PENDING, R.string.command_selection_discard_pending, undoable = false) { _ ->
        canvas.discardPendingSelection()
        null
    }
}
