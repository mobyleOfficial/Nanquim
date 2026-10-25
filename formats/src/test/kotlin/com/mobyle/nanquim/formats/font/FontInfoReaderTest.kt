package com.mobyle.nanquim.formats.font

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random

class FontInfoReaderTest {
    private val fonts = File("../goldens/fonts")
    private val bundled = File("../assets-src/fonts")

    private fun read(file: File) = FontInfoReader.readAll(ByteArrayFontSource(file.readBytes()))

    @Test
    fun readsNamesWeightAndEmbedding() {
        val sans = read(File(fonts, "NotoSans-Regular-subset.ttf")).single()
        assertEquals("NotoSans-Regular", sans.postScriptName)
        assertEquals("Noto Sans", sans.family)
        assertEquals("Regular", sans.style)
        assertEquals(400, sans.weight)
        assertFalse(sans.italic)
        assertTrue(sans.embeddable)
        val bold = read(File(bundled, "Inter-Bold.ttf")).single()
        assertEquals("Inter-Bold", bold.postScriptName)
        assertEquals(700, bold.weight)
        assertTrue(read(File(bundled, "NotoSans-Italic.ttf")).single().italic)
        assertEquals("NotoSansCJKjp-Regular", read(File(fonts, "NotoSansCJKjp-Regular-subset.otf")).single().postScriptName)
    }

    @Test
    fun readsEveryFaceOfACollection() {
        val one = File(fonts, "NotoSans-Regular-subset.ttf").readBytes()
        val two = File(fonts, "NotoSansHebrew-Regular-test.ttf").readBytes()
        val collection = collectionOf(one, two)
        assertEquals(2, FontInfoReader.faceCount(ByteArrayFontSource(collection)))
        assertEquals(
            listOf("NotoSans-Regular", "NotoSansHebrew-Regular"),
            FontInfoReader.readAll(ByteArrayFontSource(collection)).map {
                it.postScriptName
            },
        )
        assertEquals(listOf(0, 1), FontInfoReader.readAll(ByteArrayFontSource(collection)).map { it.faceIndex })
    }

    @Test
    fun refusesWhatIsNotAFont() {
        assertEquals(0, FontInfoReader.faceCount(ByteArrayFontSource(ByteArray(0))))
        assertEquals(0, FontInfoReader.faceCount(ByteArrayFontSource("not a font at all".toByteArray())))
        assertNull(FontInfoReader.read(ByteArrayFontSource(ByteArray(64)), 0))
        val bytes = File(fonts, "NotoSansHebrew-Regular-test.ttf").readBytes()
        val random = Random(20261022)
        repeat(200) {
            val damaged = bytes.copyOf(random.nextInt(bytes.size))
            repeat(8) { damaged[random.nextInt(maxOf(1, damaged.size))] = random.nextInt(256).toByte() }
            FontInfoReader.readAll(ByteArrayFontSource(damaged))
        }
    }

    private fun collectionOf(vararg fontsData: ByteArray): ByteArray {
        val headerSize = 12 + 4 * fontsData.size
        val output = java.io.ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        var position = headerSize
        val bodies =
            fontsData.map { font ->
                offsets += position
                val tableCount = ((font[4].toInt() and 0xFF) shl 8) or (font[5].toInt() and 0xFF)
                val directorySize = 12 + 16 * tableCount
                val copy = font.copyOf()
                for (index in 0 until tableCount) {
                    val at = 12 + index * 16 + 8
                    val old =
                        java.nio.ByteBuffer
                            .wrap(copy, at, 4)
                            .int
                    java.nio.ByteBuffer
                        .wrap(copy, at, 4)
                        .putInt(old + position)
                }
                position += copy.size
                check(directorySize <= copy.size)
                copy
            }
        val header = java.nio.ByteBuffer.allocate(headerSize)
        header.putInt(0x74746366).putInt(0x00010000).putInt(fontsData.size)
        offsets.forEach { header.putInt(it) }
        output.write(header.array())
        bodies.forEach(output::write)
        return output.toByteArray()
    }
}
