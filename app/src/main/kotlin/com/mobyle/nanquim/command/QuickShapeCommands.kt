package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.ShapeSnapResult
import com.mobyle.nanquim.canvas.input.PalmSupport
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.shape.RecognizedShape
import com.mobyle.nanquim.document.shape.ShapeHandle
import com.mobyle.nanquim.engine.Point

private val requiresDocument: (AppState) -> Availability = { state ->
    if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
}

object QuickShapeCommandIds {
    const val APPLY_TO_LAST_STROKE = "shapeSnap.applyToLastStroke"
    const val EDIT = "shapeSnap.edit"
    const val MOVE_HANDLE = "shapeSnap.moveHandle"
    const val END_EDIT = "shapeSnap.endEdit"
    const val SETTINGS = "settings.quickShape.set"
    const val PALM_SUPPORT = "settings.palmSupport.set"
    const val UNDO_GESTURE = "settings.undoGesture.set"
    const val FINGER_PAINTING = "settings.fingerPainting.set"
    const val HAPTICS = "settings.haptics.set"
}

fun registerQuickShapeCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
    inputSettings: InputSettings,
) {
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.APPLY_TO_LAST_STROKE,
            titleRes = R.string.command_shape_snap_apply,
            category = CommandCategory.Canvas,
            undoable = true,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        when (val result = canvas.applyShapeToLastStroke()) {
            is ShapeSnapResult.Applied -> CommandResult.Success(buildJsonObject { put("shape", result.shape.kind.label) })
            is ShapeSnapResult.Unavailable -> CommandResult.Failure(CommandErrorCode.Unavailable, result.reason)
        }
    }
    registerShapeEditCommands(registry, canvas)
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.HAPTICS,
            titleRes = R.string.command_settings_haptics,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        inputSettings.updateHaptics((arguments["enabled"] as? JsonPrimitive)?.booleanOrNull ?: inputSettings.hapticsEnabled)
        CommandResult.Success(buildJsonObject { put("enabled", inputSettings.hapticsEnabled) })
    }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.PALM_SUPPORT,
            titleRes = R.string.command_settings_palm_support,
            category = CommandCategory.Settings,
            parameters =
                listOf(ParameterSpec("level", ParameterType.Enumeration, allowedValues = PalmSupport.entries.map { it.id })),
            undoable = false,
        ),
    ) { _, arguments ->
        val level = PalmSupport.fromId((arguments["level"] as? JsonPrimitive)?.content) ?: PalmSupport.DEFAULT
        inputSettings.updatePalmSupport(level)
        CommandResult.Success(buildJsonObject { put("level", level.id) })
    }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.UNDO_GESTURE,
            titleRes = R.string.command_settings_undo_gesture,
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec(
                        "fingers",
                        ParameterType.Integer,
                        minimum = InputSettings.UNDO_TAP_FINGERS.first.toDouble(),
                        maximum = InputSettings.UNDO_TAP_FINGERS.last.toDouble(),
                    ),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        inputSettings.updateUndoTapFingers((arguments["fingers"] as? JsonPrimitive)?.intOrNull ?: InputSettings.DEFAULT_UNDO_TAP_FINGERS)
        CommandResult.Success(buildJsonObject { put("fingers", inputSettings.undoTapFingers) })
    }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.FINGER_PAINTING,
            titleRes = R.string.command_settings_finger_painting,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        inputSettings.updateFingerPainting((arguments["enabled"] as? JsonPrimitive)?.booleanOrNull ?: false)
        CommandResult.Success(buildJsonObject { put("enabled", inputSettings.fingerPainting) })
    }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.SETTINGS,
            titleRes = R.string.command_settings_quick_shape,
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec("enabled", ParameterType.Boolean, JsonNull),
                    ParameterSpec(
                        "delay",
                        ParameterType.Integer,
                        JsonNull,
                        InputSettings.QUICK_SHAPE_DELAYS.first.toDouble(),
                        InputSettings.QUICK_SHAPE_DELAYS.last.toDouble(),
                    ),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        inputSettings.updateQuickShape(
            enabled = (arguments["enabled"] as? JsonPrimitive)?.booleanOrNull ?: inputSettings.quickShapeEnabled,
            delayMillis = (arguments["delay"] as? JsonPrimitive)?.intOrNull ?: inputSettings.quickShapeDelayMillis,
        )
        CommandResult.Success(
            buildJsonObject {
                put("enabled", inputSettings.quickShapeEnabled)
                put("delay", inputSettings.quickShapeDelayMillis)
            },
        )
    }
}

private fun registerShapeEditCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    fun result(result: ShapeSnapResult): CommandResult =
        when (result) {
            is ShapeSnapResult.Applied -> CommandResult.Success(shapeEditJson(result.shape, canvas.shapeHandles()))
            is ShapeSnapResult.Unavailable -> CommandResult.Failure(CommandErrorCode.Unavailable, result.reason)
        }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.EDIT,
            titleRes = R.string.command_shape_snap_edit,
            category = CommandCategory.Canvas,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, _ -> result(canvas.beginShapeEdit()) }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.MOVE_HANDLE,
            titleRes = R.string.command_shape_snap_move_handle,
            category = CommandCategory.Canvas,
            parameters =
                listOf(
                    ParameterSpec("index", ParameterType.Integer, minimum = 0.0),
                    ParameterSpec("x", ParameterType.Number),
                    ParameterSpec("y", ParameterType.Number),
                ),
            undoable = true,
            availability = requiresDocument,
        ),
    ) { _, arguments ->
        fun number(name: String) = (arguments[name] as JsonPrimitive).double
        result(canvas.moveShapeHandle(number("index").toInt(), Point(number("x"), number("y"))))
    }
    registry.register(
        CommandSpec(
            id = QuickShapeCommandIds.END_EDIT,
            titleRes = R.string.command_shape_snap_end_edit,
            category = CommandCategory.Canvas,
            undoable = false,
            availability = requiresDocument,
        ),
    ) { _, _ ->
        canvas.endShapeEdit()
        CommandResult.Success(buildJsonObject { })
    }
}

private fun shapeEditJson(
    shape: RecognizedShape,
    handles: List<ShapeHandle>,
): JsonObject =
    buildJsonObject {
        put("shape", shape.kind.label)
        put(
            "handles",
            JsonArray(
                handles.map { handle ->
                    buildJsonObject {
                        put("x", handle.position.x)
                        put("y", handle.position.y)
                        put("kind", handle.kind.name.lowercase())
                    }
                },
            ),
        )
    }
