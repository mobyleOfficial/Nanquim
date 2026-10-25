package com.mobyle.nanquim.formats.blend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.BlendMode
import java.io.File
import kotlin.math.abs

class BlendReferenceTest {
    private fun JsonArray.toRgba() = Rgba(this[0].jsonPrimitive.double, this[1].jsonPrimitive.double, this[2].jsonPrimitive.double, this[3].jsonPrimitive.double)

    private fun assertClose(
        expected: Rgba,
        actual: Rgba,
        context: String,
    ) {
        val pairs = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue, expected.alpha to actual.alpha)
        pairs.forEach { (want, got) -> assertTrue(abs(want - got) < TOLERANCE, "$context: expected $expected, got $actual") }
    }

    @Test
    fun matchesTheCppReferenceTable() {
        val table = Json.parseToJsonElement(File(TABLE_PATH).readText()).jsonObject
        val entries = table.getValue("entries").jsonArray
        assertEquals((BlendMode.LAYER_MODES.size + 1) * 64, entries.size)
        for (element in entries) {
            val entry = element.jsonObject
            val modeId = entry.getValue("mode").jsonPrimitive.content
            val backdrop = entry.getValue("backdrop").jsonArray.toRgba()
            val source = entry.getValue("source").jsonArray.toRgba()
            val expected = entry.getValue("result").jsonArray.toRgba()
            val context = "$modeId $backdrop ← $source"
            if (modeId == "behind") {
                assertClose(expected, BlendReference.behind(backdrop, source), context)
                continue
            }
            val mode = BlendMode.fromId(modeId) ?: error("unknown mode $modeId")
            assertClose(expected, BlendReference.composite(mode, backdrop, source), context)
            assertClose(entry.getValue("clipped").jsonArray.toRgba(), BlendReference.clipComposite(mode, backdrop, source), "clipped $context")
        }
    }

    @Test
    fun knownValues() {
        assertEquals(0.25, BlendReference.separable(BlendMode.Multiply, 0.5, 0.5), TOLERANCE)
        assertEquals(0.75, BlendReference.separable(BlendMode.Screen, 0.5, 0.5), TOLERANCE)
        assertEquals(1.0, BlendReference.separable(BlendMode.HardMix, 0.5, 0.5), TOLERANCE)
        val gray = doubleArrayOf(0.5, 0.5, 0.5)
        val red = doubleArrayOf(1.0, 0.0, 0.0)
        assertEquals(0.3, BlendReference.luminosity(BlendReference.blendColor(BlendMode.Luminosity, gray, red)), TOLERANCE)
        val opaque = BlendReference.composite(BlendMode.Multiply, Rgba(0.5, 0.5, 0.5, 1.0), Rgba(0.5, 1.0, 0.0, 1.0))
        assertClose(Rgba(0.25, 0.5, 0.0, 1.0), opaque, "multiply")
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val TABLE_PATH = "../goldens/blend/blend_table.json"
    }
}
