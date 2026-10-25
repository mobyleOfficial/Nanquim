package com.mobyle.nanquim.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteFile
import com.mobyle.nanquim.formats.palette.PaletteFormatException
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PaletteImportTest {
    private val palette = Palette("Mine", listOf(PaletteSwatch(RgbaColor(1f, 0f, 0f, 0.5f), "Red"), null), columns = 2)

    @Test
    fun nqpaletteIsReadAsIs() {
        val bytes = ("﻿  " + PaletteFile.write("old-id", palette)).toByteArray(Charsets.UTF_8)
        val (imported, warnings) = PaletteImport.read(bytes, "File")
        assertEquals(palette, imported)
        assertEquals(emptyList<Any>(), warnings)
    }

    @Test
    fun swatchesArchiveIsReadFromItsJson() {
        val json =
            """{"name": "", "swatches": [{"hue": 0, "saturation": 1, "brightness": 1, "alpha": 1, "name": "R"}, null]}"""
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("Swatches.json"))
            out.write(json.toByteArray())
            out.closeEntry()
        }
        val (imported, _) = PaletteImport.read(zip.toByteArray(), "From the file name")
        assertEquals("From the file name", imported.name)
        assertEquals(RgbaColor(1f, 0f, 0f, 1f), imported.swatches[0]!!.color)
    }

    @Test
    fun archiveWithoutSwatchesIsRefused() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("other.txt"))
            out.write(1)
            out.closeEntry()
        }
        assertThrows<PaletteFormatException> { PaletteImport.read(zip.toByteArray(), "x") }
    }

    @Test
    fun readLimitedRefusesLargeFiles() {
        assertEquals(10, PaletteImport.readLimited(ByteArrayInputStream(ByteArray(10))).size)
        assertThrows<PaletteImport.TooLargeException> {
            PaletteImport.readLimited(ByteArrayInputStream(ByteArray(PaletteImport.MAXIMUM_BYTES + 1)))
        }
    }
}
