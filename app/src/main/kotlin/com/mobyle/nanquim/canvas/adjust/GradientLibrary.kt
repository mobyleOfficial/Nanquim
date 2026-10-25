package com.mobyle.nanquim.canvas.adjust

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobyle.nanquim.R
import com.mobyle.nanquim.formats.gradient.GradientDefinition
import com.mobyle.nanquim.formats.gradient.GradientFile
import com.mobyle.nanquim.formats.gradient.GradientFormatException
import com.mobyle.nanquim.formats.gradient.GradientStop
import java.io.File
import java.io.IOException
import java.util.UUID

enum class GradientCategory(
    @StringRes val labelRes: Int,
) {
    Monochrome(R.string.gradient_category_monochrome),
    Warm(R.string.gradient_category_warm),
    Cool(R.string.gradient_category_cool),
    Vivid(R.string.gradient_category_vivid),
    Custom(R.string.gradient_category_custom),
}

data class GradientEntry(
    val id: String,
    val category: GradientCategory,
    val definition: GradientDefinition,
    @StringRes val nameRes: Int? = null,
) {
    val builtIn: Boolean get() = id.startsWith(GradientLibrary.BUILT_IN_PREFIX)
}

class GradientLibrary(
    private val folder: File,
) {
    var entries: List<GradientEntry> by mutableStateOf(BUILT_INS)
        private set

    fun entry(id: String): GradientEntry? = entries.firstOrNull { it.id == id }

    fun load(): List<GradientEntry> {
        val files = folder.listFiles { file -> file.extension == EXTENSION }.orEmpty().sortedBy { it.lastModified() }
        return BUILT_INS +
            files.mapNotNull { file ->
                try {
                    GradientEntry(file.nameWithoutExtension, GradientCategory.Custom, GradientFile.read(file.readText()))
                } catch (exception: GradientFormatException) {
                    null
                } catch (exception: IOException) {
                    null
                }
            }
    }

    fun publish(loaded: List<GradientEntry>) {
        entries = loaded
    }

    fun write(
        definition: GradientDefinition,
        id: String = UUID.randomUUID().toString(),
    ): GradientEntry {
        folder.mkdirs()
        val file = File(folder, "$id.$EXTENSION")
        val temporary = File(folder, "$id.$EXTENSION.tmp")
        temporary.writeText(GradientFile.write(definition))
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) throw IOException("Cannot save gradient $id")
        }
        return GradientEntry(id, GradientCategory.Custom, definition)
    }

    fun add(entry: GradientEntry) {
        entries = if (entries.any { it.id == entry.id }) entries.map { if (it.id == entry.id) entry else it } else entries + entry
    }

    fun deleteFile(id: String) {
        require(!id.startsWith(BUILT_IN_PREFIX)) { "Built-in gradients cannot be deleted" }
        File(folder, "$id.$EXTENSION").delete()
    }

    fun remove(id: String) {
        entries = entries.filterNot { it.id == id }
    }

    companion object {
        const val BUILT_IN_PREFIX = "builtin."
        const val EXTENSION = "nqgradient"

        private fun stops(vararg values: Float): List<GradientStop> =
            values.toList().chunked(4).map { GradientStop(it[0], it[1], it[2], it[3]) }

        private fun builtIn(
            key: String,
            category: GradientCategory,
            @StringRes nameRes: Int,
            name: String,
            vararg values: Float,
        ) = GradientEntry(BUILT_IN_PREFIX + key, category, GradientDefinition(name, stops(*values)), nameRes)

        val BUILT_INS: List<GradientEntry> =
            listOf(
                builtIn(
                    "blackWhite",
                    GradientCategory.Monochrome,
                    R.string.gradient_black_white,
                    "Black and white",
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    1f,
                    1f,
                    1f,
                ),
                builtIn(
                    "sepia",
                    GradientCategory.Monochrome,
                    R.string.gradient_sepia,
                    "Sepia",
                    0f,
                    0.1f,
                    0.06f,
                    0.02f,
                    0.5f,
                    0.6f,
                    0.42f,
                    0.25f,
                    1f,
                    1f,
                    0.95f,
                    0.85f,
                ),
                builtIn(
                    "cyanotype",
                    GradientCategory.Monochrome,
                    R.string.gradient_cyanotype,
                    "Cyanotype",
                    0f,
                    0.02f,
                    0.1f,
                    0.25f,
                    0.55f,
                    0.3f,
                    0.55f,
                    0.75f,
                    1f,
                    0.95f,
                    0.98f,
                    1f,
                ),
                builtIn(
                    "ember",
                    GradientCategory.Warm,
                    R.string.gradient_ember,
                    "Ember",
                    0f,
                    0f,
                    0f,
                    0f,
                    0.35f,
                    0.6f,
                    0.05f,
                    0f,
                    0.7f,
                    1f,
                    0.55f,
                    0f,
                    1f,
                    1f,
                    0.95f,
                    0.7f,
                ),
                builtIn(
                    "sunset",
                    GradientCategory.Warm,
                    R.string.gradient_sunset,
                    "Sunset",
                    0f,
                    0.1f,
                    0f,
                    0.3f,
                    0.5f,
                    0.9f,
                    0.2f,
                    0.4f,
                    1f,
                    1f,
                    0.95f,
                    0.6f,
                ),
                builtIn(
                    "desert",
                    GradientCategory.Warm,
                    R.string.gradient_desert,
                    "Desert",
                    0f,
                    0.25f,
                    0.12f,
                    0.08f,
                    0.5f,
                    0.78f,
                    0.5f,
                    0.3f,
                    1f,
                    0.98f,
                    0.9f,
                    0.75f,
                ),
                builtIn(
                    "ocean",
                    GradientCategory.Cool,
                    R.string.gradient_ocean,
                    "Ocean",
                    0f,
                    0f,
                    0.05f,
                    0.15f,
                    0.5f,
                    0f,
                    0.4f,
                    0.6f,
                    1f,
                    0.7f,
                    0.95f,
                    1f,
                ),
                builtIn(
                    "mint",
                    GradientCategory.Cool,
                    R.string.gradient_mint,
                    "Mint",
                    0f,
                    0.02f,
                    0.15f,
                    0.12f,
                    0.6f,
                    0.35f,
                    0.8f,
                    0.6f,
                    1f,
                    0.9f,
                    1f,
                    0.95f,
                ),
                builtIn(
                    "twilight",
                    GradientCategory.Cool,
                    R.string.gradient_twilight,
                    "Twilight",
                    0f,
                    0.05f,
                    0.02f,
                    0.2f,
                    0.45f,
                    0.35f,
                    0.25f,
                    0.6f,
                    0.8f,
                    0.85f,
                    0.6f,
                    0.7f,
                    1f,
                    1f,
                    0.9f,
                    0.8f,
                ),
                builtIn(
                    "neon",
                    GradientCategory.Vivid,
                    R.string.gradient_neon,
                    "Neon",
                    0f,
                    0.1f,
                    0f,
                    0.2f,
                    0.35f,
                    0.9f,
                    0f,
                    0.8f,
                    0.7f,
                    0f,
                    1f,
                    0.9f,
                    1f,
                    1f,
                    1f,
                    0.6f,
                ),
                builtIn(
                    "spectrum",
                    GradientCategory.Vivid,
                    R.string.gradient_spectrum,
                    "Spectrum",
                    0f,
                    0.5f,
                    0f,
                    1f,
                    0.2f,
                    0f,
                    0.3f,
                    1f,
                    0.4f,
                    0f,
                    0.9f,
                    0.4f,
                    0.6f,
                    1f,
                    0.95f,
                    0f,
                    0.8f,
                    1f,
                    0.45f,
                    0f,
                    1f,
                    1f,
                    0f,
                    0.1f,
                ),
                builtIn(
                    "duotone",
                    GradientCategory.Vivid,
                    R.string.gradient_duotone,
                    "Duotone",
                    0f,
                    0.15f,
                    0.1f,
                    0.55f,
                    1f,
                    1f,
                    0.45f,
                    0.6f,
                ),
            )
    }
}
