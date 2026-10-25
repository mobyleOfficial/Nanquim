package com.mobyle.nanquim.keyboard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KeyboardShortcutsTest {
    @Test
    fun chordsReadAndWriteTheirIds() {
        val chord = KeyChord("Z", ctrl = true, shift = true)
        assertEquals("Ctrl+Shift+Z", chord.id)
        assertEquals(chord, KeyChord.parse("Ctrl+Shift+Z"))
        assertEquals("Ctrl+[", KeyChord("LEFT_BRACKET", ctrl = true).label)
        assertEquals("Tab", KeyChord("TAB").label)
        assertNull(KeyChord.parse("Hyper+Z"))
        assertNull(KeyChord.parse("Ctrl+"))
    }

    @Test
    fun defaultsFollowTheSpecTable() {
        val defaults = KeyboardShortcuts.DEFAULTS

        fun command(id: String) = defaults.actionFor(KeyChord.parse(id)!!)?.commandId
        assertEquals("edit.undo", command("Ctrl+Z"))
        assertEquals("edit.redo", command("Ctrl+Shift+Z"))
        assertEquals("edit.redo", command("Ctrl+Y"))
        assertEquals("tool.select", command("B"))
        assertEquals("view.layersPanel", command("L"))
        assertEquals("color.panel.toggle", command("C"))
        assertEquals("brush.stepSize", command("LEFT_BRACKET"))
        assertEquals("layer.new", command("Ctrl+N"))
        assertEquals("document.save", command("Ctrl+S"))
        assertEquals("view.exportSheet", command("Ctrl+E"))
        assertEquals("view.fit", command("Ctrl+0"))
        assertEquals("view.actualPixels", command("Ctrl+1"))
        assertEquals("view.fullScreen", command("TAB"))
        assertEquals("settings.keyboardShortcuts.open", command("Ctrl+SLASH"))
        assertEquals(
            JsonObject(mapOf("tool" to JsonPrimitive("eraser"))),
            defaults.actionFor(KeyChord("E"))?.arguments,
        )
    }

    @Test
    fun aTakenChordMovesToTheNewAction() {
        val fit = ShortcutAction("view.fit")
        val changed = KeyboardShortcuts.DEFAULTS.with(KeyChord("B"), fit)
        assertEquals(fit, changed.actionFor(KeyChord("B")))
        assertEquals(listOf("B", "Ctrl+0"), changed.chordsOf(fit).map { it.id })
        assertNull(changed.without(KeyChord("B")).actionFor(KeyChord("B")))
    }

    @Test
    fun jsonRoundTripAndForgivingReads() {
        val table = KeyboardShortcuts.DEFAULTS.with(KeyChord("F", alt = true), ShortcutAction("view.flipView"))
        assertEquals(table, KeyboardShortcuts.fromJson(table.toJson().toString()))
        val read =
            KeyboardShortcuts.fromJson(
                """[{"shortcut":"Meta+Q","command":"x"},{"shortcut":"Q"},{"shortcut":"Q","command":"layer.new"}]""",
            )!!
        assertEquals(mapOf(KeyChord("Q") to ShortcutAction("layer.new")), read.bindings)
        assertNull(KeyboardShortcuts.fromJson("{"))
    }
}
