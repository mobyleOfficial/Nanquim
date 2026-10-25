package com.mobyle.nanquim.document

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocumentArchiveTest {
    @TempDir
    lateinit var temporary: File

    private fun document(): File {
        val folder = File(temporary, "document").apply { mkdirs() }
        File(folder, "manifest.json").writeText("""{"format":"nanquim"}""")
        File(folder, "thumbnail.png").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "layers/a/tiles").mkdirs()
        File(folder, "layers/a/index.json").writeText("""{"version":1,"tiles":[[0,0]],"revision":1}""")
        File(folder, "layers/a/tiles/0_0.lz4").writeBytes(ByteArray(300) { it.toByte() })
        File(folder, "layers/a/tiles/1_0.lz4.tmp").writeBytes(byteArrayOf(9))
        return folder
    }

    @Test
    fun packageLayoutFollowsTheSpec() {
        val bytes = ByteArrayOutputStream().also { DocumentArchive.write(document(), it) }.toByteArray()
        assertEquals("mimetype", String(bytes, 30, 8, Charsets.US_ASCII))
        assertEquals("application/vnd.nanquim.document+zip", String(bytes, 38, 36, Charsets.US_ASCII))
        val entries = mutableMapOf<String, ZipEntry>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) entries += (zip.nextEntry ?: break).let { it.name to it }
        }
        assertEquals(ZipEntry.STORED, entries.getValue("mimetype").method)
        assertNull(entries.getValue("mimetype").extra)
        assertEquals(ZipEntry.STORED, entries.getValue("layers/a/tiles/0_0.lz4").method)
        assertEquals(ZipEntry.DEFLATED, entries.getValue("manifest.json").method)
        assertFalse(entries.keys.any { it.endsWith(".tmp") })
    }

    @Test
    fun extractRestoresTheFolder() {
        val source = document()
        val bytes = ByteArrayOutputStream().also { DocumentArchive.write(source, it) }.toByteArray()
        val destination = File(temporary, "extracted")
        DocumentArchive.extract(ByteArrayInputStream(bytes), destination)
        assertArrayEquals(File(source, "layers/a/tiles/0_0.lz4").readBytes(), File(destination, "layers/a/tiles/0_0.lz4").readBytes())
        assertEquals(File(source, "manifest.json").readText(), File(destination, "manifest.json").readText())
    }

    @Test
    fun embeddedFontsTravelWithThePackage() {
        val source = document()
        val font = File(temporary, "imported.ttf").apply { writeBytes(byteArrayOf(0, 1, 0, 0, 7)) }
        File(source, "fonts").mkdirs()
        File(source, "fonts/kept.ttf").writeBytes(byteArrayOf(5))
        val extra = mapOf("fonts/imported.ttf" to font, "fonts/kept.ttf" to font)
        val bytes = ByteArrayOutputStream().also { DocumentArchive.write(source, it, extra) }.toByteArray()
        val destination = File(temporary, "with-fonts")
        DocumentArchive.extract(ByteArrayInputStream(bytes), destination)
        assertArrayEquals(font.readBytes(), File(destination, "fonts/imported.ttf").readBytes())
        assertArrayEquals(byteArrayOf(5), File(destination, "fonts/kept.ttf").readBytes())
    }

    @Test
    fun extractRejectsUnsafePackages() {
        for (name in listOf("../escape.txt", "/absolute.txt", "a/../../b.txt", "C:/x.txt", "a\\b.txt")) {
            val bytes =
                ByteArrayOutputStream()
                    .also { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("manifest.json"))
                            zip.write("{}".toByteArray())
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(1)
                        }
                    }.toByteArray()
            assertThrows(IOException::class.java, { DocumentArchive.extract(ByteArrayInputStream(bytes), File(temporary, "unsafe${name.hashCode()}")) }, name)
        }
        assertFalse(File(temporary.parentFile, "escape.txt").exists())
        val noManifest =
            ByteArrayOutputStream()
                .also { stream ->
                    ZipOutputStream(stream).use { zip ->
                        zip.putNextEntry(ZipEntry("other.txt"))
                        zip.write(1)
                    }
                }.toByteArray()
        assertThrows(IOException::class.java) { DocumentArchive.extract(ByteArrayInputStream(noManifest), File(temporary, "empty")) }
    }
}
