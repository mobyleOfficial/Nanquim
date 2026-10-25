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
import com.mobyle.nanquim.engine.ColorProfileInfo
import com.mobyle.nanquim.engine.ColorProfileReadResult
import com.mobyle.nanquim.engine.ColorProfiles
import com.mobyle.nanquim.engine.ProfileColorSpace
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import java.io.File
import java.io.IOException
import java.util.UUID

data class ColorProfileEntry(
    val id: String,
    val name: String,
    val info: ColorProfileInfo,
    val builtIn: Boolean,
)

val ColorProfileEntry.isPrintProfile: Boolean
    get() = info.colorSpace == ProfileColorSpace.Cmyk && info.convertsFrom && info.convertsTo

class ColorProfileException(
    message: String,
) : Exception(message)

class ColorProfileLibrary(
    private val folder: File,
    private val readBuiltIn: (String) -> ByteArray,
    private val readInfo: (ByteArray) -> ColorProfileReadResult = ColorProfiles::read,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    var entries: List<ColorProfileEntry> by mutableStateOf(emptyList())
        private set

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val documentProfiles: List<ColorProfileEntry> get() = entries.filter { it.info.isDocumentProfile }

    val printProfiles: List<ColorProfileEntry> get() = entries.filter { it.isPrintProfile }

    fun entry(id: String): ColorProfileEntry? = entries.firstOrNull { it.id == id }

    suspend fun load() =
        mutex.withLock {
            entries = withContext(io) { builtInEntries() + importedEntries() }
        }

    suspend fun bytes(id: String): ByteArray? =
        withContext(io) {
            val builtIn = BUILT_INS.firstOrNull { it.id == id }
            when {
                builtIn != null -> readBuiltIn(builtIn.file)
                isImportedId(id) -> File(folder, "$id$PROFILE_EXTENSION").takeIf { it.isFile }?.readBytes()
                else -> null
            }
        }

    suspend fun documentProfile(id: String): DocumentColorProfile? {
        if (id == SRGB_ID) return null
        val entry = entry(id) ?: return null
        return bytes(id)?.let { DocumentColorProfile(entry.name, it) }
    }

    suspend fun printProfile(id: String): DocumentColorProfile? {
        val entry = entry(id)?.takeIf { it.isPrintProfile } ?: return null
        return bytes(id)?.let { DocumentColorProfile(entry.name, it) }
    }

    suspend fun import(
        bytes: ByteArray,
        fallbackName: String,
    ): ColorProfileEntry =
        mutex.withLock {
            val info =
                when (val result = readInfo(bytes)) {
                    is ColorProfileReadResult.Success -> result.info
                    is ColorProfileReadResult.Invalid -> throw ColorProfileException(result.reason)
                }
            withContext(io) {
                entries.firstOrNull { existing -> bytesOrNull(existing.id)?.contentEquals(bytes) == true }?.let { return@withContext it }
                val id = UUID.randomUUID().toString()
                val name = info.description.trim().ifEmpty { fallbackName.ifEmpty { id } }
                folder.mkdirs()
                File(folder, "$id$PROFILE_EXTENSION").writeBytes(bytes)
                File(folder, "$id$NAME_EXTENSION").writeText(json.encodeToString(StoredName.serializer(), StoredName(name)))
                ColorProfileEntry(id, name, info, builtIn = false).also { entries = entries + it }
            }
        }

    suspend fun delete(id: String): Boolean =
        mutex.withLock {
            if (!isImportedId(id) || entry(id) == null) return@withLock false
            withContext(io) {
                File(folder, "$id$PROFILE_EXTENSION").delete()
                File(folder, "$id$NAME_EXTENSION").delete()
            }
            entries = entries.filterNot { it.id == id }
            true
        }

    private fun bytesOrNull(id: String): ByteArray? {
        val builtIn = BUILT_INS.firstOrNull { it.id == id }
        return if (builtIn != null) readBuiltIn(builtIn.file) else File(folder, "$id$PROFILE_EXTENSION").takeIf { it.isFile }?.readBytes()
    }

    private fun builtInEntries(): List<ColorProfileEntry> =
        BUILT_INS.mapNotNull { builtIn ->
            val info = (readInfo(readBuiltIn(builtIn.file)) as? ColorProfileReadResult.Success)?.info ?: return@mapNotNull null
            ColorProfileEntry(builtIn.id, builtIn.name, info, builtIn = true)
        }

    private fun importedEntries(): List<ColorProfileEntry> =
        folder
            .listFiles { file -> file.name.endsWith(PROFILE_EXTENSION) }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(PROFILE_EXTENSION)
                if (!isImportedId(id)) return@mapNotNull null
                val bytes =
                    try {
                        file.readBytes()
                    } catch (error: IOException) {
                        return@mapNotNull null
                    }
                val info = (readInfo(bytes) as? ColorProfileReadResult.Success)?.info ?: return@mapNotNull null
                val name =
                    try {
                        json.decodeFromString(StoredName.serializer(), File(folder, "$id$NAME_EXTENSION").readText()).name
                    } catch (error: IOException) {
                        null
                    } catch (error: SerializationException) {
                        null
                    } ?: info.description.ifEmpty { id }
                ColorProfileEntry(id, name, info, builtIn = false)
            }

    private fun isImportedId(id: String): Boolean = runCatching { UUID.fromString(id) }.isSuccess

    @Serializable
    private data class StoredName(
        val name: String,
    )

    data class BuiltInProfile(
        val id: String,
        val file: String,
        val name: String,
    )

    companion object {
        const val SRGB_ID = "srgb"
        const val ADOBE_RGB_ID = "adobe-rgb"
        const val NANQUIM_CMYK_ID = "nanquim-cmyk"
        const val PRINT_WORKING_PROFILE_ID = ADOBE_RGB_ID
        const val ASSET_FOLDER = "profiles"
        private const val PROFILE_EXTENSION = ".icc"
        private const val NAME_EXTENSION = ".json"

        val BUILT_INS =
            listOf(
                BuiltInProfile(SRGB_ID, "sRGB-v4.icc", "sRGB IEC61966-2.1"),
                BuiltInProfile("display-p3", "DisplayP3-v4.icc", "Display P3"),
                BuiltInProfile(ADOBE_RGB_ID, "AdobeCompat-v4.icc", "Adobe RGB (1998) compatible"),
                BuiltInProfile("rec2020", "Rec2020-v4.icc", "Rec. 2020"),
                BuiltInProfile("cgats001", "CGATS001Compat-v2-micro.icc", "CGATS TR 001 (SWOP-like CMYK, input only)"),
                BuiltInProfile(NANQUIM_CMYK_ID, "NanquimCMYK-v4.icc", "Nanquim CMYK (CGATS TR 001)"),
            )
    }
}
