package com.mobyle.nanquim.document

import java.io.ByteArrayOutputStream

object JpegIcc {
    private const val MARKER = 0xFF
    private const val SOI = 0xD8
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val SOS = 0xDA
    private const val MAXIMUM_CHUNK = 65519
    private const val MAXIMUM_CHUNKS = 255
    private val SIGNATURE = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)

    fun embed(
        jpeg: ByteArray,
        icc: ByteArray,
    ): ByteArray {
        val chunkCount = (icc.size + MAXIMUM_CHUNK - 1) / MAXIMUM_CHUNK
        if (icc.isEmpty() || chunkCount > MAXIMUM_CHUNKS) return jpeg
        if (jpeg.size < 4 || jpeg.unsigned(0) != MARKER || jpeg.unsigned(1) != SOI) return jpeg
        val out = ByteArrayOutputStream(jpeg.size + icc.size + chunkCount * 18)
        out.write(jpeg, 0, 2)
        var position = 2
        var inserted = false
        while (position + 4 <= jpeg.size && jpeg.unsigned(position) == MARKER) {
            val marker = jpeg.unsigned(position + 1)
            if (marker == SOS) break
            val length = (jpeg.unsigned(position + 2) shl 8) or jpeg.unsigned(position + 3)
            if (length < 2 || position + 2 + length > jpeg.size) return jpeg
            if (!inserted && marker != APP0 && marker != APP1) {
                writeProfile(out, icc, chunkCount)
                inserted = true
            }
            if (!(marker == APP2 && isProfileSegment(jpeg, position))) {
                out.write(jpeg, position, 2 + length)
            }
            position += 2 + length
        }
        if (!inserted) writeProfile(out, icc, chunkCount)
        out.write(jpeg, position, jpeg.size - position)
        return out.toByteArray()
    }

    private fun writeProfile(
        out: ByteArrayOutputStream,
        icc: ByteArray,
        chunkCount: Int,
    ) {
        for (index in 0 until chunkCount) {
            val start = index * MAXIMUM_CHUNK
            val size = minOf(MAXIMUM_CHUNK, icc.size - start)
            val length = 2 + SIGNATURE.size + 2 + size
            out.write(MARKER)
            out.write(APP2)
            out.write(length shr 8)
            out.write(length and 0xFF)
            out.write(SIGNATURE)
            out.write(index + 1)
            out.write(chunkCount)
            out.write(icc, start, size)
        }
    }

    private fun isProfileSegment(
        jpeg: ByteArray,
        position: Int,
    ): Boolean {
        val start = position + 4
        if (start + SIGNATURE.size > jpeg.size) return false
        return SIGNATURE.indices.all { jpeg[start + it] == SIGNATURE[it] }
    }

    private fun ByteArray.unsigned(index: Int): Int = this[index].toInt() and 0xFF
}
