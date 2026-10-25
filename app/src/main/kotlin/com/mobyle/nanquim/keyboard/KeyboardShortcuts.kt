package com.mobyle.nanquim.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    val id: String
        get() =
            buildString {
                if (ctrl) append("Ctrl+")
                if (alt) append("Alt+")
                if (shift) append("Shift+")
                append(key)
            }

    val label: String get() = id.removeSuffix(key) + (KEY_SYMBOLS[key] ?: key.lowercase().replaceFirstChar { it.uppercase() })

    companion object {
        private val KEY_SYMBOLS =
            mapOf(
                "LEFT_BRACKET" to "[",
                "RIGHT_BRACKET" to "]",
                "SLASH" to "/",
                "SPACE" to "Space",
                "TAB" to "Tab",
                "MINUS" to "-",
                "EQUALS" to "=",
                "PLUS" to "+",
                "COMMA" to ",",
                "PERIOD" to ".",
            )

        fun parse(id: String): KeyChord? {
            val parts = id.split('+')
            val key = parts.last().takeIf { it.isNotEmpty() } ?: return null
            val modifiers = parts.dropLast(1).toSet()
            if (!MODIFIERS.containsAll(modifiers)) return null
            return KeyChord(key, ctrl = "Ctrl" in modifiers, shift = "Shift" in modifiers, alt = "Alt" in modifiers)
        }

        private val MODIFIERS = setOf("Ctrl", "Shift", "Alt")
    }
}

data class ShortcutAction(
    val commandId: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

data class KeyboardShortcuts(
    val bindings: Map<KeyChord, ShortcutAction>,
) {
    fun actionFor(chord: KeyChord): ShortcutAction? = bindings[chord]

    fun with(
        chord: KeyChord,
        action: ShortcutAction,
    ): KeyboardShortcuts = KeyboardShortcuts(bindings + (chord to action))

    fun without(chord: KeyChord): KeyboardShortcuts = KeyboardShortcuts(bindings - chord)

    fun chordsOf(action: ShortcutAction): List<KeyChord> = bindings.filterValues { it == action }.keys.sortedBy { it.id }

    fun toJson(): JsonArray =
        JsonArray(
            bindings.entries.sortedBy { it.key.id }.map { (chord, action) ->
                JsonObject(
                    mapOf(
                        "shortcut" to JsonPrimitive(chord.id),
                        "command" to JsonPrimitive(action.commandId),
                        "arguments" to action.arguments,
                    ),
                )
            },
        )

    companion object {
        private fun action(
            commandId: String,
            vararg arguments: Pair<String, Any>,
        ) = ShortcutAction(
            commandId,
            JsonObject(
                arguments.associate { (name, value) ->
                    name to
                        if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())
                },
            ),
        )

        val DEFAULTS =
            KeyboardShortcuts(
                mapOf(
                    KeyChord("Z", ctrl = true) to action("edit.undo"),
                    KeyChord("Z", ctrl = true, shift = true) to action("edit.redo"),
                    KeyChord("Y", ctrl = true) to action("edit.redo"),
                    KeyChord("B") to action("tool.select", "tool" to "brush"),
                    KeyChord("E") to action("tool.select", "tool" to "eraser"),
                    KeyChord("S") to action("tool.select", "tool" to "smudge"),
                    KeyChord("L") to action("view.layersPanel"),
                    KeyChord("C") to action("color.panel.toggle"),
                    KeyChord("LEFT_BRACKET") to action("brush.stepSize", "steps" to -1),
                    KeyChord("RIGHT_BRACKET") to action("brush.stepSize", "steps" to 1),
                    KeyChord("N", ctrl = true) to action("layer.new"),
                    KeyChord("S", ctrl = true) to action("document.save"),
                    KeyChord("E", ctrl = true) to action("view.exportSheet"),
                    KeyChord("0", ctrl = true) to action("view.fit"),
                    KeyChord("1", ctrl = true) to action("view.actualPixels"),
                    KeyChord("TAB") to action("view.fullScreen"),
                    KeyChord("SLASH", ctrl = true) to action("settings.keyboardShortcuts.open"),
                ),
            )

        val REPEATING = setOf("edit.undo", "edit.redo", "brush.stepSize")

        fun fromJson(text: String): KeyboardShortcuts? {
            val entries = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return null
            val bindings =
                entries.mapNotNull { entry ->
                    val json = entry as? JsonObject ?: return@mapNotNull null
                    val chord = (json["shortcut"] as? JsonPrimitive)?.contentOrNull?.let(KeyChord::parse) ?: return@mapNotNull null
                    val command = (json["command"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    chord to ShortcutAction(command, json["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
                }
            return KeyboardShortcuts(bindings.toMap())
        }
    }
}
