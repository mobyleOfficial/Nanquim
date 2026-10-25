package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.canvas.transform.TransformEditor
import com.mobyle.nanquim.canvas.transform.TransformGeometry
import com.mobyle.nanquim.canvas.transform.TransformMode
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.TransformFilter
import java.util.Locale

object TransformCommandIds {
    const val BEGIN = "transform.begin"
    const val SET_MODE = "transform.setMode"
    const val SET_MATRIX = "transform.setMatrix"
    const val WARP_MESH = "transform.warpMesh"
    const val FLIP_HORIZONTAL = "transform.flipHorizontal"
    const val FLIP_VERTICAL = "transform.flipVertical"
    const val ROTATE_45 = "transform.rotate45"
    const val FIT = "transform.fit"
    const val RESET = "transform.reset"
    const val SET_FILTER = "transform.setFilter"
    const val SET_MAGNETICS = "transform.setMagnetics"
    const val SET_FITTED = "transform.setFitted"
    const val SET_WARP_SIZE = "transform.setWarpSize"
    const val SET_NUMERIC = "transform.setNumeric"
    const val COMMIT = "transform.commit"
    const val CANCEL = "transform.cancel"
    const val CLONE_SOURCE = "tool.clone.setSource"
    const val CLONE_OPTIONS = "tool.clone.setOptions"
}

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

fun registerTransformCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        action: (TransformEditor, JsonObject) -> Unit,
    ) = registry.register(
        CommandSpec(
            id = id,
            titleRes = titleRes,
            category = CommandCategory.Transform,
            parameters = parameters,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        val editor =
            canvas.transformEditor?.takeIf { canvas.transformReady }
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No transform in progress (or still lifting)")
        action(editor, arguments)
        canvas.transformChanged()
        CommandResult.Success()
    }

    registry.register(
        CommandSpec(
            id = TransformCommandIds.CLONE_SOURCE,
            titleRes = R.string.command_clone_set_source,
            category = CommandCategory.Brush,
            parameters = listOf(ParameterSpec("x", ParameterType.Number), ParameterSpec("y", ParameterType.Number)),
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        canvas.setCloneSource(Point(arguments.getValue("x").jsonPrimitive.double, arguments.getValue("y").jsonPrimitive.double))
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TransformCommandIds.CLONE_OPTIONS,
            titleRes = R.string.command_clone_set_options,
            category = CommandCategory.Brush,
            parameters =
                listOf(
                    ParameterSpec(
                        "source",
                        ParameterType.Enumeration,
                        allowedValues = listOf("composite", "layer"),
                        defaultValue = kotlinx.serialization.json.JsonNull,
                    ),
                    ParameterSpec("aligned", ParameterType.Boolean, defaultValue = kotlinx.serialization.json.JsonNull),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        val source = (arguments["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val aligned = (arguments["aligned"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
        canvas.setCloneOptions(
            aligned = aligned ?: canvas.cloneAligned,
            fromComposite =
                source?.let { it == "composite" } ?: canvas.cloneFromComposite,
        )
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TransformCommandIds.BEGIN,
            titleRes = R.string.command_transform_begin,
            category = CommandCategory.Transform,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        canvas.canvasTool = CanvasTool.Transform
        if (canvas.canvasTool == CanvasTool.Transform) {
            CommandResult.Success()
        } else {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "The active layer cannot be transformed")
        }
    }
    register(
        TransformCommandIds.SET_MODE,
        R.string.command_transform_set_mode,
        listOf(
            ParameterSpec("mode", ParameterType.Enumeration, allowedValues = TransformMode.entries.map { it.name.lowercase(Locale.ROOT) }),
        ),
    ) { editor, arguments ->
        val mode = TransformMode.entries.first { it.name.lowercase(Locale.ROOT) == arguments.getValue("mode").jsonPrimitive.content }
        val textLayer = canvas.activeTextLayerId
        if (textLayer != null && (mode == TransformMode.Distort || mode == TransformMode.Warp)) {
            canvas.askToRasterize(textLayer) {
                editor.setMode(mode)
                canvas.transformChanged()
            }
        } else {
            editor.setMode(mode)
        }
    }
    register(
        TransformCommandIds.SET_MATRIX,
        R.string.command_transform_set_matrix,
        listOf(ParameterSpec("matrix", ParameterType.Array)),
    ) { editor, arguments ->
        val matrix = (arguments.getValue("matrix") as JsonArray).map { (it as JsonPrimitive).double }
        require(matrix.size == 9) { "matrix has 9 values" }
        val state = editor.state ?: return@register
        val apply = {
            if (editor.mode == TransformMode.Warp) editor.setMode(TransformMode.Distort)
            if (editor.mode != TransformMode.Distort) editor.setMode(TransformMode.Distort)
            val target = state.sourceQuad.map { TransformGeometry.project(matrix.toDoubleArray(), it).first }
            editor.setNumericQuad(target)
        }
        val textLayer = canvas.activeTextLayerId
        if (textLayer != null && (matrix[6] != 0.0 || matrix[7] != 0.0)) {
            canvas.askToRasterize(textLayer) {
                apply()
                canvas.transformChanged()
            }
        } else {
            apply()
        }
    }
    register(
        TransformCommandIds.WARP_MESH,
        R.string.command_transform_warp_mesh,
        listOf(ParameterSpec("points", ParameterType.Array)),
    ) { editor, arguments ->
        val points =
            (arguments.getValue("points") as JsonArray).map { element ->
                val values = (element as JsonArray).map { (it as JsonPrimitive).double }
                Point(values[0], values[1])
            }
        val textLayer = canvas.activeTextLayerId
        if (textLayer != null) {
            canvas.askToRasterize(textLayer) {
                editor.setMode(TransformMode.Warp)
                editor.setWarpGrid(points)
                canvas.transformChanged()
            }
        } else {
            editor.setMode(TransformMode.Warp)
            editor.setWarpGrid(points)
        }
    }
    register(TransformCommandIds.FLIP_HORIZONTAL, R.string.transform_flip_horizontal) { editor, _ -> editor.flipHorizontal() }
    register(TransformCommandIds.FLIP_VERTICAL, R.string.transform_flip_vertical) { editor, _ -> editor.flipVertical() }
    register(TransformCommandIds.ROTATE_45, R.string.transform_rotate_45) { editor, _ -> editor.rotate45() }
    register(TransformCommandIds.FIT, R.string.transform_fit) { editor, _ -> editor.fitToCanvas() }
    register(TransformCommandIds.RESET, R.string.transform_reset) { editor, _ -> editor.reset() }
    register(
        TransformCommandIds.SET_FILTER,
        R.string.command_transform_set_filter,
        listOf(
            ParameterSpec(
                "filter",
                ParameterType.Enumeration,
                allowedValues = TransformFilter.entries.map { it.name.lowercase(Locale.ROOT) },
            ),
        ),
    ) { editor, arguments ->
        editor.filter =
            TransformFilter.entries.first { it.name.lowercase(Locale.ROOT) == arguments.getValue("filter").jsonPrimitive.content }
    }
    register(
        TransformCommandIds.SET_MAGNETICS,
        R.string.transform_magnetics,
        listOf(ParameterSpec("enabled", ParameterType.Boolean)),
    ) { editor, arguments ->
        editor.magnetics = arguments.getValue("enabled").jsonPrimitive.boolean
    }
    register(
        TransformCommandIds.SET_FITTED,
        R.string.transform_fitted_box,
        listOf(ParameterSpec("enabled", ParameterType.Boolean)),
    ) { editor, arguments ->
        editor.setFitted(arguments.getValue("enabled").jsonPrimitive.boolean)
    }
    register(
        TransformCommandIds.SET_WARP_SIZE,
        R.string.command_transform_set_warp_size,
        listOf(ParameterSpec("size", ParameterType.Integer, minimum = 4.0, maximum = 8.0)),
    ) { editor, arguments ->
        editor.setWarpSize(arguments.getValue("size").jsonPrimitive.int)
    }
    register(
        TransformCommandIds.SET_NUMERIC,
        R.string.transform_numeric,
        listOf("x", "y", "width", "height", "rotation").map { ParameterSpec(it, ParameterType.Number) },
    ) { editor, arguments ->
        fun value(name: String) = (arguments.getValue(name) as JsonPrimitive).doubleOrNull ?: 0.0
        editor.setNumeric(value("x"), value("y"), value("width"), value("height"), value("rotation"))
    }
    registry.register(
        CommandSpec(
            id = TransformCommandIds.COMMIT,
            titleRes = R.string.transform_done,
            category = CommandCategory.Transform,
            undoable = true,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        canvas.canvasTool = CanvasTool.Brush
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TransformCommandIds.CANCEL,
            titleRes = R.string.transform_cancel,
            category = CommandCategory.Transform,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        canvas.finishTransform(commit = false)
        canvas.canvasTool = CanvasTool.Brush
        CommandResult.Success()
    }
}
