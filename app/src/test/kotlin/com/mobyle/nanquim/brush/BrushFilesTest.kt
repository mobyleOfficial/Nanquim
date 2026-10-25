package com.mobyle.nanquim.brush

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.document.brush.BrushPackageIo
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrushFilesTest {
    private fun brush(id: String) = BrushPackage(BrushDocument.parse("""{ "format": 1, "id": "$id", "name": "Ink" }"""))

    @Test
    fun `brush files are recognized by their content`() {
        assertEquals(BrushFileKind.Brush, BrushFiles.kindOf(BrushPackageIo.writeBrushArchive(brush("a"))))
        val set = BrushPackageIo.writeBrushSet(BrushPackageIo.BrushSet("Set", listOf(brush("a"), brush("b"))))
        assertEquals(BrushFileKind.BrushSet, BrushFiles.kindOf(set))
    }

    @Test
    fun `other files are not brushes`() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write("{}".toByteArray())
        }
        assertNull(BrushFiles.kindOf(zip.toByteArray()))
        assertNull(BrushFiles.kindOf("8BPS".toByteArray()))
        assertNull(BrushFiles.kindOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2)))
        assertNull(BrushFiles.kindOf(ByteArray(0)))
    }

    @Test
    fun `exported file names are safe`() {
        assertEquals("a_b_c.nqbrush", BrushFiles.fileName("a/b:c", "nqbrush"))
        assertEquals("brush.nqbrushset", BrushFiles.fileName("  ", "nqbrushset"))
    }
}
