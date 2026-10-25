package com.mobyle.nanquim.document.brush

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrushPackageIoTest {
    @TempDir
    lateinit var temporary: File

    private fun brush(
        id: String = "brush-1",
        name: String = "Ink",
    ) = BrushPackage(
        BrushDocument.parse("""{ "format": 1, "id": "$id", "name": "$name", "unknown": 7 }"""),
        mapOf("shape.png" to byteArrayOf(1, 2, 3), "material/height.png" to byteArrayOf(9)),
    )

    @Test
    fun directoryRoundTrip() {
        val directory = File(temporary, "brush-1")
        BrushPackageIo.writeDirectory(brush(), directory)
        val read = BrushPackageIo.readDirectory(directory)
        assertEquals(brush(), read)
        assertArrayEquals(byteArrayOf(1, 2, 3), read.shapeTextureBytes)
        assertEquals(7, read.document.integer("unknown"))
    }

    @Test
    fun rewritingADirectoryReplacesItWhole() {
        val directory = File(temporary, "brush-1")
        BrushPackageIo.writeDirectory(brush(), directory)
        BrushPackageIo.writeDirectory(brush().withFile("material/height.png", null), directory)
        assertFalse(File(directory, "material/height.png").exists())
        assertTrue(File(directory, "shape.png").exists())
        assertEquals(listOf("brush-1"), temporary.list()!!.toList())
    }

    @Test
    fun brushArchiveRoundTrip() {
        val bytes = BrushPackageIo.writeBrushArchive(brush())
        assertEquals(brush(), BrushPackageIo.readBrushArchive(bytes))
    }

    @Test
    fun brushSetRoundTrip() {
        val set = BrushPackageIo.BrushSet("Inks", listOf(brush("a", "First"), brush("b", "Second")))
        val read = BrushPackageIo.readBrushSet(BrushPackageIo.writeBrushSet(set))
        assertEquals("Inks", read.name)
        assertEquals(set.brushes, read.brushes)
    }

    @Test
    fun rejectsArchivesThatEscapeThePackage() {
        val malicious =
            zipOf(
                "brush.json" to """{ "format": 1, "id": "x", "name": "y" }""".toByteArray(),
                "../evil.png" to byteArrayOf(1),
            )
        assertThrows<InvalidBrushException> { BrushPackageIo.readBrushArchive(malicious) }
        assertThrows<InvalidBrushException> { BrushPackageIo.readBrushArchive(byteArrayOf(1, 2, 3, 4)) }
        assertThrows<InvalidBrushException> { BrushPackageIo.readBrushArchive(zipOf("shape.png" to byteArrayOf(1))) }
        assertThrows<IllegalArgumentException> { brush().withFile("/etc/passwd", byteArrayOf(1)) }
    }

    @Test
    fun rejectsBrushSetsWithUnsafeIds() {
        val set =
            zipOf(
                BrushPackageIo.BRUSHSET_JSON to """{ "format": 1, "name": "x", "brushes": ["../up"] }""".toByteArray(),
            )
        assertThrows<InvalidBrushException> { BrushPackageIo.readBrushSet(set) }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
