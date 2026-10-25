package com.mobyle.nanquim.color

import com.mobyle.nanquim.engine.InterchangeFiles
import com.mobyle.nanquim.formats.interchange.ImportJsonException
import com.mobyle.nanquim.formats.interchange.ImportWarning
import com.mobyle.nanquim.formats.interchange.ProcreateSwatches
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteFile
import com.mobyle.nanquim.formats.palette.PaletteFormatException
import com.mobyle.nanquim.formats.palette.PaletteInterchange
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

enum class PaletteFileFormat(
    val id: String,
    val extension: String,
    val mimeType: String,
) {
    Nanquim("nqpalette", "nqpalette", "application/vnd.nanquim.palette+json"),
    Ase("ase", "ase", "application/octet-stream"),
    Gpl("gpl", "gpl", "text/plain"),
    ;

    companion object {
        fun fromId(id: String): PaletteFileFormat? = entries.firstOrNull { it.id == id }

        val IMPORT_EXTENSIONS = listOf("nqpalette", "aco", "ase", "gpl", "swatches")
    }
}

object PaletteImport {
    const val MAXIMUM_BYTES = 4 shl 20

    class TooLargeException : Exception("The palette file is too large")

    fun read(
        bytes: ByteArray,
        fallbackName: String,
        cmykProfile: ByteArray? = null,
    ): Pair<Palette, List<ImportWarning>> {
        val start = firstContent(bytes)
        return when {
            start < bytes.size && bytes[start] == '{'.code.toByte() ->
                PaletteFile.read(String(bytes, start, bytes.size - start, Charsets.UTF_8)).palette to emptyList()
            bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> readSwatches(bytes, fallbackName)
            else -> {
                val json = InterchangeFiles.paletteJson(bytes, cmykProfile) ?: throw PaletteFormatException("Not a palette file")
                PaletteInterchange.fromEngineJson(json, fallbackName)
            }
        }
    }

    fun readLimited(input: InputStream): ByteArray {
        val buffer = ByteArray(MAXIMUM_BYTES + 1)
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) break
            total += count
        }
        if (total > MAXIMUM_BYTES) throw TooLargeException()
        return buffer.copyOf(total)
    }

    private fun readSwatches(
        bytes: ByteArray,
        fallbackName: String,
    ): Pair<Palette, List<ImportWarning>> {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == ProcreateSwatches.ENTRY || entry.name.endsWith("/${ProcreateSwatches.ENTRY}")) {
                    val text = String(readLimited(zip), Charsets.UTF_8)
                    val (palette, warnings) =
                        try {
                            ProcreateSwatches.parse(text)
                        } catch (exception: ImportJsonException) {
                            throw PaletteFormatException(exception.message ?: "Bad Swatches.json")
                        }
                    return palette.copy(name = palette.name.ifBlank { fallbackName }) to warnings
                }
            }
        }
        throw PaletteFormatException("No ${ProcreateSwatches.ENTRY} in the archive")
    }

    private fun firstContent(bytes: ByteArray): Int {
        var index =
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        while (index < bytes.size && bytes[index].toInt().toChar().isWhitespace()) index++
        return index
    }
}
