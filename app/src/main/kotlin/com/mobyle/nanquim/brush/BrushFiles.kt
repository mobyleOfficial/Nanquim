package com.mobyle.nanquim.brush

import android.content.ContentResolver
import android.net.Uri
import com.mobyle.nanquim.R
import com.mobyle.nanquim.brush.library.BrushGroup
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.document.brush.BrushPackageIo
import com.mobyle.nanquim.formats.interchange.ProcreateBrush
import com.mobyle.nanquim.formats.interchange.ProcreateBrushSet
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

enum class BrushFileKind {
    Brush,

    BrushSet,

    ProcreateBrush,

    ProcreateBrushSet,
}

object BrushFiles {
    const val MAXIMUM_BYTES = 256L * 1024 * 1024

    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    fun kindOf(bytes: ByteArray): BrushFileKind? {
        if (bytes.size < ZIP_SIGNATURE.size || !bytes.copyOf(ZIP_SIGNATURE.size).contentEquals(ZIP_SIGNATURE)) return null
        var hasBrush = false
        var hasProcreateBrush = false
        var hasProcreateFolders = false
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when {
                        entry.name == BrushPackageIo.BRUSHSET_JSON -> return BrushFileKind.BrushSet
                        entry.name == ProcreateBrushSet.INDEX -> return BrushFileKind.ProcreateBrushSet
                        entry.name == BrushPackage.BRUSH_JSON -> hasBrush = true
                        entry.name == ProcreateBrush.ARCHIVE -> hasProcreateBrush = true
                        entry.name.endsWith("/${ProcreateBrush.ARCHIVE}") -> hasProcreateFolders = true
                    }
                }
            }
        } catch (_: IOException) {
            return null
        }
        return when {
            hasBrush -> BrushFileKind.Brush
            hasProcreateBrush -> BrushFileKind.ProcreateBrush
            hasProcreateFolders -> BrushFileKind.ProcreateBrushSet
            else -> null
        }
    }

    fun read(
        contentResolver: ContentResolver,
        uri: Uri,
    ): ByteArray {
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        return stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                if (output.size() > MAXIMUM_BYTES) throw IOException("The brush file is too large")
            }
            output.toByteArray()
        }
    }

    fun fileName(
        name: String,
        extension: String,
    ): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "brush" }
        return "$safe.$extension"
    }
}

fun builtInGroupNameRes(group: BrushGroup): Int? =
    when (group.builtInKey) {
        "sketching" -> R.string.brush_group_sketching
        "inking" -> R.string.brush_group_inking
        "drawing" -> R.string.brush_group_drawing
        "painting" -> R.string.brush_group_painting
        "artistic" -> R.string.brush_group_artistic
        "calligraphy" -> R.string.brush_group_calligraphy
        "airbrushing" -> R.string.brush_group_airbrushing
        "textures" -> R.string.brush_group_textures
        "abstract" -> R.string.brush_group_abstract
        "charcoals" -> R.string.brush_group_charcoals
        "spraypaints" -> R.string.brush_group_spraypaints
        "water" -> R.string.brush_group_water
        "elements" -> R.string.brush_group_elements
        "touchups" -> R.string.brush_group_touchups
        "retro" -> R.string.brush_group_retro
        "luminance" -> R.string.brush_group_luminance
        "industrial" -> R.string.brush_group_industrial
        "organic" -> R.string.brush_group_organic
        "earth" -> R.string.brush_group_earth
        "imported" -> R.string.brush_group_imported
        else -> null
    }
