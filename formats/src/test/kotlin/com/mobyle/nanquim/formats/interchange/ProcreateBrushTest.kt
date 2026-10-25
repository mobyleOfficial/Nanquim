package com.mobyle.nanquim.formats.interchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProcreateBrushTest {
    private val archive =
        Json.parseToJsonElement(
            """
            { "${'$'}class": "SilicaBrush", "name": "Ink ", "authorName": "Someone",
              "plotSpacing": 0.042, "plotJitter": 0.1, "plotSmoothing": 0.3,
              "bundledShapePath": "Brush-Preset-Hard.png", "oriented": true, "shapeRoundness": 0.5,
              "shapeScatter": 0.2, "shapeCount": 0.2, "shapeFlipXJitter": true, "shapeFilter": true,
              "textureMovement": 1.0, "textureScale": 0.36, "grainDepth": 0.8, "textureInverted": true,
              "dynamicsPressureSize": 0.6,
              "dynamicsPressureSizeCurve": { "${'$'}class": "ValkyrieMagnitudinalCurve",
                                             "points": ["{0.000000, 0.000000}", "{0.500000, 0.200000}", "{1.000000, 1.000000}"] },
              "dynamicsPressureOpacity": 1.0,
              "dynamicsPressureOpacityCurve": { "points": ["{0.000000, 0.000000}", "{1.000000, 1.000000}"] },
              "dynamicsJitterHue": 0.25, "pencilTaperStartLength": 0.1, "pencilTaperSize": 0.8,
              "paintSize": 0.5, "paintOpacity": 0.9, "blendMode": 0, "stamp": false }
            """.trimIndent(),
        ) as JsonObject

    @Test
    fun `keys map to brush json`() {
        val grain = byteArrayOf(1, 2, 3)
        val converted = ProcreateBrush.convert(archive, mapOf(ProcreateBrush.GRAIN to grain), "brush-1", "2026-10-15T00:00:00Z")
        val document = converted.brushPackage.document
        assertEquals("Ink", document.name)
        assertEquals("Someone", document.author)
        assertEquals("procreate", document.string("importedFrom"))
        assertEquals(0.042, document.number("path.spacing")!!, 1e-9)
        assertEquals(0.3, document.number("path.streamline")!!, 1e-9)
        assertNull(document.shapeTexture)
        assertEquals(1.0, document.number("shape.hardness")!!, 1e-9)
        assertEquals("followStroke", document.string("shape.rotationMode"))
        assertEquals(4, document.integer("shape.count"))
        assertEquals(0.5, document.number("shape.flipXJitter")!!, 1e-9)
        assertEquals("grain.png", document.grainTexture)
        assertArrayEquals(grain, converted.brushPackage.grainTextureBytes)
        assertEquals("moving", document.string("grain.mode"))
        assertEquals(0.36, document.number("grain.scale")!!, 1e-6)
        assertEquals(0.6, document.number("dynamics.size.pressureAmount")!!, 1e-9)
        assertEquals("curve:pressureSize", document.string("dynamics.size.pressure"))
        assertEquals("curve:linear", document.string("dynamics.opacity.pressure"))
        assertTrue(document.has("curves.pressureSize"))
        assertEquals(0.25, document.number("colorDynamics.hueJitter")!!, 1e-9)
        assertEquals(0.1, document.number("taper.stylus.start")!!, 1e-9)
        assertEquals(0.2, document.number("taper.stylus.minSize")!!, 1e-6)
        assertEquals(0.5, document.number("defaults.size")!!, 1e-9)
        assertEquals("SilicaBrush", document.string("extensions.procreate.${'$'}class"))
        val codes = converted.warnings.map { it.code }
        assertTrue("provisionalBrushMapping" in codes)
        assertTrue("brushShapeReplaced" in codes)
    }

    @Test
    fun `a shape file is used as the tip`() {
        val shape = byteArrayOf(9)
        val converted = ProcreateBrush.convert(archive, mapOf(ProcreateBrush.SHAPE to shape), "brush-2", "2026-10-15T00:00:00Z")
        assertEquals("shape.png", converted.brushPackage.document.shapeTexture)
        assertArrayEquals(shape, converted.brushPackage.shapeTextureBytes)
        assertNull(converted.brushPackage.document.grainTexture)
        assertTrue(converted.warnings.none { it.code == "brushShapeReplaced" })
    }

    @Test
    fun `other archives are rejected`() {
        val other = Json.parseToJsonElement("""{ "${'$'}class": "SilicaDocument" }""") as JsonObject
        assertThrows<ImportJsonException> { ProcreateBrush.convert(other, emptyMap(), "x", "now") }
    }

    @Test
    fun `points parse from text`() {
        assertEquals(0.5 to 0.25, ProcreateBrush.point("{0.500000, 0.250000}"))
        assertNull(ProcreateBrush.point("{nan, 1}"))
        assertNull(ProcreateBrush.point("garbage"))
    }

    @Test
    fun `brush set index`() {
        val plist =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>brushes</key><array><string>A-1</string><string>B-2</string></array>
              <key>iconName</key><string>icon</string>
              <key>name</key><string>Inks &amp; Washes</string>
            </dict></plist>
            """.trimIndent()
        assertEquals(ProcreateBrushSetIndex("Inks & Washes", listOf("A-1", "B-2")), ProcreateBrushSet.parseIndex(plist))
        assertNull(ProcreateBrushSet.parseIndex("<plist/>"))
    }
}

class ProcreateSwatchesTest {
    @Test
    fun `colors come from the hsb fields`() {
        val text =
            """
            { "name": "Test ", "colorProfiles": [ { "iccName": "sRGB IEC61966-2.1", "colorSpace": 0 } ],
              "swatches": [
                { "hue": 0.0, "saturation": 1.0, "brightness": 1.0, "alpha": 1, "origin": 2,
                  "components": [1, 0, 0] },
                { "hue": 0.3333333333, "saturation": 1.0, "brightness": 0.5, "alpha": 1, "origin": 1,
                  "components": [0.3333333333, 1.0, 0.5], "name": "Green" },
                null ] }
            """.trimIndent()
        val (palette, warnings) = ProcreateSwatches.parse(text)
        assertEquals("Test", palette.name)
        assertEquals(3, palette.swatches.size)
        val red = palette.swatches[0]!!.color
        assertEquals(1f, red.red, 1e-6f)
        assertEquals(0f, red.green, 1e-6f)
        val green = palette.swatches[1]!!
        assertEquals("Green", green.name)
        assertEquals(0.5f, green.color.green, 1e-4f)
        assertEquals(0f, green.color.red, 1e-4f)
        assertNull(palette.swatches[2])
        assertTrue(warnings.isEmpty())
        val json = Json.parseToJsonElement(palette.toJson("palette-1")) as JsonObject
        assertEquals("1", json["format"].toString())
        assertEquals(3, (json["swatches"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun `other profiles are reported`() {
        val (_, warnings) =
            ProcreateSwatches.parse("""{ "name": "P", "colorProfiles": [ { "iccName": "Display P3" } ], "swatches": [] }""")
        assertEquals(listOf(ImportWarning("colorProfileIgnored", "Display P3")), warnings)
    }

    @Test
    fun `hsb conversion covers every sector`() {
        for (step in 0..11) {
            val (r, g, b) = ProcreateSwatches.hsbToRgb(step / 12.0, 1.0, 1.0)
            assertTrue(maxOf(r, g, b) == 1.0 && minOf(r, g, b) == 0.0, "step $step")
        }
    }
}
