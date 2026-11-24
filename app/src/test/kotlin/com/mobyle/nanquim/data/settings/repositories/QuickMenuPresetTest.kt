package com.mobyle.nanquim.data.settings.repositories

import com.mobyle.nanquim.domain.settings.models.QuickMenuPreset
import com.mobyle.nanquim.domain.settings.models.QuickMenuSlot
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QuickMenuPresetTest {
    @Test
    fun presetsRoundTrip() {
        val presets =
            listOf(
                QuickMenuPreset.defaults("Quick menu"),
                QuickMenuPreset("Inking", List(QuickMenuPreset.SLOTS) { null })
                    .withSlot(2, QuickMenuSlot("brush.select", JsonObject(mapOf("brushId" to JsonPrimitive("b1"))), "Ink pen")),
            )
        assertEquals(presets, QuickMenuRepositoryImpl.decode(QuickMenuRepositoryImpl.encode(presets)))
    }

    @Test
    fun defaultsHoldTheSpecExamples() {
        val slots = QuickMenuPreset.defaults("Quick menu").slots
        assertEquals(
            listOf("layer.new", "canvas.flipHorizontal", "layer.clear", "layer.mergeDown", "color.eyedropper.arm", "tool.select"),
            slots.map { it?.commandId },
        )
    }

    @Test
    fun unreadableEntriesAreSkippedOrEmptied() {
        val decoded = QuickMenuRepositoryImpl.decode("""[{"slots":[]},{"name":"A","slots":[{"label":"x"},{"command":"layer.new"}]}]""")!!
        assertEquals(1, decoded.size)
        assertEquals("A", decoded[0].name)
        assertNull(decoded[0].slots[0])
        assertEquals("layer.new", decoded[0].slots[1]?.commandId)
        assertEquals(QuickMenuPreset.SLOTS, decoded[0].slots.size)
        assertNull(QuickMenuRepositoryImpl.decode("not json"))
    }
}
