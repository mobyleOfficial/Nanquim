package com.mobyle.nanquim.document

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.withTextModels
import com.mobyle.nanquim.engine.ColorProfiles
import com.mobyle.nanquim.engine.DocumentFolder
import com.mobyle.nanquim.engine.SurfaceFolder
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.AnimationJson
import com.mobyle.nanquim.formats.nanquim.LayerUuids
import com.mobyle.nanquim.formats.nanquim.ManifestColorProfile
import com.mobyle.nanquim.formats.nanquim.ManifestReadResult
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import com.mobyle.nanquim.formats.nanquim.SurfaceFormat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.util.UUID

data class LoadedArtwork(
    val id: String,
    val folder: File,
    val state: DocumentState,
    val info: DocumentInfo,
    val uuids: LayerUuids,
    val manifest: JsonObject,
)

enum class ArtworkFailure {
    Missing,
    RecoveryFailed,

    NeedsNewerApp,

    Unsupported,
    Invalid,
}

sealed interface ArtworkReadResult {
    data class Success(
        val artwork: LoadedArtwork,
    ) : ArtworkReadResult

    data class Failure(
        val failure: ArtworkFailure,
        val detail: String,
    ) : ArtworkReadResult
}

class ArtworkStorage(
    val root: File,
) {
    fun folderOf(id: String): File = File(root, id)

    fun newId(): String = UUID.randomUUID().toString()

    fun ids(): List<String> =
        root.listFiles { file -> file.isDirectory && !file.name.endsWith(STAGING_SUFFIX) && isArtworkFolder(file) }
            ?.map { it.name }
            .orEmpty()

    private fun isArtworkFolder(folder: File) =
        File(folder, NanquimManifest.MANIFEST_FILE).isFile || File(folder, "save.journal").isFile

    fun read(id: String): ArtworkReadResult {
        val folder = folderOf(id)
        if (!folder.isDirectory) return ArtworkReadResult.Failure(ArtworkFailure.Missing, id)
        DocumentFolder.recover(folder.path)?.let { error -> return ArtworkReadResult.Failure(ArtworkFailure.RecoveryFailed, error) }
        val manifestFile = File(folder, NanquimManifest.MANIFEST_FILE)
        if (!manifestFile.isFile || manifestFile.length() > MAXIMUM_MANIFEST_BYTES) {
            return ArtworkReadResult.Failure(ArtworkFailure.Invalid, "No readable manifest.json")
        }
        return when (val result = NanquimManifest.parse(manifestFile.readText())) {
            is ManifestReadResult.Success -> {
                val profile = result.colorProfile?.let { readProfile(folder, it) }
                val cmykProfile = result.cmykProfile?.let { readProfile(folder, it) }
                val info = result.info.copy(id = id, colorProfile = profile, cmykProfile = cmykProfile)
                val state = result.state.withTextModels(readTextModels(folder, result.textModelFiles)).let { read ->
                    readAnimation(folder, result.uuids)?.let { read.copy(animation = it) } ?: read
                }
                ArtworkReadResult.Success(LoadedArtwork(id, folder, state, info, result.uuids, result.manifest))
            }
            is ManifestReadResult.NeedsNewerApp -> ArtworkReadResult.Failure(ArtworkFailure.NeedsNewerApp, "minReaderVersion ${result.minReaderVersion}")
            is ManifestReadResult.Unsupported -> ArtworkReadResult.Failure(ArtworkFailure.Unsupported, result.reason)
            is ManifestReadResult.Invalid -> ArtworkReadResult.Failure(ArtworkFailure.Invalid, result.reason)
        }
    }

    private fun readAnimation(
        folder: File,
        uuids: LayerUuids,
    ): AnimationSettings? {
        val file = File(folder, AnimationJson.FILE)
        if (!file.isFile || file.length() > AnimationJson.MAXIMUM_BYTES) return null
        val idByUuid = uuids.ids.associateBy { uuids.uuidOf(it) }
        return AnimationJson.read(file.readText()) { uuid -> idByUuid[uuid] }
    }

    private fun readTextModels(
        folder: File,
        files: Map<Int, String>,
    ): Map<Int, JsonObject> =
        files
            .mapNotNull { (layerId, path) ->
                val file = File(folder, path)
                val readable = file.isFile && file.length() <= NanquimManifest.MAXIMUM_TEXT_MODEL_BYTES
                val model = if (readable) runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() else null
                if (model == null) Log.w(LOG_TAG, "Text model $path of ${folder.name} is unreadable; the layer stays raster")
                model?.let { layerId to it }
            }.toMap()

    private fun readProfile(
        folder: File,
        profile: ManifestColorProfile,
    ): DocumentColorProfile? {
        val file = File(folder, profile.file)
        val inside = file.canonicalPath.startsWith(folder.canonicalPath + File.separator)
        if (!inside || !file.isFile || file.length() > ColorProfiles.MAXIMUM_BYTES) {
            Log.w(LOG_TAG, "Color profile ${profile.file} of ${folder.name} is unreadable; showing it as sRGB")
            return null
        }
        return DocumentColorProfile(profile.name, file.readBytes())
    }

    fun delete(id: String) {
        folderOf(id).deleteRecursively()
    }

    fun duplicate(
        id: String,
        name: String,
        now: Instant,
    ): String? {
        val source = read(id) as? ArtworkReadResult.Success ?: return null
        val newId = newId()
        val staging = File(root, newId + STAGING_SUFFIX)
        staging.deleteRecursively()
        return try {
            source.artwork.folder.copyRecursively(staging, overwrite = true)
            val info = source.artwork.info.copy(id = newId, name = name, createdAt = now, modifiedAt = now)
            writeManifestAtomically(staging, withIdentity(source.artwork.manifest, info))
            if (!staging.renameTo(folderOf(newId))) throw IOException("Cannot move the copy into place")
            newId
        } catch (error: IOException) {
            staging.deleteRecursively()
            null
        }
    }

    fun rename(
        id: String,
        name: String,
    ): Boolean {
        val artwork = (read(id) as? ArtworkReadResult.Success)?.artwork ?: return false
        return try {
            writeManifestAtomically(artwork.folder, withIdentity(artwork.manifest, artwork.info.copy(name = name)))
            true
        } catch (error: IOException) {
            false
        }
    }

    fun removeStagingFolders() {
        root.listFiles { file -> file.isDirectory && file.name.endsWith(STAGING_SUFFIX) }?.forEach { it.deleteRecursively() }
    }

    fun stagingFolder(finalId: String): File = File(root, finalId + STAGING_SUFFIX)

    fun publishStaging(finalId: String): Boolean = stagingFolder(finalId).renameTo(folderOf(finalId))

    companion object {
        const val STAGING_SUFFIX = ".staging"
        const val MAXIMUM_MANIFEST_BYTES = 64L * 1024 * 1024
        private const val LOG_TAG = "NanquimArtworks"

        fun withIdentity(
            manifest: JsonObject,
            info: DocumentInfo,
        ): JsonObject =
            JsonObject(
                manifest +
                    mapOf(
                        "id" to JsonPrimitive(info.id),
                        "name" to JsonPrimitive(info.name),
                        "createdAt" to JsonPrimitive(info.createdAt.toString()),
                        "modifiedAt" to JsonPrimitive(info.modifiedAt.toString()),
                    ),
            )

        fun writeManifestAtomically(
            folder: File,
            manifest: JsonObject,
        ) {
            val target = File(folder, NanquimManifest.MANIFEST_FILE)
            val staged = File(folder, NanquimManifest.MANIFEST_FILE + ".tmp")
            FileOutputStream(staged).use { stream ->
                stream.write(NanquimManifest.encode(manifest))
                stream.fd.sync()
            }
            if (!staged.renameTo(target)) throw IOException("Cannot replace ${target.path}")
        }

        fun surfaceFolders(
            state: DocumentState,
            uuids: LayerUuids,
        ): List<SurfaceFolder> =
            NanquimManifest.surfaces(state, uuids).map { surface ->
                SurfaceFolder(
                    surface.id,
                    surface.folder,
                    isMask = surface.format == SurfaceFormat.R8,
                    isSelection = surface.format == SurfaceFormat.SelectionR8,
                )
            }
    }
}
