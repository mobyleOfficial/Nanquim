package com.mobyle.nanquim.document.color

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.imageio.ImageIO

class PaletteExtractorTest {
    private fun solid(
        width: Int,
        height: Int,
        color: Int,
    ) = IntArray(width * height) { color }

    @Test
    fun aSolidImageGivesOneColor() {
        val colors = PaletteExtractor.extract(solid(40, 30, 0xFF3366CC.toInt()), 40, 30)
        assertEquals(listOf("#3366CC"), colors.map { it.toHex() })
    }

    @Test
    fun distinctColorsAreFoundAndNeutralsComeFirst() {
        val width = 90
        val pixels =
            IntArray(width * 10) { index ->
                when ((index % width) / 30) {
                    0 -> 0xFFE02818.toInt()
                    1 -> 0xFF808080.toInt()
                    else -> 0xFF1850E0.toInt()
                }
            }
        val colors = PaletteExtractor.extract(pixels, width, 10, count = 5).map { it.toHex() }
        assertEquals(listOf("#808080", "#E02818", "#1850E0"), colors)
    }

    @Test
    fun transparentPixelsAreIgnored() {
        val pixels = IntArray(100) { if (it < 50) 0x00FF0000 else 0xFF00FF00.toInt() }
        assertEquals(listOf("#00FF00"), PaletteExtractor.extract(pixels, 10, 10).map { it.toHex() })
        assertEquals(emptyList<ColorValue>(), PaletteExtractor.extract(IntArray(4), 2, 2))
    }

    @Test
    fun largeImagesAreDownscaledToTheLimit() {
        assertEquals(256 * 128, PaletteExtractor.downscale(solid(1024, 512, -1), 1024, 512).size)
        assertEquals(200 * 100, PaletteExtractor.downscale(solid(600, 300, -1), 600, 300).size)
    }

    @Test
    fun warmStartKeepsTheColorsSteady() {
        val bars = loadGolden("bars")
        val first = PaletteExtractor.extract(bars.pixels, bars.width, bars.height)
        val again =
            PaletteExtractor.extract(
                bars.pixels,
                bars.width,
                bars.height,
                initial = PaletteExtractor.centersOf(first),
                iterations = 3,
            )
        assertEquals(first.map { it.toHex() }, again.map { it.toHex() })
        val blobs = loadGolden("blobs")
        val before = PaletteExtractor.extract(blobs.pixels, blobs.width, blobs.height, count = 8)
        val after =
            PaletteExtractor.extract(
                blobs.pixels,
                blobs.width,
                blobs.height,
                count = 8,
                initial = PaletteExtractor.centersOf(before),
                iterations = 3,
            )
        val previous = PaletteExtractor.centersOf(before)
        val movement = PaletteExtractor.centersOf(after).map { color -> previous.minOf { it.distanceTo(color) } }
        assertTrue(movement.average() < 0.02, "average movement ${movement.average()}")
    }

    @Test
    fun goldenImagesGiveTheSavedPalettes() {
        val update = System.getProperty("nanquim.updateGoldens") == "true"
        for (name in GOLDENS) {
            val image = loadGolden(name)
            val palette = PaletteExtractor.extract(image.pixels, image.width, image.height).map { it.toHex() }
            assertEquals(palette, PaletteExtractor.extract(image.pixels, image.width, image.height).map { it.toHex() })
            val expectedFile = File(FOLDER, "$name.json")
            if (update) {
                expectedFile.writeText(JsonArray(palette.map(::JsonPrimitive)).toString() + "\n")
                continue
            }
            assertTrue(expectedFile.exists(), "Missing $expectedFile: run with -Dnanquim.updateGoldens=true")
            val expected = Json.parseToJsonElement(expectedFile.readText()).jsonArray.map { it.jsonPrimitive.content }
            assertEquals(expected, palette, name)
        }
    }

    private class Image(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    private fun loadGolden(name: String): Image {
        val image = ImageIO.read(File(FOLDER, "$name.png"))
        return Image(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    private companion object {
        val FOLDER = File("../goldens/palettes")
        val GOLDENS = listOf("bars", "gradient", "blobs", "noisy")
    }
}
