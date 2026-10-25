package com.mobyle.nanquim.formats.interchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerMask
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.nanquim.LayerUuids

data class ImportWarning(
    val code: String,
    val detail: String,
)

data class ImportedArtwork(
    val source: String,
    val state: DocumentState,
    val uuids: LayerUuids,
    val dpi: Double,
    val warnings: List<ImportWarning>,
    val colorProfileName: String? = null,
    val colorProfileFile: String? = null,
    val cmykProfileName: String? = null,
    val cmykProfileFile: String? = null,
)

class ImportJsonException(
    message: String,
) : Exception(message)

object ImportJson {
    const val FILE_NAME = "import.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        text: String,
        defaultLayerName: (Int) -> String,
    ): ImportedArtwork {
        val root =
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw ImportJsonException("Not a JSON object")
        if (root.int("version") != 1) throw ImportJsonException("Unsupported import.json version")
        val width = root.int("width") ?: 0
        val height = root.int("height") ?: 0
        if (width <= 0 || height <= 0) throw ImportJsonException("Invalid canvas size")
        val converter = Converter(defaultLayerName)
        val layers = converter.nodes(root["layers"] as? JsonArray ?: JsonArray(emptyList()))
        val background =
            (root["background"] as? JsonObject)?.let { value ->
                val color = (value["color"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
                Background(
                    color =
                        if (color != null && color.size == 4) {
                            RgbaColor(color[0], color[1], color[2], color[3])
                        } else {
                            RgbaColor.WHITE
                        },
                    hidden = value.boolean("hidden") ?: false,
                )
            } ?: Background()
        val state =
            DocumentState(
                widthPx = width,
                heightPx = height,
                background = background,
                layers = layers,
                activeLayerId = converter.topmostRaster,
                nextId = converter.nextId,
            )
        val warnings =
            (root["warnings"] as? JsonArray)?.mapNotNull { element ->
                val warning = element as? JsonObject ?: return@mapNotNull null
                val code = warning.string("code") ?: return@mapNotNull null
                ImportWarning(code, warning.string("detail") ?: "")
            } ?: emptyList()
        val profile = root["colorProfile"] as? JsonObject
        val cmykProfile = root["cmykProfile"] as? JsonObject
        return ImportedArtwork(
            source = root.string("source") ?: "",
            state = state,
            uuids = LayerUuids.of(converter.uuids),
            dpi = (root.double("dpi") ?: 72.0).coerceIn(1.0, 100_000.0),
            warnings = warnings,
            colorProfileName = profile?.string("name")?.takeIf { it.isNotBlank() },
            colorProfileFile = profile?.string("icc")?.takeIf(::isPlainFileName),
            cmykProfileName = cmykProfile?.string("name")?.takeIf { it.isNotBlank() },
            cmykProfileFile = cmykProfile?.string("icc")?.takeIf(::isPlainFileName),
        )
    }

    private fun isPlainFileName(name: String): Boolean = name.isNotEmpty() && '/' !in name && '\\' !in name && name != ".."

    fun write(
        state: DocumentState,
        uuids: LayerUuids,
        dpi: Double,
        source: String = "nanquim",
    ): String {
        val uuid = { id: Int -> uuids.uuidOf(id) ?: throw IllegalArgumentException("No UUID for layer $id") }
        val background = state.background
        return buildJsonObject {
            put("version", 1)
            put("source", source)
            put("width", state.widthPx)
            put("height", state.heightPx)
            put("dpi", dpi)
            putJsonObject("background") {
                putJsonArray("color") {
                    with(background.color) {
                        add(red.toDouble())
                        add(green.toDouble())
                        add(blue.toDouble())
                        add(alpha.toDouble())
                    }
                }
                put("hidden", background.hidden)
            }
            put("layers", nodesJson(state.layers, uuid))
            putJsonArray("warnings") {}
        }.toString()
    }

    private fun nodesJson(
        nodes: List<LayerNode>,
        uuid: (Int) -> String,
    ): JsonArray = buildJsonArray { nodes.forEach { add(nodeJson(it, uuid)) } }

    private fun nodeJson(
        node: LayerNode,
        uuid: (Int) -> String,
    ): JsonObject =
        buildJsonObject {
            put("type", if (node is LayerGroup) "group" else "raster")
            put("uuid", uuid(node.id))
            put("name", node.name)
            put("opacity", node.opacity.toDouble())
            put("blend", node.blend.id)
            put("hidden", node.hidden)
            put("locked", node.locked)
            when (node) {
                is LayerGroup -> {
                    put("collapsed", node.collapsed)
                    put("children", nodesJson(node.children, uuid))
                }
                is RasterLayer -> {
                    put("alphaLock", node.alphaLock)
                    put("clipping", node.clipping)
                    if (node.assisted) put("assisted", true)
                    val mask = node.mask
                    if (mask == null) {
                        put("mask", JsonNull)
                    } else {
                        putJsonObject("mask") {
                            put("uuid", uuid(mask.id))
                            put("enabled", mask.enabled)
                            put("inverted", mask.inverted)
                        }
                    }
                }
            }
        }

    private class Converter(
        private val defaultLayerName: (Int) -> String,
    ) {
        val uuids = mutableMapOf<Int, String>()
        private val seenUuids = HashSet<String>()
        var nextId = 1
        var topmostRaster: Int? = null
        private var rasterCount = 0

        fun nodes(array: JsonArray): List<LayerNode> = array.mapNotNull { element -> (element as? JsonObject)?.let(::node) }

        private fun newId(uuid: String?): Int {
            if (uuid.isNullOrEmpty() || !seenUuids.add(uuid)) throw ImportJsonException("Missing or duplicate uuid")
            val id = nextId++
            uuids[id] = uuid
            return id
        }

        private fun node(value: JsonObject): LayerNode {
            val type = value.string("type")
            val id = newId(value.string("uuid"))
            val name = value.string("name") ?: ""
            val opacity = (value.double("opacity") ?: 1.0).toFloat().coerceIn(0f, 1f)
            val hidden = value.boolean("hidden") ?: false
            val locked = value.boolean("locked") ?: false
            val blend = value.string("blend")?.let(BlendMode::fromId)
            return when (type) {
                "group" ->
                    LayerGroup(
                        id = id,
                        name = name,
                        opacity = opacity,
                        blend = blend ?: BlendMode.PassThrough,
                        hidden = hidden,
                        locked = locked,
                        collapsed = value.boolean("collapsed") ?: false,
                        children = nodes(value["children"] as? JsonArray ?: JsonArray(emptyList())),
                    )
                "raster" -> {
                    rasterCount++
                    val mask =
                        (value["mask"] as? JsonObject)?.let { mask ->
                            LayerMask(
                                id = newId(mask.string("uuid")),
                                enabled = mask.boolean("enabled") ?: true,
                                inverted = mask.boolean("inverted") ?: false,
                            )
                        }
                    topmostRaster = id
                    RasterLayer(
                        id = id,
                        name = name.ifEmpty { defaultLayerName(rasterCount) },
                        opacity = opacity,
                        blend = blend?.takeUnless { it == BlendMode.PassThrough } ?: BlendMode.Normal,
                        hidden = hidden,
                        locked = locked,
                        alphaLock = value.boolean("alphaLock") ?: false,
                        clipping = value.boolean("clipping") ?: false,
                        assisted = value.boolean("assisted") ?: false,
                        mask = mask,
                    )
                }
                else -> throw ImportJsonException("Unknown node type $type")
            }
        }
    }
}

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = primitive(key)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.double(key: String): Double? = primitive(key)?.takeUnless { it.isString }?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.takeUnless { it.isString }?.booleanOrNull
