package com.mobyle.nanquim.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StrokeSampleBufferTest {
    @Test
    fun `packs records in the little-endian stroke sample layout`() {
        val samples = StrokeSampleBuffer()
        samples.add(
            x = 1.5f,
            y = -2f,
            pressure = 0.75f,
            tilt = 0.25f,
            orientation = 3f,
            distance = 4f,
            eventTimeNanos = 0x0102030405060708L,
            toolType = 2,
            buttonState = 32,
            flags = StrokeSampleFlags.HISTORICAL or StrokeSampleFlags.CANCELED,
            pointerId = 7,
        )

        val bytes = samples.toByteArray()
        assertEquals(StrokeSampleBuffer.RECORD_BYTES, bytes.size)
        val record = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1.5f, record.getFloat(0))
        assertEquals(-2f, record.getFloat(4))
        assertEquals(0.75f, record.getFloat(8))
        assertEquals(0.25f, record.getFloat(12))
        assertEquals(3f, record.getFloat(16))
        assertEquals(4f, record.getFloat(20))
        assertEquals(0x0102030405060708L, record.getLong(24))
        assertEquals(0x08.toByte(), bytes[24])
        assertEquals(2, record.getInt(32))
        assertEquals(32, record.getInt(36))
        assertEquals(9, record.getInt(40))
        assertEquals(7, record.getInt(44))
    }

    @Test
    fun `grows past its initial capacity and keeps earlier records`() {
        val samples = StrokeSampleBuffer(initialCapacity = 2)
        repeat(100) { index -> samples.add(x = index.toFloat(), y = 0f, pressure = 1f, eventTimeNanos = index.toLong(), toolType = 2) }

        assertEquals(100, samples.count)
        val record = ByteBuffer.wrap(samples.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, record.getFloat(0))
        assertEquals(99f, record.getFloat(99 * StrokeSampleBuffer.RECORD_BYTES))
        assertEquals(42L, record.getLong(42 * StrokeSampleBuffer.RECORD_BYTES + 24))
    }

    @Test
    fun `clear starts a new batch`() {
        val samples = StrokeSampleBuffer()
        samples.add(x = 1f, y = 1f, pressure = 1f, eventTimeNanos = 0, toolType = 2)
        samples.clear()
        samples.add(x = 5f, y = 1f, pressure = 1f, eventTimeNanos = 0, toolType = 2)

        assertEquals(1, samples.count)
        assertEquals(5f, ByteBuffer.wrap(samples.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getFloat(0))
    }
}
