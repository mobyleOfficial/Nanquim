package com.mobyle.nanquim.formats.palette

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mobyle.nanquim.document.layer.RgbaColor

class PaletteFileTest {
    private val palette =
        Palette(
            "Mine",
            listOf(PaletteSwatch(RgbaColor(1f, 0.5f, 0f, 1f), "Orange"), null, PaletteSwatch(RgbaColor(0.1f, 0.2f, 0.3f, 0.4f))),
            columns = 5,
        )

    @Test
    fun roundTrips() {
        val stored = PaletteFile.read(PaletteFile.write("abc", palette))
        assertEquals("abc", stored.id)
        assertEquals(palette, stored.palette)
        assertNull(stored.palette.swatches[1])
    }

    @Test
    fun defaultsAndClamping() {
        val stored = PaletteFile.read("""{"format": 1, "id": "x", "name": "N", "swatches": [{"color": [2, -1, 0.5]}]}""")
        assertEquals(Palette.DEFAULT_COLUMNS, stored.palette.columns)
        assertEquals(RgbaColor(1f, 0f, 0.5f, 1f), stored.palette.swatches[0]!!.color)
        assertEquals("", stored.palette.swatches[0]!!.name)
    }

    @Test
    fun badFilesAreRefused() {
        val tooMany = (1..301).joinToString(",") { "null" }
        for (text in listOf(
            "",
            "[]",
            """{"format": 2, "id": "x", "name": "N", "swatches": []}""",
            """{"format": 1, "name": "N", "swatches": []}""",
            """{"format": 1, "id": "x", "swatches": []}""",
            """{"format": 1, "id": "x", "name": "N"}""",
            """{"format": 1, "id": "x", "name": "N", "columns": 0, "swatches": []}""",
            """{"format": 1, "id": "x", "name": "N", "swatches": [$tooMany]}""",
            """{"format": 1, "id": "x", "name": "N", "swatches": [{"color": [1, 0]}]}""",
            """{"format": 1, "id": "x", "name": "N", "swatches": [{"color": ["1", 0, 0]}]}""",
            """{"format": 1, "id": "x", "name": "N", "swatches": [3]}""",
        )) {
            assertThrows<PaletteFormatException>(text) { PaletteFile.read(text) }
        }
    }
}
