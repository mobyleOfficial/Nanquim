package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.document.layer.PixelFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.TextLayerContent
import kotlinx.serialization.json.boolean
import com.mobyle.nanquim.document.layer.setPrivate
import com.mobyle.nanquim.document.layer.rasterizeVideo
import com.mobyle.nanquim.document.layer.addVideoLayer
import com.mobyle.nanquim.document.layer.VideoLayerContent
import com.mobyle.nanquim.document.layer.addTextLayer
import com.mobyle.nanquim.document.layer.withTextModels
import com.mobyle.nanquim.document.layer.SavedSelection
import com.mobyle.nanquim.document.layer.addMask
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.groupLayers
import com.mobyle.nanquim.document.layer.setAssisted
import com.mobyle.nanquim.document.layer.setBlend
import com.mobyle.nanquim.document.layer.setClipping
import com.mobyle.nanquim.document.layer.setOpacity
import com.mobyle.nanquim.document.layer.setReference
import java.io.File
import java.time.Instant

class NanquimManifestTest {
    private val fixtures = File(requireNotNull(javaClass.getResource("/nanquim")).toURI())

    private fun manifest(name: String) = File(fixtures, "manifests/$name").readText()

    private fun success(text: String) = assertInstanceOf(ManifestReadResult.Success::class.java, NanquimManifest.parse(text))

    @Test
    fun everyFixtureDocumentIsReadable() {
        val documents = fixtures.listFiles { file -> File(file, NanquimManifest.MANIFEST_FILE).isFile }.orEmpty()
        assertTrue(documents.isNotEmpty())
        for (document in documents) {
            val result = success(File(document, NanquimManifest.MANIFEST_FILE).readText())
            for (surface in NanquimManifest.surfaces(result.state, result.uuids)) {
                assertTrue(File(document, "${surface.folder}/index.json").isFile, "${document.name}: ${surface.folder}")
            }
        }
    }

    @Test
    fun layerTreeFixtureMatchesTheScene() {
        val result = success(File(fixtures, "v1-layer-tree/manifest.json").readText())
        assertEquals(512, result.state.widthPx)
        assertEquals(256, result.state.heightPx)
        val groups = result.state.allNodes().filterIsInstance<LayerGroup>().toList()
        assertTrue(groups.any { it.blend == BlendMode.PassThrough })
        assertTrue(groups.any { it.blend != BlendMode.PassThrough })
        assertTrue(result.state.rasterLayers().any { it.mask != null })
        assertTrue(result.state.rasterLayers().any { it.clipping })
        val masks = NanquimManifest.surfaces(result.state, result.uuids).filter { it.format == SurfaceFormat.R8 }
        assertTrue(masks.all { it.folder.startsWith("masks/") })
        val ids = result.state.allNodes().flatMap { node -> sequenceOf(node.id) + listOfNotNull((node as? RasterLayer)?.mask?.id) }.toList()
        assertEquals((1..ids.size).toList(), ids.sorted())
        assertEquals(ids.size + 1, result.state.nextId)
    }

    @Test
    fun readsEveryKnownField() {
        val result = success(manifest("future-fields.json"))
        val state = result.state
        assertEquals("Future", result.info.name)
        assertEquals(Instant.parse("2026-10-14T12:00:00Z"), result.info.createdAt)
        assertEquals(300.0, result.info.dpi)
        assertEquals(CanvasUnit.Millimeters, result.info.unit)
        assertEquals(42, result.info.strokeCount)
        assertTrue(state.background.hidden)
        assertEquals(0.2f, state.background.color.green)
        val sketch = state.layers[0] as RasterLayer
        assertEquals("Sketch", sketch.name)
        assertEquals(0.5f, sketch.opacity)
        assertEquals(BlendMode.Multiply, sketch.blend)
        assertTrue(sketch.locked && sketch.alphaLock && sketch.isReference)
        assertEquals(false, sketch.mask?.enabled)
        assertEquals(true, sketch.mask?.inverted)
        val group = state.layers[1] as LayerGroup
        assertTrue(group.collapsed)
        assertEquals(BlendMode.Screen, group.blend)
        val title = group.children.single() as RasterLayer
        assertTrue(title.clipping)
        assertEquals(title.id, state.activeLayerId)
    }

    @Test
    fun rewritePreservesUnknownFieldsAndTextLayers() {
        val result = success(manifest("future-fields.json"))
        assertEquals(listOf("text/b0000000-0000-4000-8000-000000000002.json"), result.textModelFiles.values.toList())
        val model = JsonObject(mapOf("content" to JsonArray(emptyList())))
        val withText = result.state.withTextModels(result.textModelFiles.mapValues { model })
        val renamed = withText.setOpacity(withText.layers[0].id, 0.25f)
        val written = NanquimManifest.write(renamed, result.info, result.uuids, "0.3.0", result.manifest)
        for (key in listOf("selection", "guides", "author", "animation", "extensions")) {
            assertEquals(result.manifest[key], written[key], key)
        }
        assertEquals(3, written["canvas"]!!.jsonObject["bleed"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, written["stats"]!!.jsonObject["undoCount"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, written["version"]!!.jsonPrimitive.content.toInt())
        val layers = written["layers"]!!.jsonArray
        val sketch = layers[0].jsonObject
        assertEquals(JsonPrimitive(0.25f), sketch["opacity"])
        assertNotNull(sketch["futureLayerField"])
        assertNull(sketch["contentBounds"])
        assertEquals("0.7", sketch["mask"]!!.jsonObject["density"]!!.jsonPrimitive.content)
        val group = layers[1].jsonObject
        assertEquals("red", group["color"]!!.jsonPrimitive.content)
        val title = (group["children"] as JsonArray)[0].jsonObject
        assertEquals("text", title["type"]!!.jsonPrimitive.content)
        assertEquals("text/b0000000-0000-4000-8000-000000000002.json", title["textModel"]!!.jsonPrimitive.content)
        assertEquals("a0000000-0000-4000-8000-000000000001", sketch["id"]!!.jsonPrimitive.content)
        assertEquals("a0000000-0000-4000-8000-000000000001", written["referenceLayerId"]!!.jsonPrimitive.content)
        val reread = success(String(NanquimManifest.encode(written)))
        assertEquals(structure(renamed.layers), structure(reread.state.layers))
    }

    @Test
    fun textLayersRoundTripThroughTheirModelFiles() {
        val model = JsonObject(mapOf("content" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive("Hi")))))))
        val (state, textId) = DocumentState(800, 600).addRasterLayer("Paint").first.addTextLayer(TextLayerContent(model))
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val written = NanquimManifest.write(state, DocumentInfo.new("Text", Instant.EPOCH), uuids, "0.3.0")
        val node = written["layers"]!!.jsonArray[1].jsonObject
        val file = NanquimManifest.textModelFile(uuids.uuidOf(textId)!!)
        assertEquals("text", node["type"]!!.jsonPrimitive.content)
        assertEquals(file, node["textModel"]!!.jsonPrimitive.content)
        assertEquals("raster", written["layers"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val reread = success(String(NanquimManifest.encode(written)))
        assertEquals(mapOf(textId to file), reread.textModelFiles)
        val restored = reread.state.withTextModels(mapOf(textId to model))
        assertEquals("Hi", restored.raster(textId)!!.text!!.plainText)
        val hostile = written.toString().replace(file, "../../etc/passwd")
        assertTrue(success(hostile).textModelFiles.isEmpty())
    }

    @Test
    fun privateLayersRoundTrip() {
        val (state, id) = DocumentState(800, 600).addRasterLayer("Secret")
        val marked = state.setPrivate(id, true)
        val uuids = LayerUuids.EMPTY.assignedFor(marked)
        val written = NanquimManifest.write(marked, DocumentInfo.new("Private", Instant.EPOCH), uuids, "0.3.0")
        assertEquals(true, written["layers"]!!.jsonArray[0].jsonObject["private"]!!.jsonPrimitive.boolean)
        assertTrue(success(String(NanquimManifest.encode(written))).state.raster(id)!!.isPrivate)
        val cleared = NanquimManifest.write(marked.setPrivate(id, false), DocumentInfo.new("Private", Instant.EPOCH), uuids, "0.3.0", written)
        assertEquals(null, cleared["layers"]!!.jsonArray[0].jsonObject["private"])
    }

    @Test
    fun videoLayersRoundTripAndHostilePathsAreDropped() {
        val content = VideoLayerContent("clip.mp4", 1_500_000, 4_000_000)
        val (state, videoId) = DocumentState(800, 600).addVideoLayer("Clip", content)
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val written = NanquimManifest.write(state, DocumentInfo.new("Video", Instant.EPOCH), uuids, "0.3.0")
        val node = written["layers"]!!.jsonArray[0].jsonObject
        assertEquals("raster", node["type"]!!.jsonPrimitive.content)
        assertEquals("videos/clip.mp4", node["video"]!!.jsonObject["file"]!!.jsonPrimitive.content)
        val reread = success(String(NanquimManifest.encode(written)))
        assertEquals(content, reread.state.raster(videoId)!!.video)
        val rasterized = NanquimManifest.write(state.rasterizeVideo(videoId), DocumentInfo.new("Video", Instant.EPOCH), uuids, "0.3.0", written)
        assertEquals(null, rasterized["layers"]!!.jsonArray[0].jsonObject["video"])
        for (path in listOf("../clip.mp4", "videos/../clip.mp4", "videos/a/clip.mp4", "videos/")) {
            val hostile = written.toString().replace("videos/clip.mp4", path)
            assertEquals(null, success(hostile).state.raster(videoId)!!.video, path)
        }
    }

        @Test
    fun newDocumentRoundTrip() {
        var state = DocumentState(1600, 900)
        state = state.addRasterLayer("One").first
        val (withTwo, two) = state.addRasterLayer("Two")
        state = withTwo.setBlend(two, BlendMode.Overlay).setClipping(two, true)
        state = state.addMask(two).first
        val (withThree, three) = state.addRasterLayer("Three")
        state = withThree.groupLayers(listOf(two, three), "Group").first.setReference(three, true).setAssisted(three, true)
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val info = DocumentInfo.new("Round trip", Instant.parse("2026-10-14T10:00:00Z"))
        val written = NanquimManifest.write(state, info, uuids, "0.3.0")
        val read = success(String(NanquimManifest.encode(written)))
        assertEquals(structure(state.layers), structure(read.state.layers))
        assertEquals(1, written.toString().split("\"assisted\"").size - 1)
        assertEquals(state.background, read.state.background)
        assertEquals(info, read.info)
        assertEquals(uuids.uuidOf(state.activeLayerId!!), read.uuids.uuidOf(read.state.activeLayerId!!))
        val surfaces = NanquimManifest.surfaces(read.state, read.uuids)
        assertEquals(4, surfaces.size)
        assertEquals(1, surfaces.count { it.format == SurfaceFormat.R8 })
    }

    @Test
    fun colorProfileRoundTrip() {
        val state = DocumentState(64, 64).addRasterLayer("A").first
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val info = DocumentInfo.new("Profiles", Instant.parse("2026-10-20T10:00:00Z"))
        val srgb = NanquimManifest.write(state, info, uuids, "0.3.0")
        val srgbProfile = srgb.getValue("canvas").jsonObject.getValue("colorProfile").jsonObject
        assertEquals(NanquimManifest.SRGB_PROFILE_NAME, srgbProfile.getValue("name").jsonPrimitive.content)
        assertEquals(JsonNull, srgbProfile["icc"])
        assertNull(success(String(NanquimManifest.encode(srgb))).colorProfile)

        val p3 = DocumentColorProfile("Display P3", byteArrayOf(1, 2, 3))
        val written = NanquimManifest.write(state, info.copy(colorProfile = p3), uuids, "0.3.0", srgb)
        val read = success(String(NanquimManifest.encode(written)))
        assertEquals(ManifestColorProfile("Display P3", NanquimManifest.PROFILE_FILE), read.colorProfile)
        assertEquals(p3, DocumentColorProfile("Display P3", byteArrayOf(1, 2, 3)))
        assertFalse(p3 == DocumentColorProfile("Display P3", byteArrayOf(1, 2, 4)))
    }

    @Test
    fun cmykProfileRoundTrip() {
        val state = DocumentState(64, 64).addRasterLayer("A").first
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val info = DocumentInfo.new("Print", Instant.parse("2026-10-23T10:00:00Z"))
        val rgb = NanquimManifest.write(state, info, uuids, "0.3.0")
        assertEquals(JsonNull, rgb.getValue("canvas").jsonObject["cmykProfile"])
        assertNull(success(String(NanquimManifest.encode(rgb))).cmykProfile)

        val cmyk = DocumentColorProfile("Nanquim CMYK", byteArrayOf(4, 5, 6))
        val written = NanquimManifest.write(state, info.copy(cmykProfile = cmyk), uuids, "0.3.0", rgb)
        val read = success(String(NanquimManifest.encode(written)))
        assertEquals(ManifestColorProfile("Nanquim CMYK", NanquimManifest.CMYK_PROFILE_FILE), read.cmykProfile)
        assertNull(read.colorProfile)

        val back = NanquimManifest.write(state, info, uuids, "0.3.0", written)
        assertNull(success(String(NanquimManifest.encode(back))).cmykProfile)
    }

    @Test
    fun authorRoundTripKeepsItsOtherFields() {
        val result = success(manifest("future-fields.json"))
        assertEquals(ArtistInfo("Someone"), result.info.artist)
        val signed = result.info.copy(artist = ArtistInfo("Ana", listOf(listOf(0f, 0.5f, 1f, 0.25f))))
        val written = NanquimManifest.write(result.state, signed, result.uuids, "0.3.0", result.manifest)
        assertEquals("", written["author"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        val read = success(String(NanquimManifest.encode(written)))
        assertEquals(signed.artist, read.info.artist)
        val hostile = JsonArray(listOf(JsonPrimitive(7), JsonPrimitive(-1), JsonPrimitive(0.5)))
        assertEquals(ArtistInfo("", listOf(listOf(1f, 0f))), ArtistInfo.read(JsonObject(mapOf("signature" to JsonArray(listOf(hostile))))))
        assertNull(ArtistInfo.read(JsonObject(mapOf("name" to JsonPrimitive(" ")))))
    }

    @Test
    fun selectionsRoundTrip() {
        var state = DocumentState(300, 200).addRasterLayer("A").first
        val first = state.nextId
        state = state.copy(selectionId = first, savedSelections = listOf(SavedSelection(first + 1, "Kept")), nextId = first + 2)
        val uuids = LayerUuids.EMPTY.assignedFor(state)
        val written = NanquimManifest.write(state, DocumentInfo.new("S", Instant.parse("2026-10-15T10:00:00Z")), uuids, "0.3.0")
        val selection = written["selection"]!!.jsonObject
        assertEquals("selections/" + uuids.uuidOf(state.selectionId!!), selection["active"]!!.jsonPrimitive.content)
        val read = success(String(NanquimManifest.encode(written)))
        assertEquals(uuids.uuidOf(state.selectionId!!), read.uuids.uuidOf(read.state.selectionId!!))
        assertEquals(listOf("Kept"), read.state.savedSelections.map { it.name })
        assertEquals(uuids.uuidOf(state.savedSelections[0].id), read.uuids.uuidOf(read.state.savedSelections[0].id))
        val surfaces = NanquimManifest.surfaces(read.state, read.uuids).filter { it.format == SurfaceFormat.SelectionR8 }
        assertEquals(2, surfaces.size)
        assertTrue(surfaces.all { it.folder.startsWith("selections/") })
        assertEquals(read.state.pixelSurfaceIds().size, 3)
        assertTrue(read.state.nextId > read.state.pixelSurfaceIds().max())
        val cleared = read.state.copy(selectionId = null, savedSelections = emptyList())
        val none = NanquimManifest.write(cleared, read.info, read.uuids, "0.3.0", written)
        assertEquals(JsonNull, none["selection"]!!.jsonObject["active"])
    }

    @Test
    fun uuidsAreStableAndOnlyNewIdsGetNewOnes() {
        var state = DocumentState(100, 100).addRasterLayer("A").first
        val first = LayerUuids.EMPTY.assignedFor(state)
        val (next, id) = state.addRasterLayer("B")
        state = next
        val second = first.assignedFor(state)
        assertEquals(first.uuidOf(state.layers[0].id), second.uuidOf(state.layers[0].id))
        assertNotNull(second.uuidOf(id))
        assertFalse(first.ids.contains(id))
    }

    @Test
    fun theColorDepthRoundTrips() {
        val state = DocumentState(64, 32, pixelFormat = PixelFormat.Rgba16f).addRasterLayer("A").first
        val info = DocumentInfo.new("Deep", Instant.EPOCH)
        val written = NanquimManifest.write(state, info, LayerUuids.EMPTY.assignedFor(state), "test")
        val canvas = written["canvas"] as JsonObject
        assertEquals("rgba16f_premul_linear", (canvas["pixelFormat"] as JsonPrimitive).content)
        val read = NanquimManifest.parse(String(NanquimManifest.encode(written))) as ManifestReadResult.Success
        assertEquals(PixelFormat.Rgba16f, read.state.pixelFormat)
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertInstanceOf(ManifestReadResult.NeedsNewerApp::class.java, NanquimManifest.parse(manifest("newer-reader.json")))
        val halfFloat = NanquimManifest.parse(manifest("half-float.json"))
        assertInstanceOf(ManifestReadResult.Success::class.java, halfFloat)
        assertEquals(PixelFormat.Rgba16f, (halfFloat as ManifestReadResult.Success).state.pixelFormat)
        assertInstanceOf(
            ManifestReadResult.Unsupported::class.java,
            NanquimManifest.parse(manifest("half-float.json").replace("rgba16f_premul_linear", "rgba32f_premul_linear")),
        )
        assertInstanceOf(ManifestReadResult.Invalid::class.java, NanquimManifest.parse(manifest("duplicate-ids.json")))
        assertInstanceOf(ManifestReadResult.Invalid::class.java, NanquimManifest.parse("[1, 2]"))
        assertInstanceOf(ManifestReadResult.Invalid::class.java, NanquimManifest.parse("{not json"))
        assertInstanceOf(ManifestReadResult.Invalid::class.java, NanquimManifest.parse("""{"format":"nanquim","canvas":{"width":0,"height":5}}"""))
        val deep = (1..40).fold("""{"type":"raster","id":"leaf"}""") { inner, level -> """{"type":"group","id":"g$level","children":[$inner]}""" }
        assertInstanceOf(
            ManifestReadResult.Invalid::class.java,
            NanquimManifest.parse("""{"format":"nanquim","canvas":{"width":5,"height":5},"layers":[$deep]}"""),
        )
    }

    @Test
    fun minimalManifestGetsDefaults() {
        val result = success(manifest("minimal.json"))
        assertEquals(64, result.state.widthPx)
        assertTrue(result.state.layers.isEmpty())
        assertNull(result.state.activeLayerId)
        assertEquals(DocumentInfo.DEFAULT_DPI, result.info.dpi)
    }

    private fun structure(nodes: List<LayerNode>): List<Any> =
        nodes.map { node ->
            when (node) {
                is RasterLayer ->
                    listOf("raster", node.name, node.opacity, node.blend, node.hidden, node.locked, node.alphaLock, node.clipping, node.isReference, node.assisted) +
                        listOfNotNull(node.mask?.let { listOf(it.enabled, it.inverted) })
                is LayerGroup -> listOf("group", node.name, node.opacity, node.blend, node.hidden, node.locked, node.collapsed, structure(node.children))
            }
        }
}
