package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerMask
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.VideoLayerContent
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.SavedSelection
import java.time.Instant
import java.time.format.DateTimeParseException

object NanquimManifest {
    const val FORMAT = "nanquim"
    const val MANIFEST_FILE = "manifest.json"
    const val THUMBNAIL_FILE = "thumbnail.png"

    const val PROFILE_FILE = "profile.icc"
    const val CMYK_PROFILE_FILE = "cmyk.icc"
    const val MIMETYPE_FILE = "mimetype"

    const val VERSION = 1
    const val TILE_SIZE = 256
    const val PIXEL_FORMAT = "rgba8_premul_srgb"
    const val SRGB_PROFILE_NAME = "sRGB IEC61966-2.1"
    const val SELECTIONS_FOLDER = "selections/"

    const val MAXIMUM_CANVAS_SIDE = 1 shl 17

    const val MAXIMUM_GROUP_DEPTH = 32
    const val TEXT_FOLDER = "text/"

    const val MAXIMUM_TEXT_MODEL_BYTES = 4L * 1024 * 1024

    private val json = Json { prettyPrint = false }

    fun parse(text: String): ManifestReadResult {
        val root =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (error: IllegalArgumentException) {
                null
            } ?: return ManifestReadResult.Invalid("manifest.json is not a JSON object")
        return read(root)
    }

    fun read(root: JsonObject): ManifestReadResult {
        if (root.string("format") != FORMAT) return ManifestReadResult.Invalid("Not a Nanquim document")
        val minimumReader = root.int("minReaderVersion") ?: 1
        if (minimumReader > VERSION) return ManifestReadResult.NeedsNewerApp(minimumReader)
        val canvas = root["canvas"] as? JsonObject ?: return ManifestReadResult.Invalid("No canvas")
        val width = canvas.int("width") ?: 0
        val height = canvas.int("height") ?: 0
        if (width !in 1..MAXIMUM_CANVAS_SIDE || height !in 1..MAXIMUM_CANVAS_SIDE) {
            return ManifestReadResult.Invalid("Invalid canvas size ${width}×$height")
        }
        val pixelFormat = PixelFormat.fromId(canvas.string("pixelFormat") ?: PIXEL_FORMAT)
        if ((canvas.int("tileSize") ?: TILE_SIZE) != TILE_SIZE || pixelFormat == null) {
            return ManifestReadResult.Unsupported("Tile size or pixel format not supported")
        }
        val converter = LayerConverter()
        val layers =
            try {
                converter.nodes(root["layers"] as? JsonArray ?: JsonArray(emptyList()), depth = 1)
            } catch (error: InvalidManifestException) {
                return ManifestReadResult.Invalid(error.message ?: "Invalid layers")
            }
        val background = root["background"] as? JsonObject
        val color = (background?.get("color") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }
        val referenceId = root.string("referenceLayerId")?.let(converter.idByUuid::get)
        val selection = root["selection"] as? JsonObject
        val saved =
            try {
                (selection?.get("saved") as? JsonArray).orEmpty().mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    SavedSelection(converter.newId(entry.string("id")), entry.string("name") ?: "")
                }
            } catch (error: InvalidManifestException) {
                return ManifestReadResult.Invalid(error.message ?: "Invalid saved selections")
            }
        val activeSelectionUuid = selection?.string("active")?.removePrefix(SELECTIONS_FOLDER)?.takeIf { it.isNotEmpty() }
        val savedIds = saved.map { it.id }.toSet()
        val activeSelectionId =
            activeSelectionUuid?.let { uuid ->
                converter.idByUuid[uuid]?.takeIf { it in savedIds } ?: if (uuid in converter.idByUuid) null else converter.newId(uuid)
            }
        var state =
            DocumentState(
                widthPx = width,
                heightPx = height,
                background =
                    Background(
                        color = if (color != null && color.size == 4) RgbaColor(color[0], color[1], color[2], color[3]) else RgbaColor.WHITE,
                        hidden = background?.boolean("hidden") ?: false,
                    ),
                layers = layers,
                activeLayerId = null,
                nextId = converter.nextId,
                selectionId = activeSelectionId,
                savedSelections = saved,
                pixelFormat = pixelFormat,
            )
        if (referenceId != null && state.raster(referenceId) != null) {
            state = state.copy(layers = state.layers.markReference(referenceId))
        }
        val activeId = root.string("activeLayerId")?.let(converter.idByUuid::get)?.takeIf { state.find(it) != null }
        state = state.copy(activeLayerId = activeId ?: state.rasterLayers().lastOrNull()?.id ?: state.layers.lastOrNull()?.id)
        val stats = root["stats"] as? JsonObject
        val now = Instant.now()
        val info =
            DocumentInfo(
                id = root.string("id") ?: "",
                name = root.string("name") ?: "",
                createdAt = root.instant("createdAt") ?: now,
                modifiedAt = root.instant("modifiedAt") ?: now,
                dpi = canvas.double("dpi")?.takeIf { it > 0 } ?: DocumentInfo.DEFAULT_DPI,
                unit = canvas.string("unit")?.let(CanvasUnit::fromId) ?: CanvasUnit.Pixels,
                strokeCount = stats?.long("strokeCount") ?: 0,
                timeSpentSeconds = stats?.long("timeSpentSeconds") ?: 0,
                guides = GuidesJson.read(root["guides"], width, height),
                reference = ReferenceJson.read(root["reference"]),
                timelapse = TimelapseSettings.read(root["timelapse"]),
                artist = ArtistInfo.read(root["author"]),
            )
        fun profileFile(key: String) =
            (canvas[key] as? JsonObject)?.let { profile ->
                profile.string("icc")?.takeIf { it.isNotEmpty() }?.let { path ->
                    ManifestColorProfile(profile.string("name") ?: path, path)
                }
            }
        return ManifestReadResult.Success(
            state,
            info,
            LayerUuids.of(converter.uuidById),
            root,
            profileFile("colorProfile"),
            converter.textModelFiles,
            profileFile("cmykProfile"),
        )
    }

    fun write(
        state: DocumentState,
        info: DocumentInfo,
        uuids: LayerUuids,
        appVersion: String,
        previous: JsonObject? = null,
    ): JsonObject {
        val previousNodes = mutableMapOf<String, JsonObject>()
        (previous?.get("layers") as? JsonArray)?.let { collectNodes(it, previousNodes) }
        fun uuid(id: Int) = requireNotNull(uuids.uuidOf(id)) { "No UUID for node $id" }

        fun node(layer: LayerNode): JsonObject {
            val old = previousNodes[uuid(layer.id)]
            val fields = old.orEmptyMap().toMutableMap()
            fields.remove("contentBounds")
            when (layer) {
                is RasterLayer -> {
                    if (layer.text != null) {
                        fields["type"] = JsonPrimitive("text")
                        fields["textModel"] = JsonPrimitive(textModelFile(uuid(layer.id)))
                    } else {
                        fields["type"] = JsonPrimitive("raster")
                        fields.remove("textModel")
                    }
                    val video = layer.video
                    if (video != null) {
                        fields["video"] =
                            JsonObject(
                                mapOf(
                                    "file" to JsonPrimitive(VideoLayerContent.FOLDER + video.file),
                                    "timeMicroseconds" to JsonPrimitive(video.timeMicroseconds),
                                    "durationMicroseconds" to JsonPrimitive(video.durationMicroseconds),
                                ),
                            )
                    } else {
                        fields.remove("video")
                    }
                    fields.remove("children")
                    fields.remove("collapsed")
                }
                is LayerGroup -> {
                    fields["type"] = JsonPrimitive("group")
                    listOf("alphaLock", "clipping", "mask", "textModel", "assisted", "video", "private").forEach(fields::remove)
                }
            }
            fields["id"] = JsonPrimitive(uuid(layer.id))
            fields["name"] = JsonPrimitive(layer.name)
            fields["opacity"] = JsonPrimitive(layer.opacity)
            fields["blend"] = JsonPrimitive(layer.blend.id)
            fields["hidden"] = JsonPrimitive(layer.hidden)
            fields["locked"] = JsonPrimitive(layer.locked)
            when (layer) {
                is RasterLayer -> {
                    fields["alphaLock"] = JsonPrimitive(layer.alphaLock)
                    fields["clipping"] = JsonPrimitive(layer.clipping)
                    if (layer.assisted) fields["assisted"] = JsonPrimitive(true) else fields.remove("assisted")
                    if (layer.isPrivate) fields["private"] = JsonPrimitive(true) else fields.remove("private")
                    val mask = layer.mask
                    if (mask == null) {
                        fields.remove("mask")
                    } else {
                        val oldMask = (old?.get("mask") as? JsonObject).orEmptyMap()
                        fields["mask"] =
                            JsonObject(
                                oldMask +
                                    mapOf(
                                        "id" to JsonPrimitive(uuid(mask.id)),
                                        "enabled" to JsonPrimitive(mask.enabled),
                                        "inverted" to JsonPrimitive(mask.inverted),
                                    ),
                            )
                    }
                }
                is LayerGroup -> {
                    fields["collapsed"] = JsonPrimitive(layer.collapsed)
                    fields["children"] = JsonArray(layer.children.map(::node))
                }
            }
            return JsonObject(fields)
        }

        val root = previous.orEmptyMap().toMutableMap()
        root["format"] = JsonPrimitive(FORMAT)
        root["version"] = JsonPrimitive(VERSION)
        root["minReaderVersion"] = JsonPrimitive(1)
        root["app"] =
            buildJsonObject {
                put("name", "Nanquim")
                put("version", appVersion)
            }
        root["id"] = JsonPrimitive(info.id)
        root["name"] = JsonPrimitive(info.name)
        root["createdAt"] = JsonPrimitive(info.createdAt.toString())
        root["modifiedAt"] = JsonPrimitive(info.modifiedAt.toString())
        val previousCanvas = (previous?.get("canvas") as? JsonObject).orEmptyMap()
        root["canvas"] =
            JsonObject(
                previousCanvas +
                    buildJsonObject {
                        put("width", state.widthPx)
                        put("height", state.heightPx)
                        put("dpi", info.dpi)
                        put("unit", info.unit.id)
                        put("tileSize", TILE_SIZE)
                        put("pixelFormat", state.pixelFormat.id)
                        putJsonObject("colorProfile") {
                            put("name", info.colorProfile?.name ?: SRGB_PROFILE_NAME)
                            put("icc", if (info.colorProfile != null) JsonPrimitive(PROFILE_FILE) else JsonNull)
                        }
                        put(
                            "cmykProfile",
                            info.cmykProfile?.let { profile ->
                                buildJsonObject {
                                    put("name", profile.name)
                                    put("icc", CMYK_PROFILE_FILE)
                                }
                            } ?: JsonNull,
                        )
                    },
            )
        root["background"] =
            buildJsonObject {
                putJsonArray("color") {
                    with(state.background.color) { listOf(red, green, blue, alpha).forEach { add(JsonPrimitive(it)) } }
                }
                put("hidden", state.background.hidden)
            }
        root["activeLayerId"] = state.activeLayerId?.let { JsonPrimitive(uuid(it)) } ?: JsonNull
        root["referenceLayerId"] = state.referenceLayerId?.let { JsonPrimitive(uuid(it)) } ?: JsonNull
        root["layers"] = buildJsonArray { state.layers.forEach { add(node(it)) } }
        val previousSelection = (previous?.get("selection") as? JsonObject).orEmptyMap()
        val previousSaved =
            (previousSelection["saved"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.string("id") }
        root["selection"] =
            JsonObject(
                previousSelection +
                    mapOf(
                        "active" to (state.selectionId?.let { JsonPrimitive(SELECTIONS_FOLDER + uuid(it)) } ?: JsonNull),
                        "saved" to
                            JsonArray(
                                state.savedSelections.map { saved ->
                                    val id = uuid(saved.id)
                                    val fields = mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(saved.name))
                                    JsonObject(previousSaved[id].orEmptyMap() + fields)
                                },
                            ),
                    ),
            )
        val previousStats = (previous?.get("stats") as? JsonObject).orEmptyMap()
        root["stats"] =
            JsonObject(
                previousStats +
                    mapOf(
                        "strokeCount" to JsonPrimitive(info.strokeCount),
                        "timeSpentSeconds" to JsonPrimitive(info.timeSpentSeconds),
                    ),
            )
        info.guides?.let { guides ->
            root["guides"] = GuidesJson.write(guides, previous?.get("guides") as? JsonObject, state.widthPx, state.heightPx)
        }
        info.timelapse?.let { timelapse ->
            root["timelapse"] = TimelapseSettings.write(timelapse, previous?.get("timelapse") as? JsonObject)
        }
        info.reference?.let { reference ->
            root["reference"] = ReferenceJson.write(reference, previous?.get("reference") as? JsonObject)
        }
        info.artist?.let { artist ->
            root["author"] = ArtistInfo.write(artist, previous?.get("author") as? JsonObject)
        }
        if ("extensions" !in root) root["extensions"] = JsonObject(emptyMap())
        return JsonObject(root)
    }

    fun textModelFile(layerUuid: String): String = "$TEXT_FOLDER$layerUuid.json"

    private fun videoOf(value: JsonObject): VideoLayerContent? {
        val path = value.string("file") ?: return null
        if (!path.startsWith(VideoLayerContent.FOLDER)) return null
        val name = path.removePrefix(VideoLayerContent.FOLDER)
        if (!VideoLayerContent.isSafeVideoFileName(name)) return null
        val duration = (value.long("durationMicroseconds") ?: 0L).coerceAtLeast(0L)
        return VideoLayerContent(name, (value.long("timeMicroseconds") ?: 0L).coerceIn(0L, duration), duration)
    }

    private fun isSafeTextModelPath(path: String): Boolean =
        path.startsWith(TEXT_FOLDER) && path.length > TEXT_FOLDER.length && !path.contains("..") &&
            !path.substring(TEXT_FOLDER.length).contains('/') && !path.contains('\\')

        fun encode(manifest: JsonObject): ByteArray = json.encodeToString(JsonObject.serializer(), manifest).toByteArray(Charsets.UTF_8)

    fun surfaces(
        state: DocumentState,
        uuids: LayerUuids,
    ): List<DocumentSurface> =
        state.selectionSurfaceIds().map { id ->
            val uuid = requireNotNull(uuids.uuidOf(id)) { "No UUID for selection $id" }
            DocumentSurface(id, SELECTIONS_FOLDER + uuid, SurfaceFormat.SelectionR8)
        } +
            state.rasterLayers().flatMap { layer ->
            val layerUuid = requireNotNull(uuids.uuidOf(layer.id)) { "No UUID for layer ${layer.id}" }
            listOf(DocumentSurface(layer.id, "layers/$layerUuid", SurfaceFormat.Rgba8)) +
                listOfNotNull(
                    layer.mask?.let { mask ->
                        DocumentSurface(mask.id, "masks/${requireNotNull(uuids.uuidOf(mask.id))}", SurfaceFormat.R8)
                    },
                )
        }

    private fun collectNodes(
        nodes: JsonArray,
        into: MutableMap<String, JsonObject>,
    ) {
        for (element in nodes) {
            val node = element as? JsonObject ?: continue
            node.string("id")?.let { into[it] = node }
            (node["children"] as? JsonArray)?.let { collectNodes(it, into) }
        }
    }

    private fun List<LayerNode>.markReference(id: Int): List<LayerNode> =
        map { node ->
            when (node) {
                is RasterLayer -> if (node.id == id) node.copy(isReference = true) else node
                is LayerGroup -> node.copy(children = node.children.markReference(id))
            }
        }

    private class LayerConverter {
        val idByUuid = mutableMapOf<String, Int>()
        val uuidById = mutableMapOf<Int, String>()
        var nextId = 1
            private set
        val textModelFiles = mutableMapOf<Int, String>()

        fun nodes(
            array: JsonArray,
            depth: Int,
        ): List<LayerNode> {
            if (depth > MAXIMUM_GROUP_DEPTH) throw InvalidManifestException("Groups nested too deeply")
            return array.map { element -> node(element as? JsonObject ?: throw InvalidManifestException("Invalid layer entry"), depth) }
        }

        fun newId(uuid: String?): Int = idFor(uuid)

        private fun idFor(uuid: String?): Int {
            if (uuid.isNullOrEmpty()) throw InvalidManifestException("Layer without an id")
            if (uuid in idByUuid) throw InvalidManifestException("Duplicate layer id $uuid")
            val id = nextId++
            idByUuid[uuid] = id
            uuidById[id] = uuid
            return id
        }

        private fun node(
            value: JsonObject,
            depth: Int,
        ): LayerNode {
            val type = value.string("type")
            val id = idFor(value.string("id"))
            val name = value.string("name") ?: ""
            val opacity = (value.double("opacity") ?: 1.0).toFloat().coerceIn(0f, 1f)
            val blend = value.string("blend")?.let(BlendMode::fromId)
            val hidden = value.boolean("hidden") ?: false
            val locked = value.boolean("locked") ?: false
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
                        children = nodes(value["children"] as? JsonArray ?: JsonArray(emptyList()), depth + 1),
                    )
                "raster", "text" -> {
                    if (type == "text") {
                        value.string("textModel")?.takeIf(::isSafeTextModelPath)?.let { textModelFiles[id] = it }
                    }
                    val maskValue = value["mask"] as? JsonObject
                    RasterLayer(
                        id = id,
                        name = name,
                        opacity = opacity,
                        blend = blend?.takeIf { it != BlendMode.PassThrough } ?: BlendMode.Normal,
                        hidden = hidden,
                        locked = locked,
                        alphaLock = value.boolean("alphaLock") ?: false,
                        clipping = value.boolean("clipping") ?: false,
                        assisted = value.boolean("assisted") ?: false,
                        isPrivate = value.boolean("private") ?: false,
                        video = (value["video"] as? JsonObject)?.let(::videoOf),
                        mask =
                            maskValue?.let { mask ->
                                LayerMask(
                                    id = idFor(mask.string("id")),
                                    enabled = mask.boolean("enabled") ?: true,
                                    inverted = mask.boolean("inverted") ?: false,
                                )
                            },
                    )
                }
                else -> throw InvalidManifestException("Unknown layer type '$type'")
            }
        }
    }

    private class InvalidManifestException(
        message: String,
    ) : Exception(message)
}

enum class SurfaceFormat {
    Rgba8,
    R8,

    SelectionR8,
}

data class DocumentSurface(
    val id: Int,
    val folder: String,
    val format: SurfaceFormat,
)

data class ManifestColorProfile(
    val name: String,
    val file: String,
)

sealed interface ManifestReadResult {
    data class Success(
        val state: DocumentState,
        val info: DocumentInfo,
        val uuids: LayerUuids,
        val manifest: JsonObject,
        val colorProfile: ManifestColorProfile? = null,
        val textModelFiles: Map<Int, String> = emptyMap(),
        val cmykProfile: ManifestColorProfile? = null,
    ) : ManifestReadResult

    data class NeedsNewerApp(
        val minReaderVersion: Int,
    ) : ManifestReadResult

    data class Unsupported(
        val reason: String,
    ) : ManifestReadResult

    data class Invalid(
        val reason: String,
    ) : ManifestReadResult
}

private fun JsonObject?.orEmptyMap(): Map<String, JsonElement> = this ?: emptyMap()

private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = primitive(key)?.takeUnless { it.isString }?.intOrNull

private fun JsonObject.long(key: String): Long? = primitive(key)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.double(key: String): Double? = primitive(key)?.takeUnless { it.isString }?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.takeUnless { it.isString }?.booleanOrNull

private fun JsonObject.instant(key: String): Instant? =
    string(key)?.let {
        try {
            Instant.parse(it)
        } catch (error: DateTimeParseException) {
            null
        }
    }
