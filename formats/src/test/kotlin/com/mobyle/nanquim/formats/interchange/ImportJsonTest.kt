package com.mobyle.nanquim.formats.interchange

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.LayerUuids
import com.mobyle.nanquim.formats.nanquim.ManifestReadResult
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import java.time.Instant

class ImportJsonTest {
    private val sample =
        """
        {
          "version": 1, "source": "psd", "width": 640, "height": 480, "dpi": 300,
          "background": { "color": [1, 1, 1, 1], "hidden": true },
          "layers": [
            { "type": "raster", "uuid": "00000000-0000-4000-8000-000000000001", "name": "",
              "opacity": 1, "blend": "normal", "hidden": false, "locked": false,
              "alphaLock": false, "clipping": false, "collapsed": false, "mask": null },
            { "type": "group", "uuid": "00000000-0000-4000-8000-000000000002", "name": "Group",
              "opacity": 0.5, "blend": "passThrough", "hidden": true, "locked": false,
              "alphaLock": false, "clipping": false, "collapsed": true, "mask": null,
              "children": [
                { "type": "raster", "uuid": "00000000-0000-4000-8000-000000000003", "name": "Ink",
                  "opacity": 2.0, "blend": "multiply", "hidden": false, "locked": false,
                  "alphaLock": true, "clipping": true, "collapsed": false,
                  "mask": { "uuid": "00000000-0000-4000-8000-000000000004", "enabled": false,
                            "inverted": false } }
              ] },
            { "type": "raster", "uuid": "00000000-0000-4000-8000-000000000005", "name": "",
              "opacity": 1, "blend": "notAMode", "hidden": false, "locked": false,
              "alphaLock": false, "clipping": false, "collapsed": false, "mask": null }
          ],
          "warnings": [ { "code": "adjustmentLayerSkipped", "detail": "Levels 1" },
                        { "code": "cmykConverted", "detail": "" } ]
        }
        """.trimIndent()

    @Test
    fun `the file's color profile`() {
        assertNull(ImportJson.parse(sample) { "" }.colorProfileName)
        val withProfile =
            sample.replaceFirst(
                "\"dpi\": 300,",
                "\"dpi\": 300, \"colorProfile\": { \"name\": \"Display P3\", \"icc\": \"profile.icc\" },",
            )
        val imported = ImportJson.parse(withProfile) { "" }
        assertEquals("Display P3", imported.colorProfileName)
        assertEquals("profile.icc", imported.colorProfileFile)
        val nameOnly = sample.replaceFirst("\"dpi\": 300,", "\"dpi\": 300, \"colorProfile\": { \"name\": \"Display P3\", \"icc\": null },")
        assertNull(ImportJson.parse(nameOnly) { "" }.colorProfileFile)
        val escaping = sample.replaceFirst("\"dpi\": 300,", "\"dpi\": 300, \"colorProfile\": { \"name\": \"X\", \"icc\": \"../x.icc\" },")
        assertNull(ImportJson.parse(escaping) { "" }.colorProfileFile)
    }

    @Test
    fun `written trees read back the same`() {
        val imported = ImportJson.parse(sample) { index -> "Layer $index" }
        val locked =
            imported.state.copy(
                layers = imported.state.layers.map { node -> if (node is RasterLayer) node.copy(locked = true) else node },
            )
        val written = ImportJson.write(locked, imported.uuids, 300.0)
        val again = ImportJson.parse(written) { index -> "Unused $index" }
        assertEquals(locked, again.state)
        assertEquals(imported.uuids, again.uuids)
        assertEquals(300.0, again.dpi)
        assertEquals("nanquim", again.source)
        assertTrue(again.warnings.isEmpty())
    }

    @Test
    fun `writing needs a UUID for every surface`() {
        val imported = ImportJson.parse(sample) { index -> "Layer $index" }
        assertThrows<IllegalArgumentException> {
            ImportJson.write(imported.state, LayerUuids.EMPTY, 72.0)
        }
    }

    @Test
    fun `tree, ids, names and warnings`() {
        val imported = ImportJson.parse(sample) { index -> "Layer $index" }
        val state = imported.state
        assertEquals("psd", imported.source)
        assertEquals(300.0, imported.dpi)
        assertEquals(640, state.widthPx)
        assertTrue(state.background.hidden)
        assertEquals(3, state.layers.size)

        val bottom = state.layers[0] as RasterLayer
        assertEquals("Layer 1", bottom.name)
        val group = state.layers[1] as LayerGroup
        assertEquals("Group", group.name)
        assertEquals(BlendMode.PassThrough, group.blend)
        assertTrue(group.hidden)
        assertTrue(group.collapsed)
        val ink = group.children.single() as RasterLayer
        assertEquals(1f, ink.opacity)
        assertEquals(BlendMode.Multiply, ink.blend)
        assertTrue(ink.alphaLock)
        assertTrue(ink.clipping)
        assertFalse(ink.mask!!.enabled)
        val top = state.layers[2] as RasterLayer
        assertEquals("Layer 3", top.name)
        assertEquals(BlendMode.Normal, top.blend)

        assertEquals(listOf(1, 2, 3, 5), state.allNodes().map { it.id }.toList())
        assertEquals(4, ink.mask!!.id)
        assertEquals(top.id, state.activeLayerId)
        assertEquals(6, state.nextId)
        assertEquals("00000000-0000-4000-8000-000000000004", imported.uuids.uuidOf(4))

        assertEquals(
            listOf(ImportWarning("adjustmentLayerSkipped", "Levels 1"), ImportWarning("cmykConverted", "")),
            imported.warnings,
        )
    }

    @Test
    fun `the imported tree writes a manifest that reads back the same`() {
        val imported = ImportJson.parse(sample) { index -> "Layer $index" }
        val now = Instant.parse("2026-10-15T00:00:00Z")
        val info = DocumentInfo("artwork", "Imported", now, now, dpi = imported.dpi)
        val manifest = NanquimManifest.write(imported.state, info, imported.uuids, "test")
        val read = NanquimManifest.read(manifest) as ManifestReadResult.Success
        assertEquals(imported.state.layers, read.state.layers)
        assertEquals(imported.uuids, read.uuids)
    }

    @Test
    fun `empty layer list`() {
        val imported = ImportJson.parse("""{"version":1,"width":10,"height":10,"layers":[]}""") { "" }
        assertTrue(imported.state.layers.isEmpty())
        assertNull(imported.state.activeLayerId)
    }

    @Test
    fun `invalid files`() {
        assertThrows<ImportJsonException> { ImportJson.parse("[]") { "" } }
        assertThrows<ImportJsonException> { ImportJson.parse("""{"version":2,"width":1,"height":1}""") { "" } }
        assertThrows<ImportJsonException> { ImportJson.parse("""{"version":1,"width":0,"height":1}""") { "" } }
        val duplicate =
            """{"version":1,"width":1,"height":1,"layers":[
               {"type":"raster","uuid":"a"},{"type":"raster","uuid":"a"}]}"""
        assertThrows<ImportJsonException> { ImportJson.parse(duplicate) { "" } }
        val unknown = """{"version":1,"width":1,"height":1,"layers":[{"type":"text","uuid":"a"}]}"""
        assertThrows<ImportJsonException> { ImportJson.parse(unknown) { "" } }
    }
}
