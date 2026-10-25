package com.mobyle.nanquim.formats.gradient

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GradientFileTest {
    private val sunset =
        GradientDefinition(
            "Sunset",
            listOf(GradientStop(0f, 0.1f, 0f, 0.3f), GradientStop(0.5f, 0.9f, 0.2f, 0.4f), GradientStop(1f, 1f, 0.95f, 0.6f)),
        )

    @Test
    fun `round trips`() {
        assertEquals(sunset, GradientFile.read(GradientFile.write(sunset)))
    }

    @Test
    fun `reads the documented layout and sorts the stops`() {
        val text = """{"format": 1, "name": "Two", "stops": [{"position": 1, "color": [1, 1, 1]}, {"position": 0, "color": [0, 0, 0]}]}"""
        val gradient = GradientFile.read(text)
        assertEquals("Two", gradient.name)
        assertEquals(listOf(0f, 1f), gradient.stops.map { it.position })
    }

    @Test
    fun `rejects invalid files`() {
        for (text in listOf(
            "not json",
            """{"name": "No format", "stops": [{"position": 0, "color": [0, 0, 0]}]}""",
            """{"format": 2, "stops": [{"position": 0, "color": [0, 0, 0]}]}""",
            """{"format": 1, "stops": []}""",
            """{"format": 1, "stops": [{"position": 0, "color": [0, 0]}]}""",
            """{"format": 1, "stops": [{"position": 1.5, "color": [0, 0, 0]}]}""",
        )) {
            assertThrows<GradientFormatException>(text) { GradientFile.read(text) }
        }
    }

    @Test
    fun `table data and colors follow the stops`() {
        assertArrayEquals(
            floatArrayOf(3f, 0f, 0.1f, 0f, 0.3f, 0.5f, 0.9f, 0.2f, 0.4f, 1f, 1f, 0.95f, 0.6f),
            sunset.tableData(),
        )
        assertArrayEquals(floatArrayOf(0.5f, 0.1f, 0.35f), sunset.colorAt(0.25f), 1e-6f)
        assertArrayEquals(floatArrayOf(0.1f, 0f, 0.3f), sunset.colorAt(-1f), 0f)
        assertArrayEquals(floatArrayOf(1f, 0.95f, 0.6f), sunset.colorAt(1f), 0f)
    }
}
