package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.reference.ReferenceCorner
import com.mobyle.nanquim.document.reference.ReferenceMode
import com.mobyle.nanquim.document.reference.ReferenceSettings
import com.mobyle.nanquim.document.reference.ReferenceView
import java.time.Instant

class ReferenceJsonTest {
    private val reference =
        ReferenceSettings(
            visible = true,
            mode = ReferenceMode.Image,
            x = 0.25,
            y = 0.75,
            width = 400.0,
            height = 300.0,
            docked = null,
            opacity = 0.6f,
            canvasView = ReferenceView(2.0, 0.3, 0.7),
            imageView = ReferenceView(5.5, 0.1, 0.9),
            hasImage = true,
        )

    private fun roundTrip(info: DocumentInfo): DocumentInfo {
        val written = NanquimManifest.write(DocumentState(800, 600), info, LayerUuids.EMPTY, "test")
        return (NanquimManifest.parse(String(NanquimManifest.encode(written))) as ManifestReadResult.Success).info
    }

    @Test
    fun theWindowRoundTripsThroughTheManifest() {
        val info = DocumentInfo.new("Doc", Instant.EPOCH)
        assertEquals(reference, roundTrip(info.copy(reference = reference)).reference)
        val docked = reference.copy(docked = ReferenceCorner.BottomStart, hasImage = false, mode = ReferenceMode.Canvas)
        assertEquals(docked, roundTrip(info.copy(reference = docked)).reference)
        assertNull(roundTrip(info).reference)
    }

    @Test
    fun unknownFieldsStayAndBrokenValuesAreClamped() {
        val previous =
            Json
                .parseToJsonElement(
                    """{"visible": true, "mode": "face", "opacity": 7, "camera": "front",
                    "window": {"x": 3, "y": -1, "width": 10, "docked": "middle", "pinned": true},
                    "canvasView": {"zoom": 100, "center": [2, "a"]}, "image": "elsewhere.png"}""",
                ).jsonObject
        val read = ReferenceJson.read(previous)!!
        assertEquals(ReferenceMode.Canvas, read.mode)
        assertEquals(1f, read.opacity)
        assertEquals(1.0, read.x)
        assertEquals(0.0, read.y)
        assertEquals(ReferenceSettings.MINIMUM_SIDE, read.width)
        assertEquals(ReferenceCorner.TopEnd, read.docked)
        assertEquals(ReferenceView(ReferenceView.MAXIMUM_ZOOM, 1.0, 0.5), read.canvasView)
        assertEquals(false, read.hasImage)
        assertSame(previous, ReferenceJson.write(read, previous))
        val written = ReferenceJson.write(read.copy(docked = null, hasImage = true), previous)
        assertEquals(JsonPrimitive("front"), written["camera"])
        assertEquals(JsonPrimitive(true), (written["window"] as JsonObject)["pinned"])
        assertEquals(JsonNull, (written["window"] as JsonObject)["docked"])
        assertEquals(JsonPrimitive(ReferenceJson.IMAGE_PATH), written["image"])
        assertNull(ReferenceJson.write(read.copy(opacity = 0.5f), written)["image"])
    }
}
