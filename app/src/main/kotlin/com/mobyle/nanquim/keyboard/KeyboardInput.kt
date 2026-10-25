package com.mobyle.nanquim.keyboard

import android.content.res.Resources
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.command.CommandRegistry

class KeyboardInput(
    private val session: CanvasSession,
    private val shortcuts: () -> KeyboardShortcuts,
    private val dispatch: (String, JsonObject) -> Unit,
) {
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (session.textEditor.editing != null) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_SPACE && !event.isCtrlPressed && !event.isAltPressed) {
            session.spacePanning = down
            return true
        }
        if (!down || KeyEvent.isModifierKey(event.keyCode)) return false
        val action = shortcuts().actionFor(chordOf(event)) ?: return false
        if (event.repeatCount > 0 && action.commandId !in KeyboardShortcuts.REPEATING) return true
        dispatch(action.commandId, action.arguments)
        return true
    }

    companion object {
        fun chordOf(event: KeyEvent): KeyChord =
            KeyChord(keyName(event.keyCode), ctrl = event.isCtrlPressed, shift = event.isShiftPressed, alt = event.isAltPressed)

        fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

        fun describe(
            action: ShortcutAction,
            resources: Resources,
            registry: CommandRegistry,
        ): String {
            val argument = { name: String -> (action.arguments[name] as? JsonPrimitive)?.content }
            return when (action.commandId) {
                "tool.select" ->
                    when (argument("tool")) {
                        "brush" -> resources.getString(R.string.tool_brush)
                        "eraser" -> resources.getString(R.string.tool_eraser)
                        "smudge" -> resources.getString(R.string.tool_smudge)
                        "selection" -> resources.getString(R.string.tool_selection)
                        "transform" -> resources.getString(R.string.tool_transform)
                        else -> null
                    }
                "brush.stepSize" ->
                    resources.getString(
                        if (((action.arguments["steps"] as? JsonPrimitive)?.intOrNull ?: 0) < 0) {
                            R.string.shortcut_brush_smaller
                        } else {
                            R.string.shortcut_brush_larger
                        },
                    )
                else -> null
            } ?: registry.find(action.commandId)?.let { resources.getString(it.titleRes) } ?: action.commandId
        }

        fun shortcutGroup(
            shortcuts: KeyboardShortcuts,
            resources: Resources,
            registry: CommandRegistry,
        ): KeyboardShortcutGroup {
            val items =
                shortcuts.bindings.entries
                    .sortedBy { it.key.id }
                    .mapNotNull { (chord, action) ->
                        val keyCode = KeyEvent.keyCodeFromString("KEYCODE_${chord.key}")
                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return@mapNotNull null
                        var modifiers = 0
                        if (chord.ctrl) modifiers = modifiers or KeyEvent.META_CTRL_ON
                        if (chord.shift) modifiers = modifiers or KeyEvent.META_SHIFT_ON
                        if (chord.alt) modifiers = modifiers or KeyEvent.META_ALT_ON
                        KeyboardShortcutInfo(describe(action, resources, registry), keyCode, modifiers)
                    }
            return KeyboardShortcutGroup(resources.getString(R.string.keyboard_shortcuts_title), items)
        }
    }
}
