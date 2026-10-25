package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.guide.GridGuide
import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.guide.GuideType
import com.mobyle.nanquim.document.guide.PerspectiveGuide
import com.mobyle.nanquim.document.guide.SymmetryGuide
import com.mobyle.nanquim.document.guide.SymmetryKind
import com.mobyle.nanquim.document.guide.VanishingPoint
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import java.time.Instant

class GuidesJsonTest {
    private val guides =
        GuideSettings(
            type = GuideType.Isometric,
            visible = true,
            hue = 33f,
            opacity = 0.4f,
            thickness = 2f,
            grid = GridGuide(size = 64.0, rotation = 0.25, originX = 10.0, originY = 20.5),
            perspective = PerspectiveGuide(listOf(VanishingPoint(-300.0, 120.0), VanishingPoint(1400.5, 90.0))),
            symmetry =
                SymmetryGuide(SymmetryKind.Radial, segments = 12, rotational = true, centerX = 400.0, centerY = 300.0, rotation = -0.5),
        )

    @Test
    fun guidesRoundTripThroughTheManifest() {
        val state = DocumentState(800, 600).addRasterLayer("One").first
        val info = DocumentInfo.new("Doc", Instant.EPOCH).copy(guides = guides)
        val uuids =
            com.mobyle.nanquim.formats.nanquim.LayerUuids.EMPTY
                .assignedFor(state)
        val written = NanquimManifest.write(state, info, uuids, "test")
        val read = NanquimManifest.parse(String(NanquimManifest.encode(written))) as ManifestReadResult.Success
        assertEquals(guides, read.info.guides)
    }

    @Test
    fun documentsWithoutGuidesHaveNone() {
        val state = DocumentState(800, 600)
        val written =
            NanquimManifest.write(
                state,
                DocumentInfo.new("Doc", Instant.EPOCH),
                com.mobyle.nanquim.formats.nanquim.LayerUuids.EMPTY,
                "test",
            )
        assertEquals(null, written["guides"])
        assertNull((NanquimManifest.parse(String(NanquimManifest.encode(written))) as ManifestReadResult.Success).info.guides)
    }

    @Test
    fun unknownFieldsAndUnchangedGuidesAreKept() {
        val previous =
            Json.parseToJsonElement("""{"type": "grid2d", "grid": {"size": 50, "dash": [2, 2]}, "future": 1}""").jsonObject
        val read = GuidesJson.read(previous)!!
        assertEquals(previous, GuidesJson.write(read, previous))
        val changed = GuidesJson.write(read.copy(visible = true), previous)
        assertEquals("1", changed["future"].toString())
        assertEquals("[2,2]", (changed["grid"] as JsonObject)["dash"].toString())
        assertEquals("true", changed["visible"].toString())
    }

    @Test
    fun valuesAreClamped() {
        val read =
            GuidesJson.read(
                Json
                    .parseToJsonElement(
                        """{"type": "nope", "hue": -30, "opacity": 3, "thickness": 99, "grid": {"size": 0.1}}""",
                    ).jsonObject,
            )!!
        assertEquals(GuideType.Grid, read.type)
        assertEquals(330f, read.hue)
        assertEquals(1f, read.opacity)
        assertEquals(GuideSettings.MAXIMUM_THICKNESS, read.thickness)
        assertEquals(GridGuide.MINIMUM_SIZE, read.grid.size)
        assertNull(GuidesJson.read(null))
        val broken =
            GuidesJson.read(
                Json
                    .parseToJsonElement(
                        """{"perspective": {"points": [[1, 2], [3], "x", [4, 5], [6, 7], [8, 9]]},
                          "symmetry": {"kind": "spiral", "segments": 99}}""",
                    ).jsonObject,
            )!!
        assertEquals(listOf(VanishingPoint(1.0, 2.0), VanishingPoint(4.0, 5.0), VanishingPoint(6.0, 7.0)), broken.perspective.points)
        assertEquals(SymmetryKind.Vertical, broken.symmetry.kind)
        val legacy = GuidesJson.read(Json.parseToJsonElement("""{"type": "grid2d"}""").jsonObject, 800, 600)!!
        assertEquals(400.0, legacy.symmetry.centerX)
        assertEquals(300.0, legacy.symmetry.centerY)
        assertEquals(SymmetryGuide.MAXIMUM_SEGMENTS, broken.symmetry.segments)
    }
}
