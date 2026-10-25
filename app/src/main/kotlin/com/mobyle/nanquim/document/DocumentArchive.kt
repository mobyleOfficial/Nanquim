package com.mobyle.nanquim.document

import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DocumentArchive {
    const val MAXIMUM_ENTRY_BYTES = 256L * 1024 * 1024
    const val MAXIMUM_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    const val MAXIMUM_ENTRIES = 1_000_000

    fun write(
        folder: File,
        output: OutputStream,
        extraFiles: Map<String, File> = emptyMap(),
    ) {
        ZipOutputStream(output.buffered()).use { zip ->
            val mimetype = NanquimFileType.Document.mimeType.toByteArray(Charsets.US_ASCII)
            putStored(zip, NanquimManifest.MIMETYPE_FILE, mimetype)
            folder
                .walkTopDown()
                .filter { it.isFile && it.name != NanquimManifest.MIMETYPE_FILE && !it.name.endsWith(".tmp") && it.name != "save.journal" }
                .sortedBy { it.relativeTo(folder).invariantSeparatorsPath }
                .forEach { file ->
                    val name = file.relativeTo(folder).invariantSeparatorsPath
                    if (file.extension == "lz4") {
                        putStored(zip, name, file.readBytes())
                    } else {
                        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            for ((name, file) in extraFiles.toSortedMap()) {
                if (File(folder, name).exists() || !file.isFile) continue
                zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun putStored(
        zip: ZipOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry =
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().also { it.update(bytes) }.value
            }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun extract(
        input: InputStream,
        destination: File,
    ) {
        if (!destination.mkdirs()) throw IOException("Cannot create ${destination.path}")
        val root = destination.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MAXIMUM_ENTRIES) throw IOException("Too many entries")
                val name = entry.name
                if (name.isEmpty() || name.startsWith("/") || name.contains('\\') || name.contains(':') ||
                    name.split('/').any { it == ".." || it == "." }
                ) {
                    throw IOException("Unsafe entry name: $name")
                }
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("Unsafe entry name: $name")
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > MAXIMUM_ENTRY_BYTES || total > MAXIMUM_TOTAL_BYTES) throw IOException("Package too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
        if (!File(root, NanquimManifest.MANIFEST_FILE).isFile) throw IOException("No manifest.json")
    }

    private const val BUFFER_BYTES = 64 * 1024
}
