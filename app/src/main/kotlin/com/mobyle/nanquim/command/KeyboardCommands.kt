package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.keyboard.KeyChord
import com.mobyle.nanquim.keyboard.KeyboardShortcutSettings
import com.mobyle.nanquim.keyboard.ShortcutAction
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType

object KeyboardCommandIds {
    const val OPEN = "settings.keyboardShortcuts.open"
    const val GET = "settings.keyboardShortcuts.get"
    const val SET = "settings.keyboardShortcuts.set"
    const val REMOVE = "settings.keyboardShortcuts.remove"
    const val RESET = "settings.keyboardShortcuts.reset"
}

fun registerKeyboardCommands(
    registry: CommandRegistry,
    settings: KeyboardShortcutSettings,
) {
    fun answer(extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()) =
        CommandResult.Success(JsonObject(mapOf("shortcuts" to settings.shortcuts.toJson()) + extra))

    fun chordOf(arguments: JsonObject): KeyChord? = (arguments["shortcut"] as? JsonPrimitive)?.content?.let(KeyChord::parse)

    fun badChord(arguments: JsonObject) =
        CommandResult.Failure(CommandErrorCode.InvalidArguments, "Not a shortcut: ${arguments["shortcut"]}")

    registry.register(
        CommandSpec(KeyboardCommandIds.GET, R.string.command_keyboard_shortcuts_get, CommandCategory.Settings, undoable = false),
    ) { _, _ -> answer() }
    registry.register(
        CommandSpec(
            KeyboardCommandIds.SET,
            R.string.command_keyboard_shortcuts_set,
            CommandCategory.Settings,
            listOf(
                ParameterSpec("shortcut", ParameterType.String),
                ParameterSpec("command", ParameterType.String),
                ParameterSpec("arguments", ParameterType.Object, JsonNull),
            ),
            undoable = false,
        ),
    ) { _, arguments ->
        val chord = chordOf(arguments) ?: return@register badChord(arguments)
        val commandId = (arguments["command"] as JsonPrimitive).content
        val spec =
            registry.find(commandId)
                ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown command $commandId")
        val replaced = settings.shortcuts.actionFor(chord)
        settings.update(
            settings.shortcuts.with(
                chord,
                ShortcutAction(
                    spec.id,
                    arguments["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                ),
            ),
        )
        answer(mapOf("replaced" to (replaced?.let { JsonPrimitive(it.commandId) } ?: JsonNull)))
    }
    registry.register(
        CommandSpec(
            KeyboardCommandIds.REMOVE,
            R.string.command_keyboard_shortcuts_remove,
            CommandCategory.Settings,
            listOf(ParameterSpec("shortcut", ParameterType.String)),
            undoable = false,
        ),
    ) { _, arguments ->
        val chord = chordOf(arguments) ?: return@register badChord(arguments)
        settings.update(settings.shortcuts.without(chord))
        answer()
    }
    registry.register(
        CommandSpec(KeyboardCommandIds.RESET, R.string.command_keyboard_shortcuts_reset, CommandCategory.Settings, undoable = false),
    ) { _, _ ->
        settings.reset()
        answer()
    }
}
