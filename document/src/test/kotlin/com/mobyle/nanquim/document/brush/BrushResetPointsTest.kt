package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BrushResetPointsTest {
    private val original =
        BrushDocument.parse("""{ "format": 1, "id": "ink", "name": "Ink", "path": { "spacing": 0.1 } }""")

    @Test
    fun `a reset point restores the brush as it was`() {
        val saved = BrushResetPoints.create(original, "Before", "2026-10-16T10:00:00Z")
        assertEquals(listOf(BrushResetPoint("Before", "2026-10-16T10:00:00Z")), BrushResetPoints.list(saved))
        val edited = saved.with("path.spacing", 0.5).with("name", "Ink 2")
        val restored = BrushResetPoints.restoreLatest(edited)!!
        assertEquals(0.1, restored.number("path.spacing"))
        assertEquals("Ink", restored.name)
        assertEquals(1, BrushResetPoints.list(restored).size)
    }

    @Test
    fun `snapshots do not nest and keep the order`() {
        val first = BrushResetPoints.create(original, "A", "1")
        val second = BrushResetPoints.create(first.with("path.spacing", 0.3), "B", "2")
        assertEquals(listOf("A", "B"), BrushResetPoints.list(second).map { it.name })
        val latest = (second["resetPoints"] as JsonArray)[1] as JsonObject
        assertNull((latest["snapshot"] as JsonObject)["resetPoints"])
        assertEquals(0.1, BrushResetPoints.restore(second, 0)!!.number("path.spacing"))
        assertEquals(0.3, BrushResetPoints.restore(second, 1)!!.number("path.spacing"))
    }

    @Test
    fun `without reset points there is nothing to restore`() {
        assertNull(BrushResetPoints.restoreLatest(original))
        assertNull(BrushResetPoints.restore(original, 3))
    }
}
