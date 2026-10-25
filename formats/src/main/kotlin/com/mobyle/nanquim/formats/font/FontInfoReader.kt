package com.mobyle.nanquim.formats.font

data class FontFaceInfo(
    val postScriptName: String,
    val family: String,
    val style: String,
    val weight: Int,
    val italic: Boolean,
    val embeddable: Boolean,
    val faceIndex: Int,
)

interface FontByteSource {
    val size: Long

    fun read(
        offset: Long,
        length: Int,
    ): ByteArray?
}

class ByteArrayFontSource(
    private val bytes: ByteArray,
) : FontByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(
        offset: Long,
        length: Int,
    ): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

object FontInfoReader {
    private const val TAG_TTC = 0x74746366
    private const val TAG_NAME = 0x6E616D65
    private const val TAG_OS2 = 0x4F532F32
    private const val MAXIMUM_FACES = 256
    private const val MAXIMUM_TABLES = 512
    private const val MAXIMUM_NAME_TABLE_BYTES = 1 shl 20
    private val SFNT_VERSIONS = setOf(0x00010000, 0x4F54544F, 0x74727565)

    fun faceCount(source: FontByteSource): Int {
        val header = source.read(0, 12) ?: return 0
        return when (header.u32(0)) {
            TAG_TTC -> header.u32(8).takeIf { it in 1..MAXIMUM_FACES } ?: 0
            in SFNT_VERSIONS -> 1
            else -> 0
        }
    }

    fun readAll(source: FontByteSource): List<FontFaceInfo> = (0 until faceCount(source)).mapNotNull { read(source, it) }

    fun read(
        source: FontByteSource,
        faceIndex: Int,
    ): FontFaceInfo? {
        val count = faceCount(source)
        if (faceIndex !in 0 until count) return null
        val header = source.read(0, 12) ?: return null
        val fontOffset =
            if (header.u32(0) == TAG_TTC) {
                (source.read(12L + 4L * faceIndex, 4) ?: return null).u32(0).toLong() and 0xFFFFFFFFL
            } else {
                0L
            }
        val directory = source.read(fontOffset, 12) ?: return null
        if (directory.u32(0) !in SFNT_VERSIONS) return null
        val tableCount = directory.u16(4)
        if (tableCount !in 1..MAXIMUM_TABLES) return null
        val records = source.read(fontOffset + 12, tableCount * 16) ?: return null
        var nameTable: ByteArray? = null
        var os2Table: ByteArray? = null
        for (index in 0 until tableCount) {
            val tag = records.u32(index * 16)
            val offset = records.u32(index * 16 + 8).toLong() and 0xFFFFFFFFL
            val length = records.u32(index * 16 + 12).toLong() and 0xFFFFFFFFL
            if (length > MAXIMUM_NAME_TABLE_BYTES) continue
            when (tag) {
                TAG_NAME -> nameTable = source.read(offset, length.toInt())
                TAG_OS2 -> os2Table = source.read(offset, length.toInt())
            }
        }
        val names = nameTable?.let(::readNames) ?: return null
        val family = names[16] ?: names[1] ?: return null
        val style = names[17] ?: names[2] ?: "Regular"
        val postScript = names[6]?.takeIf { it.isNotBlank() } ?: "${family.replace(" ", "")}-${style.replace(" ", "")}"
        val fsType = os2Table?.takeIf { it.size >= 10 }?.u16(8) ?: 0
        val weight = os2Table?.takeIf { it.size >= 6 }?.u16(4)?.takeIf { it in 1..1000 } ?: 400
        val italic =
            os2Table?.takeIf { it.size >= 64 }?.let { (it.u16(62) and 1) != 0 }
                ?: style.contains("Italic", ignoreCase = true)
        return FontFaceInfo(
            postScriptName = postScript,
            family = family,
            style = style,
            weight = weight,
            italic = italic,
            embeddable = (fsType and 0x0002) == 0 && (fsType and 0x0200) == 0,
            faceIndex = faceIndex,
        )
    }

    private fun readNames(table: ByteArray): Map<Int, String> {
        if (table.size < 6) return emptyMap()
        val count = table.u16(2)
        val storage = table.u16(4)
        val chosen = mutableMapOf<Int, Pair<Int, String>>()
        for (index in 0 until count) {
            val record = 6 + index * 12
            if (record + 12 > table.size) break
            val platform = table.u16(record)
            val encoding = table.u16(record + 2)
            val language = table.u16(record + 4)
            val nameId = table.u16(record + 6)
            val length = table.u16(record + 8)
            val offset = storage + table.u16(record + 10)
            if (nameId !in setOf(1, 2, 6, 16, 17) || offset + length > table.size) continue
            val rank =
                when {
                    platform == 3 && (encoding == 1 || encoding == 10) && language == 0x409 -> 0
                    platform == 3 && (encoding == 1 || encoding == 10) -> 1
                    platform == 0 -> 2
                    platform == 1 && encoding == 0 -> 3
                    else -> continue
                }
            val bytes = table.copyOfRange(offset, offset + length)
            val value =
                if (rank == 3) {
                    String(bytes, Charsets.ISO_8859_1)
                } else {
                    String(bytes, Charsets.UTF_16BE)
                }.trim()
            if (value.isEmpty()) continue
            val current = chosen[nameId]
            if (current == null || rank < current.first) chosen[nameId] = rank to value
        }
        return chosen.mapValues { it.value.second }
    }

    private fun ByteArray.u16(offset: Int): Int = ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.u32(offset: Int): Int = (u16(offset) shl 16) or u16(offset + 2)
}
