package com.mobyle.nanquim.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

object StrokeSampleFlags {
    const val HISTORICAL = 1
    const val PREDICTED = 1 shl 1
    const val HOVER = 1 shl 2
    const val CANCELED = 1 shl 3
}

data class StrokeSample(
    val x: Double,
    val y: Double,
    val pressure: Float,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
    val eventTimeNanos: Long,
    val toolType: Int,
    val buttonState: Int = 0,
    val flags: Int = 0,
    val pointerId: Int = 0,
)

class StrokeSampleBuffer(
    initialCapacity: Int = 64,
) {
    private var buffer: ByteBuffer = allocate(initialCapacity.coerceAtLeast(1))

    var count: Int = 0
        private set

    internal val byteBuffer: ByteBuffer get() = buffer
    internal val byteCount: Int get() = count * RECORD_BYTES

    fun add(
        x: Float,
        y: Float,
        pressure: Float,
        tilt: Float = 0f,
        orientation: Float = 0f,
        distance: Float = 0f,
        eventTimeNanos: Long,
        toolType: Int,
        buttonState: Int = 0,
        flags: Int = 0,
        pointerId: Int = 0,
    ) {
        ensureCapacity(count + 1)
        val offset = count * RECORD_BYTES
        buffer
            .putFloat(offset, x)
            .putFloat(offset + 4, y)
            .putFloat(offset + 8, pressure)
            .putFloat(offset + 12, tilt)
            .putFloat(offset + 16, orientation)
            .putFloat(offset + 20, distance)
            .putLong(offset + 24, eventTimeNanos)
            .putInt(offset + 32, toolType)
            .putInt(offset + 36, buttonState)
            .putInt(offset + 40, flags)
            .putInt(offset + 44, pointerId)
        count++
    }

    fun add(sample: StrokeSample) =
        add(
            x = sample.x.toFloat(),
            y = sample.y.toFloat(),
            pressure = sample.pressure,
            tilt = sample.tilt,
            orientation = sample.orientation,
            eventTimeNanos = sample.eventTimeNanos,
            toolType = sample.toolType,
            buttonState = sample.buttonState,
            flags = sample.flags,
            pointerId = sample.pointerId,
        )

    fun sampleAt(index: Int): StrokeSample {
        require(index in 0 until count) { "No sample $index" }
        val offset = index * RECORD_BYTES
        return StrokeSample(
            x = buffer.getFloat(offset).toDouble(),
            y = buffer.getFloat(offset + 4).toDouble(),
            pressure = buffer.getFloat(offset + 8),
            tilt = buffer.getFloat(offset + 12),
            orientation = buffer.getFloat(offset + 16),
            eventTimeNanos = buffer.getLong(offset + 24),
            toolType = buffer.getInt(offset + 32),
            buttonState = buffer.getInt(offset + 36),
            flags = buffer.getInt(offset + 40),
            pointerId = buffer.getInt(offset + 44),
        )
    }

    fun clear() {
        count = 0
    }

    fun firstPoint(): Point? = if (count == 0) null else pointAt(0)

    fun lastPoint(): Point? = if (count == 0) null else pointAt(count - 1)

    fun pointAt(index: Int): Point {
        require(index in 0 until count) { "No sample $index" }
        val offset = index * RECORD_BYTES
        return Point(buffer.getFloat(offset).toDouble(), buffer.getFloat(offset + 4).toDouble())
    }

    fun setPoint(
        index: Int,
        x: Float,
        y: Float,
    ) {
        require(index in 0 until count) { "No sample $index" }
        val offset = index * RECORD_BYTES
        buffer.putFloat(offset, x).putFloat(offset + 4, y)
    }

    fun copy(): StrokeSampleBuffer {
        val copy = StrokeSampleBuffer(count.coerceAtLeast(1))
        val records = buffer.duplicate().apply { position(0).limit(byteCount) }
        copy.buffer.put(records)
        copy.buffer.position(0)
        copy.count = count
        return copy
    }

    fun toByteArray(): ByteArray {
        val bytes = ByteArray(byteCount)
        buffer.duplicate().apply { position(0) }.get(bytes)
        return bytes
    }

    private fun ensureCapacity(records: Int) {
        if (records * RECORD_BYTES <= buffer.capacity()) return
        val grown = allocate(maxOf(records, buffer.capacity() / RECORD_BYTES * 2))
        val existing = buffer.duplicate().apply { position(0).limit(byteCount) }
        grown.put(existing)
        buffer = grown
    }

    private fun allocate(records: Int): ByteBuffer = ByteBuffer.allocateDirect(records * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
        const val RECORD_BYTES = 48
    }
}
