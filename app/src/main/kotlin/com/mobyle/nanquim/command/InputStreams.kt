package com.mobyle.nanquim.command

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun InputStream.readAtMost(count: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(count, BUFFER_BYTES))
    val buffer = ByteArray(BUFFER_BYTES)
    var remaining = count
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private const val BUFFER_BYTES = 64 * 1024
