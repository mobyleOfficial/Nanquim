package com.mobyle.nanquim.document

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipProbeTest {
    @TempDir
    lateinit var temporary: File

    private fun names(file: File): List<String>? = RandomAccessFile(file, "r").use { ZipProbe.entryNames(it.channel) }

    @Test
    fun `entry names come from the central directory`() {
        val file = File(temporary, "archive.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (name in listOf("layer/0~0.lz4", "Document.archive", "QuickLook/Thumbnail.png")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(5000) { it.toByte() })
            }
            zip.setComment("a comment")
        }
        assertEquals(listOf("layer/0~0.lz4", "Document.archive", "QuickLook/Thumbnail.png"), names(file))
    }

    @Test
    fun `other files are not zips`() {
        val file = File(temporary, "image.png").apply { writeBytes(ByteArray(300) { 7 }) }
        assertNull(names(file))
        val empty = File(temporary, "empty").apply { writeBytes(ByteArray(0)) }
        assertNull(names(empty))
        val zip = File(temporary, "cut.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a"))
            out.write(ByteArray(100))
        }
        val bytes = zip.readBytes()
        val cut = File(temporary, "cut2.zip").apply { writeBytes(bytes.copyOf(bytes.size - 30)) }
        assertNull(names(cut))
    }
}
