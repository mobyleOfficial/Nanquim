package com.mobyle.nanquim.brush

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.brush.BrushDocument

class BrushEditorSessionTest {
    private fun document(linked: Boolean) =
        BrushDocument.parse("""{ "format": 1, "id": "a", "name": "Ink", "taper": { "stylus": { "linkStartEnd": $linked } } }""")

    @Test
    fun `linked taper lengths move together`() {
        val value = JsonPrimitive(40.0)
        val linked = BrushEditorSession.linkedTaper(document(true).with("taper.stylus.start", value), "taper.stylus.start", value)
        assertEquals(40.0, linked.number("taper.stylus.end"))
        val unlinked = BrushEditorSession.linkedTaper(document(false).with("taper.stylus.end", value), "taper.stylus.end", value)
        assertNull(unlinked.number("taper.stylus.start"))
        val other = BrushEditorSession.linkedTaper(document(true), "taper.stylus.minSize", value)
        assertNull(other.number("taper.stylus.end"))
    }
}
