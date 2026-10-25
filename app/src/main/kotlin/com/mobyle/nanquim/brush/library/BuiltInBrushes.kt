package com.mobyle.nanquim.brush.library

import android.content.res.AssetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage

@Serializable
data class BuiltInLibrary(
    val format: Int,
    val version: Int,
    val defaults: Map<String, String> = emptyMap(),
    val groups: List<BuiltInGroup>,
)

@Serializable
data class BuiltInGroup(
    val key: String,
    val brushes: List<String>,
)

interface BuiltInBrushSource {
    fun library(): BuiltInLibrary

    fun readPackage(path: String): BrushPackage

    fun pathOf(
        groupKey: String,
        brushId: String,
    ): String = "$ROOT/$groupKey/$brushId"

    companion object {
        const val ROOT = "brushes"
        const val LIBRARY_FILE = "$ROOT/library.json"
        const val LIBRARY_FORMAT = 1
    }
}

class AssetBrushSource(
    private val assets: AssetManager,
) : BuiltInBrushSource {
    private val json = Json { ignoreUnknownKeys = true }

    override fun library(): BuiltInLibrary {
        val library =
            json.decodeFromString(
                BuiltInLibrary.serializer(),
                assets.open(BuiltInBrushSource.LIBRARY_FILE).use { it.readBytes().decodeToString() },
            )
        check(library.format == BuiltInBrushSource.LIBRARY_FORMAT) { "unsupported library.json format" }
        return library
    }

    override fun readPackage(path: String): BrushPackage {
        val document = BrushDocument.parse(read("$path/${BrushPackage.BRUSH_JSON}").decodeToString())
        val files = mutableMapOf<String, ByteArray>()
        for (name in assets.list(path).orEmpty()) {
            if (name == BrushPackage.BRUSH_JSON) continue
            val children = assets.list("$path/$name").orEmpty()
            if (children.isEmpty()) {
                files[name] = read("$path/$name")
            } else {
                children.forEach { child -> files["$name/$child"] = read("$path/$name/$child") }
            }
        }
        return BrushPackage(document, files)
    }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }
}
