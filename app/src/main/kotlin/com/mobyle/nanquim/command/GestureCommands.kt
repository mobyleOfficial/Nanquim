package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.Gesture
import com.mobyle.nanquim.canvas.input.GestureAction
import com.mobyle.nanquim.canvas.input.GestureConfiguration
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType

object GestureCommandIds {
    const val OPEN = "settings.gestures.open"
    const val GET = "settings.gestures.get"
    const val SET_TRIGGERS = "settings.gestures.setTriggers"
    const val SET = "settings.gestures.set"
    const val RESET = "settings.gestures.reset"
}

fun registerGestureCommands(
    registry: CommandRegistry,
    inputSettings: InputSettings,
) {
    fun answer() = CommandResult.Success(gesturesJson(inputSettings.gestures))
    registry.register(
        CommandSpec(
            id = GestureCommandIds.GET,
            titleRes = R.string.command_settings_gestures_get,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ -> answer() }
    registry.register(
        CommandSpec(
            id = GestureCommandIds.SET_TRIGGERS,
            titleRes = R.string.command_settings_gestures_set_triggers,
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec("action", ParameterType.Enumeration, allowedValues = GestureConfiguration.ACTIONS.map { it.id }),
                    ParameterSpec("triggers", ParameterType.Array),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        val action = GestureAction.fromId((arguments["action"] as JsonPrimitive).content)!!
        val ids = (arguments["triggers"] as JsonArray).map { (it as? JsonPrimitive)?.content }
        val triggers =
            ids.map { id ->
                Gesture.fromId(id)
                    ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown trigger $id")
            }
        triggers.firstOrNull { !GestureConfiguration.allows(action, it) }?.let {
            return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "${action.id} cannot use ${it.id}")
        }
        inputSettings.updateGestures(inputSettings.gestures.withTriggers(action, triggers.toSet()))
        answer()
    }
    val longPress = GestureConfiguration.LONG_PRESS_DELAYS
    val repeat = GestureConfiguration.REPEAT_DELAYS
    registry.register(
        CommandSpec(
            id = GestureCommandIds.SET,
            titleRes = R.string.command_settings_gestures_set,
            category = CommandCategory.Settings,
            parameters =
                listOf(
                    ParameterSpec("longPressDelay", ParameterType.Integer, JsonNull, longPress.first.toDouble(), longPress.last.toDouble()),
                    ParameterSpec("repeatDelay", ParameterType.Integer, JsonNull, repeat.first.toDouble(), repeat.last.toDouble()),
                    ParameterSpec("rotation", ParameterType.Boolean, JsonNull),
                    ParameterSpec("stylusDisablesFingerStrokes", ParameterType.Boolean, JsonNull),
                ),
            undoable = false,
        ),
    ) { _, arguments ->
        fun int(name: String) = (arguments[name] as? JsonPrimitive)?.intOrNull

        fun boolean(name: String) = (arguments[name] as? JsonPrimitive)?.booleanOrNull
        val current = inputSettings.gestures
        inputSettings.updateGestures(
            current.copy(
                longPressMillis = int("longPressDelay")?.coerceIn(longPress) ?: current.longPressMillis,
                repeatDelayMillis = int("repeatDelay")?.coerceIn(repeat) ?: current.repeatDelayMillis,
                rotation = boolean("rotation") ?: current.rotation,
                stylusDisablesFingerStrokes = boolean("stylusDisablesFingerStrokes") ?: current.stylusDisablesFingerStrokes,
            ),
        )
        answer()
    }
    registry.register(
        CommandSpec(
            id = GestureCommandIds.RESET,
            titleRes = R.string.command_settings_gestures_reset,
            category = CommandCategory.Settings,
            undoable = false,
        ),
    ) { _, _ ->
        inputSettings.updateGestures(GestureConfiguration.defaults())
        answer()
    }
}

private fun gesturesJson(configuration: GestureConfiguration): JsonObject =
    JsonObject(
        configuration.toJson() +
            (
                "conflicts" to
                    JsonArray(
                        configuration.conflicts.map { (gesture, actions) ->
                            JsonObject(
                                mapOf(
                                    "trigger" to JsonPrimitive(gesture.id),
                                    "actions" to JsonArray(actions.map { JsonPrimitive(it.id) }),
                                ),
                            )
                        },
                    )
            ),
    )
