package com.mobyle.nanquim.document

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object ZipProbe {
    private const val END_SIGNATURE = 0x06054b50
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
    private const val ZIP64_END_SIGNATURE = 0x06064b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val END_BYTES = 22
    private const val MAXIMUM_COMMENT_BYTES = 0xFFFF
    private const val MAXIMUM_DIRECTORY_BYTES = 64L * 1024 * 1024
    private const val MAXIMUM_ENTRIES = 200_000

    fun entryNames(
        contentResolver: ContentResolver,
        uri: Uri,
    ): List<String>? =
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { entryNames(it) }
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    fun entryNames(channel: FileChannel): List<String>? {
        val size = channel.size()
        if (size < END_BYTES) return null
        val tailBytes = minOf(size, (END_BYTES + MAXIMUM_COMMENT_BYTES).toLong()).toInt()
        val tail = read(channel, size - tailBytes, tailBytes) ?: return null
        var end = -1
        for (index in tailBytes - END_BYTES downTo 0) {
            if (tail.getInt(index) == END_SIGNATURE) {
                end = index
                break
            }
        }
        if (end < 0) return null
        var count = tail.getShort(end + 10).toLong() and 0xFFFF
        var directorySize = tail.getInt(end + 12).toLong() and 0xFFFFFFFFL
        var directoryOffset = tail.getInt(end + 16).toLong() and 0xFFFFFFFFL
        if (directoryOffset == 0xFFFFFFFFL || count == 0xFFFFL) {
            val locatorOffset = size - tailBytes + end - 20
            if (locatorOffset < 0) return null
            val locator = read(channel, locatorOffset, 20) ?: return null
            if (locator.getInt(0) != ZIP64_LOCATOR_SIGNATURE) return null
            val recordOffset = locator.getLong(8)
            if (recordOffset < 0 || recordOffset > size - 56) return null
            val record = read(channel, recordOffset, 56) ?: return null
            if (record.getInt(0) != ZIP64_END_SIGNATURE) return null
            count = record.getLong(32)
            directorySize = record.getLong(40)
            directoryOffset = record.getLong(48)
        }
        if (count < 0 || count > MAXIMUM_ENTRIES || directorySize < 0 || directorySize > MAXIMUM_DIRECTORY_BYTES ||
            directoryOffset < 0 || directoryOffset > size - directorySize
        ) {
            return null
        }
        val directory = read(channel, directoryOffset, directorySize.toInt()) ?: return null
        val names = ArrayList<String>(count.toInt())
        var position = 0
        repeat(count.toInt()) {
            if (position + 46 > directory.limit() || directory.getInt(position) != CENTRAL_SIGNATURE) return null
            val nameLength = directory.getShort(position + 28).toInt() and 0xFFFF
            val extraLength = directory.getShort(position + 30).toInt() and 0xFFFF
            val commentLength = directory.getShort(position + 32).toInt() and 0xFFFF
            if (position + 46 + nameLength > directory.limit()) return null
            val bytes = ByteArray(nameLength)
            directory.position(position + 46)
            directory.get(bytes)
            names += String(bytes, Charsets.UTF_8)
            position += 46 + nameLength + extraLength + commentLength
        }
        return names
    }

    private fun read(
        channel: FileChannel,
        offset: Long,
        count: Int,
    ): ByteBuffer? {
        val buffer = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) return null
            position += read
        }
        buffer.flip()
        return buffer
    }
}
