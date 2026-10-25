package com.mobyle.nanquim.document.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextModelEditingTest {
    private val base = TextModelEditing.newModel("Hello", "Inter-Regular", 48.0, RgbaColor.BLACK, 10.0, 20.0)

    private fun runs(model: JsonObject) =
        (model["content"] as JsonArray).map {
            it.jsonObject["text"]!!.jsonPrimitive.content to
                it.jsonObject["style"]!!.jsonPrimitive.content
        }

    private val bold = JsonObject(mapOf("fontId" to JsonPrimitive("Inter-Bold")))

    @Test
    fun newModelHasTheSpecFields() {
        assertEquals("Hello", TextModelEditing.plainText(base))
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 10.0, 20.0), TextModelEditing.transform(base))
        assertEquals("Inter-Regular", TextModelEditing.styleAt(base, 0)["fontId"]!!.jsonPrimitive.content)
        assertEquals("auto", base["box"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun replacesRangesKeepingStyles() {
        var model = TextModelEditing.replace(base, 5, 5, " world")
        assertEquals("Hello world", TextModelEditing.plainText(model))
        assertEquals(1, runs(model).size)
        model = TextModelEditing.applyStyle(model, 6, 11, bold)
        assertEquals(listOf("Hello " to "s0", "world" to "s1"), runs(model))
        model = TextModelEditing.replace(model, 11, 11, "!")
        assertEquals("world!", runs(model)[1].first)
        model = TextModelEditing.replace(model, 3, 8, "p, wo")
        assertEquals("Help, world!", TextModelEditing.plainText(model))
        model = TextModelEditing.replace(model, 0, 12, "")
        assertEquals(listOf("" to "s0"), runs(model))
    }

    @Test
    fun stylesAreSharedAndUnusedOnesDropped() {
        var model = TextModelEditing.applyStyle(base, 0, 2, bold)
        model = TextModelEditing.applyStyle(model, 3, 5, bold)
        assertEquals(listOf("He" to "s1", "l" to "s0", "lo" to "s1"), runs(model))
        assertEquals(2, (model["styles"] as JsonObject).size)
        model = TextModelEditing.applyStyle(model, 0, 5, JsonObject(mapOf("fontId" to JsonPrimitive("Inter-Regular"))))
        assertEquals(listOf("Hello" to "s0"), runs(model))
        assertEquals(setOf("s0"), (model["styles"] as JsonObject).keys)
    }

    @Test
    fun typingStyleAppliesToInsertedText() {
        val (styled, key) = TextModelEditing.withTypingStyle(base, 5, bold)
        val model = TextModelEditing.replace(styled, 5, 5, "!", key)
        assertEquals(listOf("Hello" to "s0", "!" to key), runs(model))
        assertNotEquals("s0", key)
    }

    @Test
    fun paragraphBoxAndTransformMerge() {
        var model = TextModelEditing.setParagraph(base, JsonObject(mapOf("alignment" to JsonPrimitive("center"))))
        model = TextModelEditing.setBox(model, JsonObject(mapOf("mode" to JsonPrimitive("fixed"), "width" to JsonPrimitive(300))))
        model = TextModelEditing.setTransform(model, listOf(2.0, 0.0, 0.0, 2.0, 5.0, 5.0))
        assertEquals("center", model["paragraph"]!!.jsonObject["alignment"]!!.jsonPrimitive.content)
        assertEquals("1.2", model["paragraph"]!!.jsonObject["lineHeight"]!!.jsonPrimitive.content)
        assertEquals("fixed", model["box"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(2.0, TextModelEditing.transform(model)[0])
        val future = JsonObject(base + ("futureField" to JsonPrimitive(7)))
        assertTrue(TextModelEditing.replace(future, 0, 0, "x").containsKey("futureField"))
    }
}
