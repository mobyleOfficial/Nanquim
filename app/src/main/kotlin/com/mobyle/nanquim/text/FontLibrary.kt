package com.mobyle.nanquim.text

import android.content.res.AssetManager
import android.graphics.fonts.SystemFonts
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.formats.font.ByteArrayFontSource
import com.mobyle.nanquim.formats.font.FontByteSource
import com.mobyle.nanquim.formats.font.FontFaceInfo
import com.mobyle.nanquim.formats.font.FontInfoReader
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

sealed interface FontSource {
    data object Bundled : FontSource

    data object System : FontSource

    data object Imported : FontSource

    data object Document : FontSource
}

data class FontEntry(
    val info: FontFaceInfo,
    val source: FontSource,
    val file: File,
) {
    val id: String get() = info.postScriptName
}

enum class FontImportFailure { NotAFont, TooLarge, Unreadable }

sealed interface FontImportResult {
    data class Imported(
        val fonts: List<FontEntry>,
    ) : FontImportResult

    data class Failed(
        val reason: FontImportFailure,
    ) : FontImportResult
}

class FontLibrary(
    private val assets: AssetManager,
    private val bundledDirectory: File,
    private val importedDirectory: File,
    private val engine: Engine,
) {
    var fonts: List<FontEntry> by mutableStateOf(emptyList())
        private set

    private val loaded = CompletableDeferred<Unit>()
    private var documentFonts: List<FontEntry> = emptyList()

    var fallbackIds: List<String> = BUNDLED_FALLBACKS
        private set

    fun find(id: String): FontEntry? = fonts.firstOrNull { it.id == id } ?: documentFonts.firstOrNull { it.id == id }

    suspend fun awaitLoaded() = loaded.await()

    suspend fun load() {
        val (bundled, imported, system) =
            withContext(Dispatchers.IO) {
                Triple(readBundled(), readFolder(importedDirectory, FontSource.Imported), readSystem())
            }
        publish(bundled + imported + system)
        val cjk =
            system
                .filter { it.id.contains("CJK", ignoreCase = true) && !it.info.italic }
                .minWithOrNull(
                    compareBy<FontEntry>(
                        {
                            when {
                                it.info.family.contains("Sans", ignoreCase = true) -> 0
                                !it.info.family.contains("Serif", ignoreCase = true) -> 1
                                else -> 2
                            }
                        },
                        { kotlin.math.abs(it.info.weight - 400) },
                        { it.id },
                    ),
                )
        fallbackIds = BUNDLED_FALLBACKS + listOfNotNull(cjk?.id)
        engine.setFontFallbacks(fallbackIds)
        loaded.complete(Unit)
    }

    suspend fun import(bytes: ByteArray): FontImportResult {
        if (bytes.size > MAXIMUM_FONT_BYTES) return FontImportResult.Failed(FontImportFailure.TooLarge)
        val faces = FontInfoReader.readAll(ByteArrayFontSource(bytes))
        if (faces.isEmpty()) return FontImportResult.Failed(FontImportFailure.NotAFont)
        val entries =
            withContext(Dispatchers.IO) {
                runCatching {
                    importedDirectory.mkdirs()
                    val digest =
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                            .take(24)
                    val extension = if (FontInfoReader.faceCount(ByteArrayFontSource(bytes)) > 1) "ttc" else "ttf"
                    val file = File(importedDirectory, "$digest.$extension")
                    if (!file.isFile) file.writeBytes(bytes)
                    faces.map { FontEntry(it, FontSource.Imported, file) }
                }.getOrNull()
            } ?: return FontImportResult.Failed(FontImportFailure.Unreadable)
        publish(fonts.filterNot { existing -> entries.any { it.id == existing.id && existing.source == FontSource.Imported } } + entries)
        return FontImportResult.Imported(entries)
    }

    suspend fun delete(id: String): Boolean {
        val entry = fonts.firstOrNull { it.id == id && it.source == FontSource.Imported } ?: return false
        val remaining = fonts - entry
        if (remaining.none { it.file == entry.file }) withContext(Dispatchers.IO) { entry.file.delete() }
        publish(remaining)
        return true
    }

    suspend fun useDocumentFonts(folder: File): List<String> {
        val embedded = withContext(Dispatchers.IO) { readFolder(File(folder, DOCUMENT_FONTS_FOLDER), FontSource.Document) }
        documentFonts = embedded.filter { entry -> fonts.none { it.id == entry.id } }
        documentFonts.forEach(::register)
        return documentFonts.map { it.id }
    }

    fun embeddableFiles(fontIds: Collection<String>): Map<String, File> =
        fontIds
            .mapNotNull(::find)
            .filter { it.source != FontSource.Bundled && it.info.embeddable }
            .associate { "$DOCUMENT_FONTS_FOLDER${it.file.name}" to it.file }

    private fun publish(entries: List<FontEntry>) {
        val ranked = entries.sortedBy { SOURCE_RANK.indexOf(it.source) }.distinctBy { it.id }
        fonts = ranked.sortedWith(compareBy({ it.info.family.lowercase() }, { it.info.weight }, { it.info.italic }))
        fonts.forEach(::register)
    }

    private fun register(entry: FontEntry) = engine.registerFont(entry.id, path = entry.file.absolutePath, faceIndex = entry.info.faceIndex)

    private fun readBundled(): List<FontEntry> {
        bundledDirectory.mkdirs()
        val names = assets.list(BUNDLED_ASSET_FOLDER).orEmpty().filter { it.endsWith(".ttf") || it.endsWith(".otf") }
        return names.flatMap { name ->
            val file = File(bundledDirectory, name)
            runCatching {
                val bytes = assets.open("$BUNDLED_ASSET_FOLDER/$name").use { it.readBytes() }
                if (!file.isFile || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
                FontInfoReader.readAll(ByteArrayFontSource(bytes)).map { FontEntry(it, FontSource.Bundled, file) }
            }.getOrElse {
                Log.w(TAG, "Bundled font $name is unreadable", it)
                emptyList()
            }
        }
    }

    private fun readFolder(
        folder: File,
        source: FontSource,
    ): List<FontEntry> =
        folder
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .flatMap { file -> readFile(file, source, null) }

    private fun readSystem(): List<FontEntry> =
        SystemFonts.getAvailableFonts().mapNotNull { font -> font.file?.let { it to font.ttcIndex } }.distinct().flatMap { (file, index) ->
            readFile(file, FontSource.System, index)
        }

    private fun readFile(
        file: File,
        source: FontSource,
        faceIndex: Int?,
    ): List<FontEntry> =
        runCatching {
            RandomAccessFile(file, "r").use { random ->
                val bytes = RandomFontSource(random)
                val faces = faceIndex?.let { listOfNotNull(FontInfoReader.read(bytes, it)) } ?: FontInfoReader.readAll(bytes)
                faces.map { FontEntry(it, source, file) }
            }
        }.getOrElse { emptyList() }

    private class RandomFontSource(
        private val file: RandomAccessFile,
    ) : FontByteSource {
        override val size: Long = file.length()

        override fun read(
            offset: Long,
            length: Int,
        ): ByteArray? {
            if (offset < 0 || length < 0 || offset + length > size) return null
            return ByteArray(length).also {
                file.seek(offset)
                file.readFully(it)
            }
        }
    }

    companion object {
        const val BUNDLED_ASSET_FOLDER = "fonts"
        const val DOCUMENT_FONTS_FOLDER = "fonts/"

        const val DEFAULT_FONT_ID = "Inter-Regular"

        const val MAXIMUM_FONT_BYTES = 64 * 1024 * 1024

        private val BUNDLED_FALLBACKS = listOf("NotoSans-Regular", "NotoSansArabic-Regular", "NotoSansHebrew-Regular")
        private val SOURCE_RANK = listOf(FontSource.Bundled, FontSource.Imported, FontSource.System, FontSource.Document)
        private const val TAG = "NanquimFonts"
    }
}
