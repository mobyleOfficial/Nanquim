package com.mobyle.nanquim.color

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteFile
import com.mobyle.nanquim.formats.palette.PaletteFormatException
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.File
import java.io.IOException
import java.util.UUID

data class PaletteEntry(
    val id: String,
    val palette: Palette,
)

class PaletteLibrary(
    private val folder: File,
    private val seeds: () -> List<Palette>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var entries: List<PaletteEntry> by mutableStateOf(emptyList())
        private set

    var defaultId: String? by mutableStateOf(null)
        private set

    val defaultPalette: PaletteEntry? get() = entries.firstOrNull { it.id == defaultId } ?: entries.firstOrNull()

    private val mutex = Mutex()

    fun entry(id: String): PaletteEntry? = entries.firstOrNull { it.id == id }

    suspend fun load() =
        mutex.withLock {
            val (loaded, default) = withContext(io) { readOrSeed() }
            entries = loaded
            defaultId = default
        }

    suspend fun create(
        name: String,
        columns: Int = Palette.DEFAULT_COLUMNS,
        slots: Int = NEW_PALETTE_SLOTS,
    ): PaletteEntry = add(PaletteEntry(newId(), Palette(name, List(slots.coerceIn(0, Palette.MAXIMUM_SWATCHES)) { null }, columns)))

    suspend fun add(entry: PaletteEntry): PaletteEntry {
        change { list, default ->
            writePalette(entry)
            (list + entry) to (default ?: entry.id)
        }
        return entry
    }

    suspend fun duplicate(
        id: String,
        name: String? = null,
    ): PaletteEntry {
        val source = existing(id)
        return add(PaletteEntry(newId(), source.palette.copy(name = name ?: source.palette.name)))
    }

    suspend fun rename(
        id: String,
        name: String,
    ) = update(id) { it.copy(name = name) }

    suspend fun setSwatch(
        id: String,
        index: Int,
        swatch: PaletteSwatch?,
    ) = update(id) { palette ->
        require(index in 0..palette.swatches.size && index < Palette.MAXIMUM_SWATCHES) { "No slot $index" }
        val swatches = palette.swatches.toMutableList()
        if (index == swatches.size) swatches += swatch else swatches[index] = swatch
        palette.copy(swatches = swatches)
    }

    suspend fun delete(id: String) {
        existing(id)
        change { list, default ->
            withContext(io) { File(folder, "$id.$EXTENSION").delete() }
            val rest = list.filterNot { it.id == id }
            rest to (if (default == id) rest.firstOrNull()?.id else default)
        }
    }

    suspend fun setDefault(id: String) {
        existing(id)
        change { list, _ -> list to id }
    }

    suspend fun move(
        id: String,
        index: Int,
    ) {
        val entry = existing(id)
        change { list, default ->
            val rest = list.filterNot { it.id == id }.toMutableList()
            rest.add(index.coerceIn(0, rest.size), entry)
            rest to default
        }
    }

    private fun existing(id: String): PaletteEntry = entry(id) ?: throw NoSuchElementException("No palette $id")

    private suspend fun update(
        id: String,
        transform: (Palette) -> Palette,
    ) {
        existing(id)
        change { list, default ->
            val changed = list.map { if (it.id == id) it.copy(palette = transform(it.palette)) else it }
            writePalette(changed.first { it.id == id })
            changed to default
        }
    }

    private suspend fun change(block: suspend (List<PaletteEntry>, String?) -> Pair<List<PaletteEntry>, String?>) =
        mutex.withLock {
            val (list, default) = block(entries, defaultId)
            withContext(io) { writeIndex(list.map { it.id }, default) }
            entries = list
            defaultId = default
        }

    private suspend fun writePalette(entry: PaletteEntry) =
        withContext(io) {
            atomicWrite(File(folder, "${entry.id}.$EXTENSION"), PaletteFile.write(entry.id, entry.palette))
        }

    private fun readOrSeed(): Pair<List<PaletteEntry>, String?> {
        val indexFile = File(folder, INDEX)
        val files = folder.listFiles { file -> file.extension == EXTENSION }.orEmpty()
        if (!indexFile.exists() && files.isEmpty()) {
            val seeded = seeds().map { PaletteEntry(newId(), it) }
            for (entry in seeded) atomicWrite(File(folder, "${entry.id}.$EXTENSION"), PaletteFile.write(entry.id, entry.palette))
            val default = seeded.firstOrNull()?.id
            writeIndex(seeded.map { it.id }, default)
            return seeded to default
        }
        val index = readIndex(indexFile)
        val byId =
            files
                .sortedBy { it.lastModified() }
                .mapNotNull { file ->
                    try {
                        PaletteEntry(file.nameWithoutExtension, PaletteFile.read(file.readText()).palette)
                    } catch (exception: PaletteFormatException) {
                        null
                    } catch (exception: IOException) {
                        null
                    }
                }.associateBy { it.id }
        val ordered = index.order.mapNotNull { byId[it] } + byId.values.filterNot { it.id in index.order }
        val default = index.default?.takeIf { id -> ordered.any { it.id == id } } ?: ordered.firstOrNull()?.id
        return ordered to default
    }

    private fun readIndex(file: File): StoredIndex =
        try {
            if (file.exists()) json.decodeFromString(StoredIndex.serializer(), file.readText()) else StoredIndex()
        } catch (exception: SerializationException) {
            StoredIndex()
        } catch (exception: IllegalArgumentException) {
            StoredIndex()
        }

    private fun writeIndex(
        order: List<String>,
        default: String?,
    ) = atomicWrite(File(folder, INDEX), json.encodeToString(StoredIndex.serializer(), StoredIndex(order, default)))

    private fun atomicWrite(
        file: File,
        text: String,
    ) {
        folder.mkdirs()
        val temporary = File(folder, "${file.name}.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("Cannot save ${file.name}")
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    @Serializable
    private data class StoredIndex(
        val order: List<String> = emptyList(),
        val default: String? = null,
    )

    companion object {
        const val EXTENSION = "nqpalette"
        const val INDEX = "index.json"

        const val NEW_PALETTE_SLOTS = 30

        private val json = Json { ignoreUnknownKeys = true }
    }
}
