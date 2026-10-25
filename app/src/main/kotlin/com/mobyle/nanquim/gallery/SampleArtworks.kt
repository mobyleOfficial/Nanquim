package com.mobyle.nanquim.gallery

import android.content.SharedPreferences
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SampleArtworks(
    private val assets: AssetManager,
    private val transfer: ArtworkTransfer,
    private val gallery: Gallery,
    private val preferences: SharedPreferences,
    private val cacheDirectory: File,
    private val names: (String) -> String?,
) {
    suspend fun installOnce() {
        if (preferences.getBoolean(KEY_INSTALLED, false)) return
        install()
        preferences.edit { putBoolean(KEY_INSTALLED, true) }
    }

    suspend fun install(): List<String> = gallery.whileBusy { installNow() }

    private suspend fun installNow(): List<String> {
        val files = withContext(Dispatchers.IO) { assets.list(FOLDER).orEmpty().filter { it.endsWith(EXTENSION) } }
        val created = mutableListOf<String>()
        for (key in files.map { it.removeSuffix(EXTENSION) }.sorted()) {
            val copy = File(cacheDirectory, "samples/$key$EXTENSION")
            try {
                withContext(Dispatchers.IO) {
                    copy.parentFile?.mkdirs()
                    assets.open("$FOLDER/$key$EXTENSION").use { input -> copy.outputStream().use(input::copyTo) }
                }
                val id = transfer.importDocument(Uri.fromFile(copy))
                names(key)?.let { gallery.rename(id, it) }
                created += id
            } catch (error: GalleryException) {
                Log.w(TAG, "sample $key not installed", error)
            } finally {
                withContext(Dispatchers.IO) { copy.delete() }
            }
        }
        return created
    }

    companion object {
        const val PREFERENCES_NAME = "samples"
        private const val KEY_INSTALLED = "installed"
        private const val FOLDER = "samples"
        private const val EXTENSION = ".nanquim"
        private const val TAG = "SampleArtworks"
    }
}
