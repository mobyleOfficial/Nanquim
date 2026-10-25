package com.mobyle.nanquim.color

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteFormatException
import com.mobyle.nanquim.formats.palette.PaletteInterchange
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

@RunWith(AndroidJUnit4::class)
class PaletteImportDeviceTest {
    private val palette =
        Palette(
            "Ida e volta",
            listOf(
                PaletteSwatch(RgbaColor(1f, 0.5f, 0f, 1f), "Laranja"),
                null,
                PaletteSwatch(RgbaColor(0.2f, 0.4f, 0.6f, 1f), "Azul ç"),
            ),
            columns = 5,
        )

    @Test
    fun acoWithNamesAndApproximateCmyk() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { data ->
            data.writeShort(2)
            data.writeShort(2)
            for ((space, values, name) in listOf(
                Triple(0, listOf(65535, 0, 0, 0), "Vermelho"),
                Triple(2, listOf(0, 65535, 65535, 65535), "Ciano"),
            )) {
                data.writeShort(space)
                values.forEach(data::writeShort)
                data.writeInt(name.length + 1)
                name.forEach { data.writeChar(it.code) }
                data.writeShort(0)
            }
        }
        val (imported, warnings) = PaletteImport.read(bytes.toByteArray(), "Arquivo")
        assertEquals("Arquivo", imported.name)
        assertEquals(listOf("Vermelho", "Ciano"), imported.swatches.map { it!!.name })
        assertEquals(RgbaColor(1f, 0f, 0f, 1f), imported.swatches[0]!!.color)
        assertEquals(RgbaColor(0f, 1f, 1f, 1f), imported.swatches[1]!!.color)
        assertTrue(warnings.any { it.code == "approximateCmyk" })
    }

    @Test
    fun aseWrittenByTheAppReadsBack() {
        val (imported, warnings) = PaletteImport.read(PaletteInterchange.writeAse(palette), "x")
        assertEquals(listOf("Laranja", "Azul ç"), imported.swatches.map { it!!.name })
        assertEquals(palette.swatches[2]!!.color, imported.swatches[1]!!.color)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun gplWrittenByTheAppReadsBack() {
        val (imported, _) = PaletteImport.read(PaletteInterchange.writeGpl(palette).toByteArray(Charsets.UTF_8), "x")
        assertEquals("Ida e volta", imported.name)
        assertEquals(5, imported.columns)
        assertEquals(listOf("Laranja", "Azul ç"), imported.swatches.map { it!!.name })
        assertEquals(102 / 255f, imported.swatches[1]!!.color.green, 1e-6f)
    }

    @Test(expected = PaletteFormatException::class)
    fun otherBytesAreRefused() {
        PaletteImport.read("not a palette".toByteArray(), "x")
    }
}
