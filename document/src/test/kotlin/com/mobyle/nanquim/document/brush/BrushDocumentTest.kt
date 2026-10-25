package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrushDocumentTest {
    private val text =
        """
        {
          "format": 1,
          "id": "b1",
          "name": "HB Pencil",
          "futureField": { "kept": [1, 2, 3] },
          "path": { "spacing": 0.08, "somethingNew": true },
          "grain": { "texture": "grain.png" }
        }
        """.trimIndent()

    @Test
    fun readsTheEnvelopeAndFieldsByPath() {
        val document = BrushDocument.parse(text)
        assertEquals("b1", document.id)
        assertEquals("HB Pencil", document.name)
        assertEquals(0.08, document.number("path.spacing"))
        assertEquals(true, document.boolean("path.somethingNew"))
        assertNull(document.number("path.streamline"))
        assertNull(document.number("name"))
        assertEquals("grain.png", document.grainTexture)
        assertEquals(BrushDocument.DEFAULT_SHAPE_TEXTURE, document.shapeTexture)
    }

    @Test
    fun editsKeepUnknownFields() {
        val edited =
            BrushDocument
                .parse(text)
                .with("path.spacing", 0.2)
                .with("taper.stylus.end", 40.0)
                .with("shape.texture", null as String?)
        val reparsed = BrushDocument.parse(edited.toJsonText())
        assertEquals(0.2, reparsed.number("path.spacing"))
        assertEquals(40.0, reparsed.number("taper.stylus.end"))
        assertEquals(true, reparsed.boolean("path.somethingNew"))
        assertEquals(JsonPrimitive(3), (reparsed["futureField"] as JsonObject)["kept"]?.let { (it as kotlinx.serialization.json.JsonArray)[2] })
        assertNull(reparsed.shapeTexture)
        assertTrue(reparsed.has("shape.texture"))
    }

    @Test
    fun withoutRestoresTheDefault() {
        val document = BrushDocument.parse(text)
        val removed = document.without("path.spacing")
        assertFalse(removed.has("path.spacing"))
        assertEquals(true, removed.boolean("path.somethingNew"))
        assertSame(document, document.without("path.missing"))
        assertSame(document, document.without("nothing.here"))
    }

    @Test
    fun valuesAreImmutable() {
        val document = BrushDocument.parse(text)
        document.with("path.spacing", 0.5)
        assertEquals(0.08, document.number("path.spacing"))
    }

    @Test
    fun rejectsInvalidEnvelopes() {
        assertThrows<InvalidBrushException> { BrushDocument.parse("not json") }
        assertThrows<InvalidBrushException> { BrushDocument.parse("[]") }
        assertThrows<InvalidBrushException> { BrushDocument.parse("""{ "format": 2, "id": "x", "name": "y" }""") }
        assertThrows<InvalidBrushException> { BrushDocument.parse("""{ "format": 1, "name": "y" }""") }
        assertThrows<InvalidBrushException> { BrushDocument.parse("""{ "format": 1, "id": "x" }""") }
        assertThrows<IllegalArgumentException> { BrushDocument.parse(text).with("path..spacing", 1.0) }
    }
}
