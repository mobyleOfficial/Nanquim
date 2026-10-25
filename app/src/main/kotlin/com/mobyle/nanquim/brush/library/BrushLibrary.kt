package com.mobyle.nanquim.brush.library

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.document.brush.BrushPackageIo
import com.mobyle.nanquim.document.brush.BrushResetPoints
import com.mobyle.nanquim.document.brush.InvalidBrushException
import java.io.File
import java.time.Instant
import java.util.UUID

enum class BrushTool(
    val key: String,
) {
    Paint("paint"),
    Erase("erase"),
    Smudge("smudge"),
}

data class BrushGroup(
    val id: String,
    val name: String?,
    val builtInKey: String?,
    val brushes: List<BrushEntryEntity>,
)

data class SliderState(
    val sizeSlider: Float,
    val opacitySlider: Float,
)

class BrushLibrary(
    private val database: BrushLibraryDatabase,
    private val builtIns: BuiltInBrushSource,
    private val storageDirectory: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.dao()
    private val mutex = Mutex()

    val groups: Flow<List<BrushGroup>> =
        combine(dao.observeGroups(), dao.observeEntries()) { groups, entries ->
            val byGroup = entries.groupBy { it.groupId }
            groups.map { BrushGroup(it.id, it.name, it.builtInKey, byGroup[it.id].orEmpty()) }
        }

    val recent: Flow<List<BrushEntryEntity>> =
        dao.observeEntries().map { entries ->
            entries.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(RECENT_COUNT)
        }

    suspend fun initialize() =
        locked {
            val library = builtIns.library()
            val seeded = dao.seededBuiltInBrushes().toSet()
            val groups = dao.groups().toMutableList()
            var nextGroupPosition = (groups.maxOfOrNull { it.position } ?: -1) + 1
            val newlySeeded = mutableListOf<SeededBuiltInBrushEntity>()
            for (builtInGroup in library.groups) {
                val missing = builtInGroup.brushes.filter { it !in seeded }
                if (missing.isEmpty()) continue
                val group =
                    dao.groupByKey(builtInGroup.key)
                        ?: BrushGroupEntity(newId(), null, builtInGroup.key, nextGroupPosition++).also { dao.upsertGroup(it) }
                var nextPosition = (dao.entriesOf(group.id).maxOfOrNull { it.position } ?: -1) + 1
                val entries =
                    missing.map { brushId ->
                        val path = builtIns.pathOf(builtInGroup.key, brushId)
                        val name = builtIns.readPackage(path).document.name
                        BrushEntryEntity(brushId, group.id, nextPosition++, name, builtInPath = path)
                    }
                dao.upsertEntries(entries)
                newlySeeded += missing.map(::SeededBuiltInBrushEntity)
            }
            dao.markSeeded(newlySeeded)
            for (tool in BrushTool.entries) {
                val default = library.defaults[tool.key] ?: continue
                if (dao.toolBrush(tool.key) == null && dao.entry(default) != null) {
                    dao.upsertToolBrush(ToolBrushEntity(tool.key, default))
                }
            }
        }

    suspend fun brushFor(tool: BrushTool): String? =
        io {
            dao.toolBrush(tool.key)?.takeIf { dao.entry(it) != null }
                ?: dao.groups().firstNotNullOfOrNull { dao.entriesOf(it.id).firstOrNull()?.brushId }
        }

    val toolBrushes: Flow<Map<BrushTool, String>> =
        combine(dao.observeToolBrushes(), dao.observeEntries()) { selections, _ ->
            selections.mapNotNull { selection ->
                BrushTool.entries.firstOrNull { it.key == selection.tool }?.let { it to selection.brushId }
            }.toMap()
        }

    suspend fun select(
        tool: BrushTool,
        brushId: String,
    ) = locked {
        val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
        dao.upsertEntry(entry.copy(lastUsedAt = clock()))
        dao.upsertToolBrush(ToolBrushEntity(tool.key, brushId))
    }

    suspend fun entry(brushId: String): BrushEntryEntity? = io { dao.entry(brushId) }

    suspend fun loadPackage(brushId: String): BrushPackage =
        io {
            val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
            val builtInPath = entry.builtInPath
            if (builtInPath != null && !entry.hasOverride) {
                builtIns.readPackage(builtInPath)
            } else {
                BrushPackageIo.readDirectory(directoryOf(brushId))
            }
        }

    suspend fun save(brushPackage: BrushPackage) =
        locked {
            val brushId = brushPackage.document.id
            val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
            val document = brushPackage.document.with("modifiedAt", Instant.ofEpochMilli(clock()).toString())
            BrushPackageIo.writeDirectory(brushPackage.withDocument(document), directoryOf(brushId))
            dao.upsertEntry(entry.copy(name = document.name, hasOverride = entry.builtInPath != null))
        }

    suspend fun reset(brushId: String): Boolean {
        val restored = BrushResetPoints.restoreLatest(loadPackage(brushId).document)
        if (restored != null) {
            save(loadPackage(brushId).withDocument(restored))
            return true
        }
        return resetToOriginal(brushId)
    }

    suspend fun builtInOriginal(brushId: String): BrushPackage? = io { dao.entry(brushId)?.builtInPath?.let(builtIns::readPackage) }

    private suspend fun resetToOriginal(brushId: String): Boolean =
        locked {
            val entry = dao.entry(brushId) ?: return@locked false
            val builtInPath = entry.builtInPath ?: return@locked false
            directoryOf(brushId).deleteRecursively()
            val name = builtIns.readPackage(builtInPath).document.name
            dao.upsertEntry(entry.copy(name = name, hasOverride = false))
            true
        }

    suspend fun duplicate(brushId: String): String {
        val original = loadPackage(brushId)
        return locked {
            val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
            val copyId = newId()
            val now = Instant.ofEpochMilli(clock()).toString()
            val document =
                original.document
                    .with("id", copyId)
                    .with("createdAt", now)
                    .with("modifiedAt", now)
            BrushPackageIo.writeDirectory(original.withDocument(document), directoryOf(copyId))
            insertAt(entry.groupId, entry.position + 1, BrushEntryEntity(copyId, entry.groupId, 0, document.name, builtInPath = null))
            copyId
        }
    }

    suspend fun delete(brushId: String) =
        locked {
            dao.entry(brushId) ?: return@locked
            directoryOf(brushId).deleteRecursively()
            dao.deleteEntry(brushId)
            dao.deleteToolStates(brushId)
            dao.deleteSliderPresets(brushId)
            dao.clearToolBrush(brushId)
        }

    suspend fun setPinned(
        brushId: String,
        pinned: Boolean,
    ) = locked {
        val entry = dao.entry(brushId) ?: return@locked
        dao.upsertEntry(entry.copy(pinned = pinned))
    }

    suspend fun move(
        brushId: String,
        groupId: String,
        position: Int,
    ) = locked {
        val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
        dao.group(groupId) ?: throw NoSuchElementException("no group $groupId")
        dao.deleteEntry(brushId)
        renumber(entry.groupId)
        insertAt(groupId, position, entry)
    }

    suspend fun createGroup(name: String): String =
        locked {
            val id = newId()
            val groups = dao.groups()
            dao.upsertGroups(groups.map { it.copy(position = it.position + 1) })
            dao.upsertGroup(BrushGroupEntity(id, name, null, 0))
            id
        }

    suspend fun renameGroup(
        groupId: String,
        name: String,
    ) = locked {
        val group = dao.group(groupId) ?: return@locked
        dao.upsertGroup(group.copy(name = name))
    }

    suspend fun moveGroup(
        groupId: String,
        position: Int,
    ) = locked {
        val groups = dao.groups().toMutableList()
        val group = groups.firstOrNull { it.id == groupId } ?: return@locked
        groups.remove(group)
        groups.add(position.coerceIn(0, groups.size), group)
        dao.upsertGroups(groups.mapIndexed { index, it -> it.copy(position = index) })
    }

    suspend fun deleteGroup(groupId: String) {
        val brushes = io { dao.entriesOf(groupId) }
        brushes.forEach { delete(it.brushId) }
        locked { dao.deleteGroup(groupId) }
    }

    suspend fun sliderState(
        brushId: String,
        tool: BrushTool,
    ): SliderState =
        io {
            dao.toolState(brushId, tool.key)?.let { SliderState(it.sizeSlider, it.opacitySlider) }
                ?: defaultSliders(loadPackageUnlocked(brushId).document)
        }

    suspend fun saveSliderState(
        brushId: String,
        tool: BrushTool,
        state: SliderState,
    ) = io { dao.upsertToolState(BrushToolStateEntity(brushId, tool.key, state.sizeSlider, state.opacitySlider)) }

    suspend fun sliderPresets(
        brushId: String,
        tool: BrushTool,
        slider: String,
    ): List<Float> = io { dao.sliderPresets(brushId, tool.key, slider).map { it.value } }

    suspend fun saveSliderPresets(
        brushId: String,
        tool: BrushTool,
        slider: String,
        values: List<Float>,
    ) = io {
        val presets =
            values.take(MAXIMUM_SLIDER_PRESETS).mapIndexed { index, value ->
                SliderPresetEntity(brushId, tool.key, slider, index, value.coerceIn(0f, 1f))
            }
        dao.replaceSliderPresets(brushId, tool.key, slider, presets)
    }

    suspend fun addBrush(
        brushPackage: BrushPackage,
        groupId: String?,
    ): String =
        locked {
            val group =
                groupId?.let { dao.group(it) }
                    ?: dao.groups().firstOrNull { it.builtInKey == IMPORTED_GROUP_KEY }
                    ?: createGroupUnlocked(null, IMPORTED_GROUP_KEY)
            addUserBrush(brushPackage, group.id)
        }

    suspend fun importBrush(bytes: ByteArray): String {
        val brushPackage = io { BrushPackageIo.readBrushArchive(bytes) }
        return locked {
            val group = dao.groups().firstOrNull { it.builtInKey == IMPORTED_GROUP_KEY } ?: createGroupUnlocked(null, IMPORTED_GROUP_KEY)
            addUserBrush(brushPackage, group.id)
        }
    }

    suspend fun importBrushSet(bytes: ByteArray): String {
        val set = io { BrushPackageIo.readBrushSet(bytes) }
        if (set.brushes.isEmpty()) throw InvalidBrushException("the brush set is empty")
        return locked {
            val group = createGroupUnlocked(set.name.ifBlank { null } ?: IMPORTED_GROUP_FALLBACK_NAME, null)
            set.brushes.forEach { addUserBrush(it, group.id) }
            group.id
        }
    }

    suspend fun importConverted(
        groupName: String?,
        brushes: List<BrushPackage>,
    ): String {
        if (brushes.isEmpty()) throw InvalidBrushException("the file holds no brush")
        return locked {
            val group =
                if (groupName == null) {
                    dao.groups().firstOrNull { it.builtInKey == IMPORTED_GROUP_KEY } ?: createGroupUnlocked(null, IMPORTED_GROUP_KEY)
                } else {
                    createGroupUnlocked(groupName.ifBlank { IMPORTED_GROUP_FALLBACK_NAME }, null)
                }
            brushes.forEach { addUserBrush(it, group.id) }
            group.id
        }
    }

    suspend fun exportBrush(brushId: String): ByteArray = BrushPackageIo.writeBrushArchive(loadPackage(brushId))

    suspend fun exportGroup(
        groupId: String,
        name: String,
    ): ByteArray {
        val brushes = io { dao.entriesOf(groupId) }.map { loadPackage(it.brushId) }
        return BrushPackageIo.writeBrushSet(BrushPackageIo.BrushSet(name, brushes))
    }

    private suspend fun addUserBrush(
        brushPackage: BrushPackage,
        groupId: String,
    ): String {
        val id = brushPackage.document.id.takeIf { isSafeId(it) && dao.entry(it) == null } ?: newId()
        val document = brushPackage.document.with("id", id)
        BrushPackageIo.writeDirectory(brushPackage.withDocument(document), directoryOf(id))
        val position = (dao.entriesOf(groupId).maxOfOrNull { it.position } ?: -1) + 1
        dao.upsertEntry(BrushEntryEntity(id, groupId, position, document.name, builtInPath = null))
        return id
    }

    private suspend fun createGroupUnlocked(
        name: String?,
        builtInKey: String?,
    ): BrushGroupEntity {
        val groups = dao.groups()
        dao.upsertGroups(groups.map { it.copy(position = it.position + 1) })
        return BrushGroupEntity(newId(), name, builtInKey, 0).also { dao.upsertGroup(it) }
    }

    private suspend fun insertAt(
        groupId: String,
        position: Int,
        entry: BrushEntryEntity,
    ) {
        val entries = dao.entriesOf(groupId).toMutableList()
        entries.add(position.coerceIn(0, entries.size), entry.copy(groupId = groupId))
        dao.upsertEntries(entries.mapIndexed { index, it -> it.copy(position = index) })
    }

    private suspend fun renumber(groupId: String) {
        dao.upsertEntries(dao.entriesOf(groupId).mapIndexed { index, it -> it.copy(position = index) })
    }

    private suspend fun loadPackageUnlocked(brushId: String): BrushPackage {
        val entry = dao.entry(brushId) ?: throw NoSuchElementException("no brush $brushId")
        val builtInPath = entry.builtInPath
        return if (builtInPath != null && !entry.hasOverride) {
            builtIns.readPackage(builtInPath)
        } else {
            BrushPackageIo.readDirectory(directoryOf(brushId))
        }
    }

    private fun directoryOf(brushId: String): File {
        require(isSafeId(brushId)) { "invalid brush id $brushId" }
        return File(storageDirectory, brushId)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(ioDispatcher) { block() }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(ioDispatcher) { mutex.withLock { block() } }

    companion object {
        const val RECENT_COUNT = 12
        const val MAXIMUM_SLIDER_PRESETS = 5
        const val IMPORTED_GROUP_KEY = "imported"
        private const val IMPORTED_GROUP_FALLBACK_NAME = "Imported"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]+$")

        fun isSafeId(id: String) = SAFE_ID.matches(id)

        private fun newId() = UUID.randomUUID().toString()

        fun defaultSliders(document: BrushDocument) =
            SliderState(
                sizeSlider = (document.number("defaults.size") ?: DEFAULT_SIZE_SLIDER).toFloat().coerceIn(0f, 1f),
                opacitySlider = (document.number("defaults.opacity") ?: 1.0).toFloat().coerceIn(0f, 1f),
            )

        private const val DEFAULT_SIZE_SLIDER = 0.3
    }
}
