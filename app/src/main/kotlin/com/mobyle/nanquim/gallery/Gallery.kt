package com.mobyle.nanquim.gallery

import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.ArtworkReadResult
import com.mobyle.nanquim.document.ArtworkStorage
import com.mobyle.nanquim.document.DocumentPersistence
import com.mobyle.nanquim.document.LoadedArtwork
import com.mobyle.nanquim.document.OpenArtwork
import com.mobyle.nanquim.navigation.AppDestination
import com.mobyle.nanquim.navigation.AppNavigator
import com.mobyle.nanquim.telemetry.ArtworkOpenSource
import com.mobyle.nanquim.telemetry.Telemetry
import com.mobyle.nanquim.ui.transition.ArtworkTransitions
import com.mobyle.nanquim.ui.transition.canvasRectOnScreen
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.session.LayerNames
import com.mobyle.nanquim.engine.DocumentSaveResult
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import com.mobyle.nanquim.formats.nanquim.CanvasUnit
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings
import java.io.File
import java.time.Instant
import java.util.UUID

sealed interface GalleryItem {
    val id: String
    val name: String
    val modifiedAt: Long

    data class Artwork(
        val entity: ArtworkEntity,
    ) : GalleryItem {
        override val id get() = entity.id
        override val name get() = entity.name
        override val modifiedAt get() = entity.modifiedAt
    }

    data class Stack(
        val entity: StackEntity,
        val artworks: List<ArtworkEntity>,
    ) : GalleryItem {
        override val id get() = entity.id
        override val name get() = entity.name
        override val modifiedAt get() = artworks.maxOfOrNull { it.modifiedAt } ?: entity.createdAt
    }
}

enum class GallerySort(
    val id: String,
) {
    Date("date"),
    Name("name"),
    ;

    companion object {
        fun fromId(id: String): GallerySort? = entries.firstOrNull { it.id == id }
    }
}

enum class GalleryError {
    NotFound,
    NeedsNewerApp,
    Unsupported,
    Unreadable,
    WriteFailed,

    TooLarge,
}

interface GalleryNames {
    fun untitled(): String

    fun copyOf(name: String): String

    fun stack(): String
}

class GalleryException(
    val error: GalleryError,
    message: String,
) : Exception(message)

class Gallery(
    private val storage: ArtworkStorage,
    private val dao: GalleryDao,
    private val canvasSession: CanvasSession,
    private val navigator: AppNavigator,
    private val layerNames: LayerNames,
    private val preferences: SharedPreferences,
    private val names: GalleryNames,
    private val scope: CoroutineScope,
) {
    var sort: GallerySort by mutableStateOf(GallerySort.fromId(preferences.getString(KEY_SORT, null) ?: "") ?: GallerySort.Date)
        private set

    var searchQuery: String by mutableStateOf("")

    var recordTimelapse: () -> Boolean = { true }

    var telemetry: Telemetry = Telemetry.Disabled

    val transitions = ArtworkTransitions()

    var openStackId: String? by mutableStateOf(null)

    var selecting: Boolean by mutableStateOf(false)
        private set

    var selectedIds: Set<String> by mutableStateOf(emptySet())
        private set

    var previewId: String? by mutableStateOf(null)

    var busyCount: Int by mutableStateOf(0)
        private set

    val busy: Boolean get() = busyCount > 0

    suspend fun <T> whileBusy(work: suspend () -> T): T {
        busyCount++
        try {
            return work()
        } finally {
            busyCount--
        }
    }

    var thumbnailVersion: Int by mutableStateOf(0)
        private set

    var loaded: Boolean by mutableStateOf(false)
        private set

    val items: StateFlow<List<GalleryItem>> =
        combine(dao.observeArtworks(), dao.observeStacks()) { artworks, stacks ->
            val stacksById = stacks.associateBy { it.id }
            val inStacks = artworks.filter { it.stackId != null && it.stackId in stacksById }.groupBy { it.stackId!! }
            artworks.filter { it.stackId == null || it.stackId !in stacksById }.map { GalleryItem.Artwork(it) } +
                inStacks.mapNotNull { (stackId, members) ->
                    stacksById[stackId]?.let { GalleryItem.Stack(it, members.sortedByDescending(ArtworkEntity::modifiedAt)) }
                }
        }.onEach { loaded = true }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val presets: StateFlow<List<CanvasPreset>> =
        dao
            .observePresets()
            .map { presets -> presets.map(CanvasPreset::from) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun changeSort(order: GallerySort) {
        sort = order
        preferences.edit { putString(KEY_SORT, order.id) }
    }

    fun visibleItems(all: List<GalleryItem>): List<GalleryItem> {
        val level =
            openStackId?.let { stackId ->
                (all.firstOrNull { it.id == stackId } as? GalleryItem.Stack)?.artworks?.map { GalleryItem.Artwork(it) }
            } ?: all
        val query = searchQuery.trim()
        val filtered =
            if (query.isEmpty()) {
                level
            } else {
                level.filter { item ->
                    item.name.contains(query, ignoreCase = true) ||
                        (item is GalleryItem.Stack && item.artworks.any { it.name.contains(query, ignoreCase = true) })
                }
            }
        return when (sort) {
            GallerySort.Date -> filtered.sortedByDescending { it.modifiedAt }
            GallerySort.Name -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    fun copyName(name: String): String = names.copyOf(name)

    fun stackName(): String = names.stack()

    fun changeSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) selectedIds = emptySet()
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectedArtworkIds(): List<String> = expand(selectedIds)

    private fun expand(ids: Collection<String>): List<String> =
        ids.flatMap { id ->
            (items.value.firstOrNull { it.id == id } as? GalleryItem.Stack)?.artworks?.map { it.id } ?: listOf(id)
        }

    suspend fun sync() {
        val rows = dao.artworks().associateBy { it.id }
        val onDisk =
            withContext(Dispatchers.IO) {
                storage.removeStagingFolders()
                storage.ids()
            }
        for (id in onDisk) {
            if (id == hiddenArtworkId) continue
            val folderTime = withContext(Dispatchers.IO) { File(storage.folderOf(id), "manifest.json").lastModified() }
            val row = rows[id]
            if (row != null && row.modifiedAt >= folderTime - CLOCK_SLACK_MILLISECONDS && folderTime != 0L) continue
            val result = withContext(Dispatchers.IO) { storage.read(id) }
            if (result is ArtworkReadResult.Success) {
                dao.upsertArtwork(entityOf(result.artwork.id, result.artwork.state, result.artwork.info, row?.stackId))
            }
        }
        val gone = rows.keys - onDisk.toSet()
        if (gone.isNotEmpty()) dao.deleteArtworks(gone.toList())
        dao.tidyStacks()
        thumbnailVersion++
    }

    suspend fun onArtworkSaved(
        artwork: OpenArtwork,
        @Suppress("UNUSED_PARAMETER") result: DocumentSaveResult,
    ) {
        val state = canvasSession.document ?: return
        if (artwork.id == hiddenArtworkId) return
        val stackId = dao.artwork(artwork.id)?.stackId
        dao.upsertArtwork(entityOf(artwork.id, state, artwork.info, stackId))
        thumbnailVersion++
    }

    private fun entityOf(
        id: String,
        state: DocumentState,
        info: DocumentInfo,
        stackId: String?,
    ) = ArtworkEntity(id, info.name, state.widthPx, state.heightPx, info.createdAt.toEpochMilli(), info.modifiedAt.toEpochMilli(), stackId)

    suspend fun artworkName(id: String): String? = dao.artwork(id)?.name

    fun thumbnailFile(id: String): File = File(storage.folderOf(id), "thumbnail.png")

    suspend fun open(
        id: String,
        source: ArtworkOpenSource = ArtworkOpenSource.Gallery,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val loaded =
            when (val result = withContext(Dispatchers.IO) { storage.read(id) }) {
                is ArtworkReadResult.Success -> result.artwork
                is ArtworkReadResult.Failure -> {
                    val error = failureOf(result)
                    telemetry.artworkOpenFailed(error.name)
                    throw GalleryException(error, result.detail)
                }
            }
        val readMillis = SystemClock.elapsedRealtime() - startedAt
        closeCanvasAndWait()
        show(loaded)
        measureOpen(source, loaded.state, startedAt, readMillis)
    }

    suspend fun openFromOutside(id: String) {
        if (canvasSession.artwork?.id == id) {
            navigator.showCanvas()
            return
        }
        try {
            open(id, ArtworkOpenSource.Widget)
        } catch (_: GalleryException) {
            navigator.showGallery()
        }
    }

    private fun show(loaded: LoadedArtwork) {
        canvasSession.openArtwork(loaded)
        rememberOpen(loaded.id)
        changeSelecting(false)
        navigator.showCanvas()
    }

    private var openMeasurement: Job? = null

    private fun measureOpen(
        source: ArtworkOpenSource,
        state: DocumentState,
        startedAt: Long,
        readMillis: Long,
    ) {
        telemetry.artworkChanged(state)
        openMeasurement?.cancel()
        openMeasurement =
            scope.launch {
                val shown =
                    withTimeoutOrNull(OPEN_MEASUREMENT_TIMEOUT_MILLIS) {
                        merge(
                            snapshotFlow { canvasSession.documentShown }.filter { it },
                            canvasSession.engineFailure.filterNotNull().map { false },
                        ).first()
                    }
                if (shown == true) telemetry.artworkOpened(source, state, SystemClock.elapsedRealtime() - startedAt, readMillis)
            }
    }

    suspend fun newCanvas(
        widthPx: Int,
        heightPx: Int,
        name: String?,
        dpi: Double,
        unit: CanvasUnit,
        background: Background = Background(RgbaColor.WHITE),
        pixelFormat: PixelFormat = PixelFormat.Rgba8,
        show: Boolean = true,
        colorProfile: DocumentColorProfile? = null,
        cmykProfile: DocumentColorProfile? = null,
    ): String {
        val startedAt = SystemClock.elapsedRealtime()
        closeCanvasAndWait()
        if (!show && navigator.current == AppDestination.Canvas) navigator.showGallery()
        val info =
            DocumentInfo
                .new(name?.takeIf { it.isNotBlank() } ?: names.untitled(), Instant.now(), dpi, unit)
                .copy(timelapse = TimelapseSettings(enabled = recordTimelapse()), colorProfile = colorProfile, cmykProfile = cmykProfile)
        val folder = storage.folderOf(info.id)
        withContext(Dispatchers.IO) { folder.mkdirs() }
        val state = DocumentState(widthPx, heightPx, background, pixelFormat = pixelFormat).addRasterLayer(layerNames.layer(1)).first
        canvasSession.createArtwork(folder, state, info)
        if (show) dao.upsertArtwork(entityOf(info.id, state, info, openStackId)) else hiddenArtworkId = info.id
        changeSelecting(false)
        if (show) {
            rememberOpen(info.id)
            navigator.showCanvas()
            measureOpen(ArtworkOpenSource.New, state, startedAt, readMillis = 0)
        }
        return info.id
    }

    suspend fun closeHiddenCanvas() {
        try {
            closeCanvasAndWait()
        } finally {
            hiddenArtworkId = null
        }
        sync()
    }

    private var hiddenArtworkId: String? = null

    suspend fun closeCanvas() {
        val startedAt = SystemClock.elapsedRealtime()
        val state = canvasSession.document
        canvasSession.artwork?.let { open ->
            val picture = canvasSession.compositePicture(CLOSE_PICTURE_SIDE)?.asImageBitmap()
            transitions.prepareClose(open.id, canvasSession.canvasRectOnScreen(), picture)
        }
        val saved = closeCanvasAndWait()
        navigator.showGallery()
        if (saved != null) telemetry.artworkClosed(state, SystemClock.elapsedRealtime() - startedAt, saved)
    }

    private suspend fun closeCanvasAndWait(): Boolean? {
        if (canvasSession.documentSession == null) return null
        openMeasurement?.cancel()
        val saved =
            suspendCancellableCoroutine { continuation ->
                canvasSession.closeArtwork { saved -> continuation.resumeWith(Result.success(saved)) }
            }
        telemetry.artworkChanged(null)
        rememberOpen(null)
        sync()
        return saved
    }

    suspend fun restoreLastOpen(): Boolean {
        val id = preferences.getString(KEY_OPEN_ARTWORK, null) ?: return false
        val startedAt = SystemClock.elapsedRealtime()
        val result = withContext(Dispatchers.IO) { storage.read(id) }
        val readMillis = SystemClock.elapsedRealtime() - startedAt
        if (result !is ArtworkReadResult.Success) {
            rememberOpen(null)
            return false
        }
        val thumbnail = thumbnailFile(id)
        val picture = withContext(Dispatchers.IO) { if (thumbnail.isFile) BitmapFactory.decodeFile(thumbnail.path) else null }
        if (canvasSession.documentSession != null) return false
        picture?.let { transitions.prepareRestore(id, it.asImageBitmap()) }
        show(result.artwork)
        measureOpen(ArtworkOpenSource.Restore, result.artwork.state, startedAt, readMillis)
        return true
    }

    private fun rememberOpen(id: String?) = preferences.edit(commit = true) { if (id == null) remove(KEY_OPEN_ARTWORK) else putString(KEY_OPEN_ARTWORK, id) }

    private fun requireClosed(ids: Collection<String>): List<String> {
        val open = canvasSession.artwork?.id
        return ids.filter { it != open }
    }

    suspend fun delete(ids: Collection<String>) = whileBusy { deleteNow(ids) }

    private suspend fun deleteNow(ids: Collection<String>) {
        val artworkIds = requireClosed(expand(ids))
        withContext(Dispatchers.IO) { artworkIds.forEach(storage::delete) }
        dao.deleteArtworks(artworkIds)
        dao.tidyStacks()
        changeSelecting(false)
    }

    suspend fun duplicate(
        ids: Collection<String>,
        copyName: (String) -> String,
    ): List<String> = whileBusy { duplicateNow(ids, copyName) }

    private suspend fun duplicateNow(
        ids: Collection<String>,
        copyName: (String) -> String,
    ): List<String> {
        val created = mutableListOf<String>()
        for (id in expand(ids)) {
            val row = dao.artwork(id) ?: continue
            if (id == canvasSession.artwork?.id) saveAndWait(canvasSession.persistence)
            val newId = withContext(Dispatchers.IO) { storage.duplicate(id, copyName(row.name), Instant.now()) } ?: throw GalleryException(GalleryError.WriteFailed, id)
            val now = System.currentTimeMillis()
            dao.upsertArtwork(row.copy(id = newId, name = copyName(row.name), createdAt = now, modifiedAt = now))
            created += newId
        }
        changeSelecting(false)
        thumbnailVersion++
        return created
    }

    private suspend fun saveAndWait(persistence: DocumentPersistence) =
        suspendCancellableCoroutine { continuation ->
            persistence.saveNow(withThumbnail = true) { continuation.resumeWith(Result.success(Unit)) }
        }

    suspend fun rename(
        id: String,
        name: String,
    ) {
        val trimmed = name.trim().ifEmpty { return }
        val open = canvasSession.artwork
        if (open?.id == id) {
            open.info = open.info.copy(name = trimmed)
            canvasSession.persistence.documentChanged()
        } else if (!withContext(Dispatchers.IO) { storage.rename(id, trimmed) }) {
            throw GalleryException(GalleryError.WriteFailed, id)
        }
        dao.artwork(id)?.let { dao.upsertArtwork(it.copy(name = trimmed)) }
    }

    suspend fun stack(
        ids: Collection<String>,
        name: String,
    ): String? {
        val artworkIds = expand(ids).distinct()
        if (artworkIds.size < 2) return null
        val stackId = UUID.randomUUID().toString()
        dao.upsertStack(StackEntity(stackId, name, System.currentTimeMillis()))
        dao.setStack(artworkIds, stackId)
        dao.tidyStacks()
        changeSelecting(false)
        return stackId
    }

    suspend fun moveToStack(
        ids: Collection<String>,
        stackId: String?,
    ) {
        if (stackId != null && dao.stack(stackId) == null) throw GalleryException(GalleryError.NotFound, stackId)
        dao.setStack(expand(ids), stackId)
        dao.tidyStacks()
        if (openStackId != null && dao.stack(openStackId!!) == null) openStackId = null
        changeSelecting(false)
    }

    suspend fun renameStack(
        stackId: String,
        name: String,
    ) {
        val trimmed = name.trim().ifEmpty { return }
        dao.renameStack(stackId, trimmed)
    }

    suspend fun seedPresets(
        screenWidthPx: Int,
        screenHeightPx: Int,
    ) {
        if (dao.presetCount() > 0 || preferences.getBoolean(KEY_PRESETS_SEEDED, false)) return
        BuiltInCanvasPresets.all(screenWidthPx, screenHeightPx).forEachIndexed { index, (key, size) ->
            dao.upsertPreset(CanvasPresetEntity(UUID.randomUUID().toString(), null, key, size.width, size.height, size.unit.id, size.dpi, index))
        }
        preferences.edit { putBoolean(KEY_PRESETS_SEEDED, true) }
    }

    suspend fun savePreset(
        id: String?,
        name: String,
        size: CanvasSize,
    ) {
        val existing = id?.let { presetId -> presets.value.firstOrNull { it.id == presetId } }
        val position =
            if (existing != null) {
                presets.value.indexOf(existing)
            } else {
                dao.lastPresetPosition() + 1
            }
        dao.upsertPreset(CanvasPresetEntity(existing?.id ?: UUID.randomUUID().toString(), name, null, size.width, size.height, size.unit.id, size.dpi, position))
    }

    suspend fun deletePreset(id: String) = dao.deletePreset(id)

    companion object {
        const val PREFERENCES_NAME = "gallery"
        private const val KEY_SORT = "sort"
        private const val KEY_OPEN_ARTWORK = "openArtwork"
        private const val KEY_PRESETS_SEEDED = "presetsSeeded"

        private const val OPEN_MEASUREMENT_TIMEOUT_MILLIS = 120_000L

        private const val CLOCK_SLACK_MILLISECONDS = 2_000L
    }
}

private const val CLOSE_PICTURE_SIDE = 1024
