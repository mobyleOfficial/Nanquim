package com.mobyle.nanquim.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class InputStreamsTest {
    @Test
    fun readsUpToTheLimitAcrossShortReads() {
        val data = ByteArray(200_000) { it.toByte() }
        val slow =
            object : InputStream() {
                private val inner = ByteArrayInputStream(data)

                override fun read(): Int = inner.read()

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = inner.read(buffer, offset, minOf(length, 1000))
            }
        assertEquals(data.copyOf(150_001).toList(), slow.readAtMost(150_001).toList())
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readAtMost(100).size)
    }
}
