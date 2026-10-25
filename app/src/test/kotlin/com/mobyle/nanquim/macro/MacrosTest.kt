package com.mobyle.nanquim.macro

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MacrosTest {
    @TempDir
    lateinit var folder: File

    @Test
    fun recordsEditsButNotInterfaceOrSettings() {
        assertTrue(Macro.records("layer.new"))
        assertTrue(Macro.records("tool.paint.strokePath"))
        assertTrue(Macro.records("brush.select"))
        assertFalse(Macro.records("macro.run"))
        assertFalse(Macro.records("settings.gestures.set"))
        assertFalse(Macro.records("gallery.open"))
        assertFalse(Macro.records("canvas.info.get"))
        assertFalse(Macro.records("view.layersPanel"))
        assertFalse(Macro.records("quickMenu.show"))
    }

    @Test
    fun aRecordingBecomesAMacroThatIsKept() {
        val macros = Macros(folder)
        macros.record("layer.new", JsonObject(emptyMap()))
        assertEquals(0, macros.recordedSteps)
        macros.startRecording("Two layers")
        macros.record("layer.new", JsonObject(emptyMap()))
        macros.record("settings.theme", JsonObject(emptyMap()))
        macros.record("layer.setOpacity", JsonObject(mapOf("opacity" to JsonPrimitive(0.5))))
        assertEquals(2, macros.recordedSteps)
        val saved = macros.stopRecording()!!
        assertEquals(listOf("layer.new", "layer.setOpacity"), saved.steps.map { it.commandId })
        assertNull(macros.recordingName)

        val reread = Macros(folder)
        assertEquals(listOf(saved), reread.macros)
        assertTrue(reread.rename(saved.id, "Renamed"))
        assertEquals("Renamed", Macros(folder).find(saved.id)?.name)
        assertTrue(reread.delete(saved.id))
        assertEquals(emptyList<Macro>(), Macros(folder).macros)
    }

    @Test
    fun anEmptyRecordingSavesNothing() {
        val macros = Macros(folder)
        macros.startRecording("Nothing")
        assertNull(macros.stopRecording())
        assertEquals(emptyList<Macro>(), macros.macros)
    }
}
