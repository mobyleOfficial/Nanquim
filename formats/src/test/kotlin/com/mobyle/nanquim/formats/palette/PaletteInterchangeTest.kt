package com.mobyle.nanquim.formats.palette

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.interchange.ImportWarning
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class PaletteInterchangeTest {
    private val palette =
        Palette(
            "Mine",
            listOf(PaletteSwatch(RgbaColor(1f, 0.5f, 0f, 1f), "Orange"), null, PaletteSwatch(RgbaColor(0f, 0f, 1f, 1f))),
            columns = 4,
        )

    @Test
    fun engineJsonBecomesAPalette() {
        val json =
            """{"format": "aco", "name": "", "columns": 0, "swatches": [{"color": [1, 0, 0, 1], "name": "Red"}],
               "warnings": [{"code": "approximateCmyk", "detail": ""}]}"""
        val (imported, warnings) = PaletteInterchange.fromEngineJson(json, "From file")
        assertEquals("From file", imported.name)
        assertEquals(Palette.DEFAULT_COLUMNS, imported.columns)
        assertEquals(PaletteSwatch(RgbaColor(1f, 0f, 0f, 1f), "Red"), imported.swatches.single())
        assertEquals(listOf(ImportWarning("approximateCmyk", "")), warnings)
        assertThrows<PaletteFormatException> { PaletteInterchange.fromEngineJson("[]", "x") }
    }

    @Test
    fun gplHasHeaderAndOneLinePerColor() {
        val text = PaletteInterchange.writeGpl(palette)
        assertEquals(
            "GIMP Palette\nName: Mine\nColumns: 4\n#\n255 128   0\tOrange\n  0   0 255\t#0000FF\n",
            text,
        )
    }

    @Test
    fun aseHasAGroupAndRgbEntries() {
        val bytes = PaletteInterchange.writeAse(palette)
        val data = DataInputStream(ByteArrayInputStream(bytes))
        val signature = ByteArray(4).also { data.readFully(it) }
        assertArrayEquals("ASEF".toByteArray(), signature)
        assertEquals(1, data.readShort().toInt())
        assertEquals(0, data.readShort().toInt())
        assertEquals(4, data.readInt())
        assertEquals(0xC001, data.readUnsignedShort())
        val groupLength = data.readInt()
        assertEquals(2 + ("Mine".length + 1) * 2, groupLength)
        data.skipBytes(groupLength)
        assertEquals(0x0001, data.readUnsignedShort())
        val length = data.readInt()
        assertEquals(2 + ("Orange".length + 1) * 2 + 4 + 12 + 2, length)
        assertEquals("Orange".length + 1, data.readUnsignedShort())
        assertEquals("Orange", String(CharArray(6) { data.readChar() }))
        assertEquals(0, data.readUnsignedShort())
        assertArrayEquals("RGB ".toByteArray(), ByteArray(4).also { data.readFully(it) })
        assertEquals(1f, data.readFloat())
        assertEquals(0.5f, data.readFloat())
        assertEquals(0f, data.readFloat())
        assertEquals(2, data.readUnsignedShort())
    }
}
