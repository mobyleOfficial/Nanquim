package com.mobyle.nanquim.document.brush

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BrushPackage(
    val document: BrushDocument,
    files: Map<String, ByteArray> = emptyMap(),
) {
    val files: Map<String, ByteArray> = files.toMap()

    init {
        this.files.keys.forEach(::requireValidFileName)
        require(BRUSH_JSON !in this.files) { "brush.json is the document, not a file" }
    }

    val shapeTextureBytes: ByteArray? get() = document.shapeTexture?.let(files::get)
    val grainTextureBytes: ByteArray? get() = document.grainTexture?.let(files::get)

    fun withDocument(document: BrushDocument) = BrushPackage(document, files)

    fun withFile(
        name: String,
        bytes: ByteArray?,
    ): BrushPackage = BrushPackage(document, if (bytes == null) files - name else files + (name to bytes))

    override fun equals(other: Any?): Boolean =
        other is BrushPackage &&
            other.document == document &&
            other.files.keys == files.keys &&
            files.all { (name, bytes) -> other.files.getValue(name).contentEquals(bytes) }

    override fun hashCode(): Int = document.hashCode() * 31 + files.keys.hashCode()

    companion object {
        const val BRUSH_JSON = "brush.json"

        private val FILE_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)?$")

        fun requireValidFileName(name: String) {
            require(FILE_NAME.matches(name) && ".." !in name) { "Invalid file name in brush package: $name" }
        }
    }
}

object BrushPackageIo {
    const val BRUSHSET_JSON = "brushset.json"
    const val BRUSHSET_FORMAT = 1

    private const val MAXIMUM_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAXIMUM_ARCHIVE_BYTES = 256L * 1024 * 1024
    private const val MAXIMUM_ENTRIES = 4096

    fun readDirectory(directory: File): BrushPackage {
        val documentFile = File(directory, BrushPackage.BRUSH_JSON)
        if (!documentFile.isFile) throw InvalidBrushException("no brush.json in ${directory.name}")
        val document = BrushDocument.parse(documentFile.readText())
        val files =
            directory
                .walkTopDown()
                .filter { it.isFile && it != documentFile }
                .associate { file -> file.relativeTo(directory).invariantSeparatorsPath to file.readBytes() }
        return BrushPackage(document, files)
    }

    fun writeDirectory(
        brushPackage: BrushPackage,
        directory: File,
    ) {
        val parent = directory.absoluteFile.parentFile ?: throw IOException("no parent folder for $directory")
        parent.mkdirs()
        val staging = File(parent, ".${directory.name}.writing")
        val previous = File(parent, ".${directory.name}.previous")
        staging.deleteRecursively()
        previous.deleteRecursively()
        staging.mkdirs()
        File(staging, BrushPackage.BRUSH_JSON).writeText(brushPackage.document.toJsonText())
        for ((name, bytes) in brushPackage.files) {
            File(staging, name).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        if (directory.exists() && !directory.renameTo(previous)) throw IOException("cannot move $directory aside")
        if (!staging.renameTo(directory)) {
            previous.renameTo(directory)
            throw IOException("cannot replace $directory")
        }
        previous.deleteRecursively()
    }

    fun writeBrushArchive(brushPackage: BrushPackage): ByteArray =
        zip(packageEntries(brushPackage, prefix = ""))

    fun readBrushArchive(bytes: ByteArray): BrushPackage = packageFrom(unzip(bytes), prefix = "")

    data class BrushSet(
        val name: String,
        val brushes: List<BrushPackage>,
    )

    fun writeBrushSet(brushSet: BrushSet): ByteArray {
        val index =
            buildJsonObject {
                put("format", BRUSHSET_FORMAT)
                put("name", brushSet.name)
                put("brushes", JsonArray(brushSet.brushes.map { JsonPrimitive(it.document.id) }))
            }
        val entries = mutableListOf(BRUSHSET_JSON to Json.encodeToString(JsonObject.serializer(), index).toByteArray())
        for (brush in brushSet.brushes) {
            entries += packageEntries(brush, prefix = "${requireFolderName(brush.document.id)}/")
        }
        return zip(entries)
    }

    fun readBrushSet(bytes: ByteArray): BrushSet {
        val entries = unzip(bytes)
        val indexBytes = entries[BRUSHSET_JSON] ?: throw InvalidBrushException("no brushset.json in the brush set")
        val index =
            try {
                Json.parseToJsonElement(indexBytes.decodeToString()) as? JsonObject
            } catch (exception: IllegalArgumentException) {
                throw InvalidBrushException("brushset.json is not valid JSON", exception)
            } ?: throw InvalidBrushException("brushset.json must be an object")
        if (index["format"]?.jsonPrimitive?.intOrNull != BRUSHSET_FORMAT) {
            throw InvalidBrushException("unsupported brush set format")
        }
        val name = index["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val ids =
            (index["brushes"] as? JsonArray)?.map { it.jsonPrimitive.content }
                ?: throw InvalidBrushException("brushset.json has no brush list")
        return BrushSet(name, ids.map { id -> packageFrom(entries, prefix = "${requireFolderName(id)}/") })
    }

    private fun requireFolderName(id: String): String {
        if (!Regex("^[A-Za-z0-9_-]+$").matches(id)) throw InvalidBrushException("invalid brush id: $id")
        return id
    }

    private fun packageEntries(
        brushPackage: BrushPackage,
        prefix: String,
    ): List<Pair<String, ByteArray>> =
        listOf(prefix + BrushPackage.BRUSH_JSON to brushPackage.document.toJsonText().toByteArray()) +
            brushPackage.files.entries.sortedBy { it.key }.map { (name, bytes) -> prefix + name to bytes }

    private fun packageFrom(
        entries: Map<String, ByteArray>,
        prefix: String,
    ): BrushPackage {
        val documentBytes =
            entries[prefix + BrushPackage.BRUSH_JSON] ?: throw InvalidBrushException("no ${prefix}brush.json in the archive")
        val document = BrushDocument.parse(documentBytes.decodeToString())
        val files =
            entries
                .filterKeys { it.startsWith(prefix) && it != prefix + BrushPackage.BRUSH_JSON }
                .mapKeys { (name, _) -> name.removePrefix(prefix) }
                .filterKeys { name -> prefix.isNotEmpty() || '/' !in name || name.startsWith("material/") }
        try {
            return BrushPackage(document, files)
        } catch (exception: IllegalArgumentException) {
            throw InvalidBrushException(exception.message ?: "invalid brush package", exception)
        }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entries.size >= MAXIMUM_ENTRIES) throw InvalidBrushException("too many files in the archive")
                    val name = entry.name
                    if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) {
                        throw InvalidBrushException("invalid file name in the archive: $name")
                    }
                    val content = readLimited(zip, MAXIMUM_ENTRY_BYTES)
                    total += content.size
                    if (total > MAXIMUM_ARCHIVE_BYTES) throw InvalidBrushException("archive too large")
                    entries[name] = content
                }
            }
        } catch (exception: IOException) {
            throw InvalidBrushException("not a valid archive: ${exception.message}", exception)
        }
        return entries
    }

    private fun readLimited(
        zip: ZipInputStream,
        limit: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw InvalidBrushException("a file in the archive is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
