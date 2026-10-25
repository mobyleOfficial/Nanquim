package com.mobyle.nanquim.document

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.textModels
import com.mobyle.nanquim.engine.DocumentSaveResult
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.formats.NanquimFileType
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.AnimationJson
import com.mobyle.nanquim.formats.nanquim.LayerUuids
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import java.io.File
import java.time.Instant

class OpenArtwork(
    val id: String,
    val folder: File,
    info: DocumentInfo,
    var uuids: LayerUuids,
    var manifest: JsonObject?,
) {
    var info: DocumentInfo by mutableStateOf(info)

    var savedColorProfile: DocumentColorProfile? = info.colorProfile.takeIf { manifest != null }

    var savedCmykProfile: DocumentColorProfile? = info.cmykProfile.takeIf { manifest != null }
}

enum class SaveStatus {
    Saved,

    Pending,
    Saving,
    Failed,
}

class DocumentPersistence(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val onSaved: (OpenArtwork, DocumentSaveResult) -> Unit = { _, _ -> },
) {
    var status: SaveStatus by mutableStateOf(SaveStatus.Saved)
        private set

    var lastError: String? by mutableStateOf(null)
        private set

    private var artwork: OpenArtwork? = null
    private var stateProvider: () -> DocumentState? = { null }
    private var autosaveJob: Job? = null
    private var saving = false
    private var saveAgain = false
    private var thumbnailAgain = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private var pauses = 0
    private var savePendingAfterPause = false
    private var thumbnailPendingAfterPause = false

    val isAttached: Boolean get() = artwork != null

    fun attach(
        artwork: OpenArtwork,
        state: () -> DocumentState?,
    ) {
        autosaveJob?.cancel()
        this.artwork = artwork
        stateProvider = state
        status = if (artwork.manifest == null) SaveStatus.Pending else SaveStatus.Saved
        lastError = null
    }

    fun detach() {
        autosaveJob?.cancel()
        artwork = null
        stateProvider = { null }
        status = SaveStatus.Saved
    }

    fun pause() {
        autosaveJob?.cancel()
        pauses++
    }

    fun resume() {
        if (pauses == 0) return
        pauses--
        if (pauses == 0 && savePendingAfterPause) {
            savePendingAfterPause = false
            val thumbnail = thumbnailPendingAfterPause
            thumbnailPendingAfterPause = false
            saveNow(withThumbnail = thumbnail)
        }
    }

    fun documentChanged() {
        if (artwork == null) return
        if (status != SaveStatus.Saving) status = SaveStatus.Pending
        autosaveJob?.cancel()
        autosaveJob =
            scope.launch {
                delay(AUTOSAVE_DELAY_MILLISECONDS)
                saveNow(withThumbnail = false)
            }
    }

    fun saveNow(
        withThumbnail: Boolean,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        autosaveJob?.cancel()
        if (artwork == null) {
            onDone?.invoke(true)
            return
        }
        onDone?.let(waiting::add)
        if (pauses > 0) {
            savePendingAfterPause = true
            thumbnailPendingAfterPause = thumbnailPendingAfterPause || withThumbnail
            status = SaveStatus.Pending
            return
        }
        if (saving) {
            saveAgain = true
            thumbnailAgain = thumbnailAgain || withThumbnail
            return
        }
        saving = true
        status = SaveStatus.Saving
        if (withThumbnail) {
            engine.requestCompositeThumbnail(NanquimFileThumbnail.MAXIMUM_SIDE) { render ->
                if (render.error != null || render.width == 0) {
                    startSave(null)
                    return@requestCompositeThumbnail
                }
                scope.launch {
                    val png = withContext(Dispatchers.Default) { RgbaImages.toPng(render.width, render.height, render.pixels) }
                    startSave(png)
                }
            }
        } else {
            startSave(null)
        }
    }

    private fun startSave(thumbnailPng: ByteArray?) {
        val artwork = artwork
        val state = stateProvider()
        if (artwork == null || state == null) {
            finish(true)
            return
        }
        val uuids = artwork.uuids.assignedFor(state)
        artwork.uuids = uuids
        val info = artwork.info.copy(modifiedAt = Instant.now())
        val manifest = NanquimManifest.write(state, info, uuids, appVersion, artwork.manifest)
        val files = linkedMapOf(NanquimManifest.MANIFEST_FILE to NanquimManifest.encode(manifest))
        if (!File(artwork.folder, NanquimManifest.MIMETYPE_FILE).isFile) {
            files[NanquimManifest.MIMETYPE_FILE] = NanquimFileType.Document.mimeType.toByteArray(Charsets.US_ASCII)
        }
        thumbnailPng?.let { files[NanquimManifest.THUMBNAIL_FILE] = it }
        if (state.animation != AnimationSettings()) files[AnimationJson.FILE] = AnimationJson.write(state.animation, uuids::uuidOf)
        for ((layerId, model) in state.textModels()) {
            val uuid = uuids.uuidOf(layerId) ?: continue
            files[NanquimManifest.textModelFile(uuid)] = model.toString().toByteArray(Charsets.UTF_8)
        }
        val profile = info.colorProfile
        if (profile != null && profile != artwork.savedColorProfile) files[NanquimManifest.PROFILE_FILE] = profile.icc
        val cmykProfile = info.cmykProfile
        if (cmykProfile != null && cmykProfile != artwork.savedCmykProfile) files[NanquimManifest.CMYK_PROFILE_FILE] = cmykProfile.icc
        engine.saveDocument(ArtworkStorage.surfaceFolders(state, uuids), files) { result ->
            if (result.ok && this.artwork === artwork) {
                artwork.manifest = manifest
                artwork.info = info
                artwork.savedColorProfile = profile
                artwork.savedCmykProfile = cmykProfile
            }
            if (result.ok) onSaved(artwork, result)
            lastError = if (result.ok) null else result.error
            finish(result.ok)
        }
    }

    private fun finish(ok: Boolean) {
        saving = false
        if (saveAgain && artwork != null) {
            saveAgain = false
            val thumbnail = thumbnailAgain
            thumbnailAgain = false
            saveNow(withThumbnail = thumbnail)
            if (!ok) status = SaveStatus.Failed
            return
        }
        status = if (ok) SaveStatus.Saved else SaveStatus.Failed
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(ok) }
    }

    companion object {
        const val AUTOSAVE_DELAY_MILLISECONDS = 2_000L
    }
}

object NanquimFileThumbnail {
    const val MAXIMUM_SIDE = 512
}
