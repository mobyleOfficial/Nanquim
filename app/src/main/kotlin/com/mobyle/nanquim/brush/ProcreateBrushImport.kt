package com.mobyle.nanquim.brush

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.engine.InterchangeFiles
import com.mobyle.nanquim.formats.interchange.ConvertedBrush
import com.mobyle.nanquim.formats.interchange.ImportJsonException
import com.mobyle.nanquim.formats.interchange.ProcreateBrush
import com.mobyle.nanquim.formats.interchange.ProcreateBrushSet
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

data class ConvertedBrushFile(
    val name: String?,
    val brushes: List<ConvertedBrush>,
)

object ProcreateBrushImport {
    private const val MAXIMUM_ENTRIES = 4096
    private const val MAXIMUM_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAXIMUM_TOTAL_BYTES = 256L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun convert(bytes: ByteArray): ConvertedBrushFile {
        val entries = unzip(bytes)
        val createdAt = Instant.now().toString()
        val index = entries[ProcreateBrushSet.INDEX]?.let { ProcreateBrushSet.parseIndex(it.toString(Charsets.UTF_8)) }
        val folders =
            entries.keys
                .filter { it.endsWith("/${ProcreateBrush.ARCHIVE}") && it.count { character -> character == '/' } == 1 }
                .map { it.substringBefore('/') }
        val ordered = (index?.brushes.orEmpty().filter { it in folders } + folders).distinct()
        val brushes =
            if (ordered.isNotEmpty()) {
                ordered.map { folder -> convertFolder(entries, "$folder/", createdAt) }
            } else if (ProcreateBrush.ARCHIVE in entries) {
                listOf(convertFolder(entries, "", createdAt))
            } else {
                throw ImportJsonException("No brush in the file")
            }
        return ConvertedBrushFile(if (index != null || ordered.size > 1) index?.name.orEmpty() else null, brushes)
    }

    private fun convertFolder(
        entries: Map<String, ByteArray>,
        prefix: String,
        createdAt: String,
    ): ConvertedBrush {
        val archive = entries["$prefix${ProcreateBrush.ARCHIVE}"] ?: throw ImportJsonException("No Brush.archive")
        val text = InterchangeFiles.keyedArchiveJson(archive) ?: throw ImportJsonException("Unreadable Brush.archive")
        val tree =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw ImportJsonException("Unreadable Brush.archive")
        val files =
            listOf(ProcreateBrush.SHAPE, ProcreateBrush.GRAIN, ProcreateBrush.AUTHOR_PICTURE, ProcreateBrush.SIGNATURE)
                .mapNotNull { name -> entries["$prefix$name"]?.let { name to it } }
                .toMap()
        return ProcreateBrush.convert(tree, files, UUID.randomUUID().toString(), createdAt)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entries.size >= MAXIMUM_ENTRIES) throw IOException("Too many entries")
                val name = entry.name
                if (entry.isDirectory || name.startsWith("/") || ".." in name.split('/') || '\\' in name) continue
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    total += count
                    if (output.size() > MAXIMUM_ENTRY_BYTES || total > MAXIMUM_TOTAL_BYTES) throw IOException("The brush file is too large")
                }
                entries[name] = output.toByteArray()
            }
        }
        return entries
    }
}
