package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrushCurveTest {
    @Test
    fun passesThroughItsPointsAndStaysMonotone() {
        val soft = BrushCurve.SOFT
        assertEquals(0.45, soft.evaluate(0.25), 1e-12)
        assertEquals(1.0, soft.evaluate(1.0), 1e-12)
        assertEquals(0.5, BrushCurve.LINEAR.evaluate(0.5), 1e-12)
        var previous = 0.0
        for (step in 0..100) {
            val value = BrushCurve.HARD.evaluate(step / 100.0)
            assertTrue(value >= previous - 1e-12)
            previous = value
        }
    }

    @Test
    fun invalidPointsFallBackToLinear() {
        assertEquals(BrushCurve.LINEAR, BrushCurve(listOf(0.2 to 0.0, 1.0 to 1.0)))
        assertNull(BrushCurve.fromJson(Json.parseToJsonElement("[[0, 0], [0.5, 2], [1, 1]]")))
    }

    @Test
    fun resolvesReferences() {
        val document =
            BrushDocument.parse("""{ "format": 1, "id": "a", "name": "b", "curves": { "mine": [[0, 0], [0.5, 0.1], [1, 1]] } }""")
        assertEquals(0.1, BrushCurve.resolve(document, "curve:mine")!!.evaluate(0.5), 1e-12)
        assertEquals(BrushCurve.SOFT, BrushCurve.resolve(document, "curve:soft"))
        assertNull(BrushCurve.resolve(document, "curve:missing"))
        assertNull(BrushCurve.resolve(document, "soft"))
    }
}
