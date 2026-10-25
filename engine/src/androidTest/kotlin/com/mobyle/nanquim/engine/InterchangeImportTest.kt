package com.mobyle.nanquim.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addMask
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.setBlend
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(Parameterized::class)
class InterchangeImportTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine
    private lateinit var folder: File
    private lateinit var source: File
    private lateinit var secondFolder: File
    private lateinit var exported: File

    @Before
    fun setUp() {
        val scratch = File(context.cacheDir, "import-test-scratch-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
        folder = File(context.filesDir, "import-test-document").apply { deleteRecursively() }
        source = File(context.cacheDir, "import-test.psd")
        secondFolder = File(context.filesDir, "import-test-document-2").apply { deleteRecursively() }
        exported = File(context.cacheDir, "export-test.psd")
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        folder.deleteRecursively()
        source.delete()
        secondFolder.deleteRecursively()
        exported.delete()
    }

    @Test
    fun importedPsdComposesOnTheGpu() {
        source.writeBytes(twoLayerPsd())
        val pixels = importAndCompose(source, folder)

        assertPixel(pixels, 50, 50, 0, 0, 255)
        assertPixel(pixels, 260, 260, 0, 0, 128)
        assertPixel(pixels, 150, 260, 0, 0, 255)
    }

    @Test
    fun exportedPsdImportsToTheSamePicture() {
        source.writeBytes(twoLayerPsd())
        val original = importAndCompose(source, folder)
        engine.closeDocument()

        val tree = File(folder, "import.json").readText()
        val result = InterchangeFiles.exportPsd(folder.path, tree, "Background", original, WIDTH, HEIGHT, exported.path)
        assertTrue("${result.errorCode} ${result.errorDetail}", result.ok)
        assertTrue(result.warnings.isEmpty())

        val again = importAndCompose(exported, secondFolder)
        val differing = original.indices.count { abs((original[it].toInt() and 0xFF) - (again[it].toInt() and 0xFF)) > 0 }
        assertEquals(0, differing)
    }

    @Test
    fun exportedTiffImportsToTheSamePicture() {
        val composite = ByteArray(WIDTH * HEIGHT * 4)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val offset = (y * WIDTH + x) * 4
                composite[offset] = x.toByte()
                composite[offset + 1] = y.toByte()
                composite[offset + 2] = (x xor y).toByte()
                composite[offset + 3] = 255.toByte()
            }
        }
        val exportedTiff = File(context.cacheDir, "export-test.tif")
        try {
            val result = InterchangeFiles.exportTiff(composite, WIDTH, HEIGHT, 144.0, exportedTiff.path)
            assertTrue("${result.errorCode} ${result.errorDetail}", result.ok)
            assertTrue(folder.mkdirs())
            val imported = InterchangeFiles.importTiff(exportedTiff.path, folder.path, maximumSide = 16_384)
            assertTrue("${imported.errorCode} ${imported.errorDetail}", imported.ok)
            val tree = JSONObject(File(folder, "import.json").readText())
            assertEquals(144.0, tree.getDouble("dpi"), 1e-9)
            val uuid = tree.getJSONArray("layers").getJSONObject(0).getString("uuid")
            val state = DocumentState(WIDTH, HEIGHT).addRasterLayer("only").first
            val layer = state.rasterLayers().single()
            assertNull(
                engine.openDocumentFolder(
                    WIDTH,
                    HEIGHT,
                    state.toEngineLayerTree().toString(),
                    "tiff-test",
                    folder.path,
                    listOf(SurfaceFolder(layer.id, "layers/$uuid", false)),
                ),
            )
            assertTrue(composite.contentEquals(composite()))
        } finally {
            exportedTiff.delete()
        }
    }

    @Test
    fun pdfHasOnePagePerLayerOrOneFlattenedPage() {
        source.writeBytes(twoLayerPsd())
        val flattened = importAndCompose(source, folder)
        val tree = File(folder, "import.json").readText()
        val pdf = File(context.cacheDir, "export-test.pdf")
        try {
            for ((layersAsPages, pages) in listOf(false to 1, true to 2)) {
                val result =
                    InterchangeFiles.exportPdf(
                        documentFolder = folder.path,
                        layerTree = tree,
                        composite = flattened,
                        width = WIDTH,
                        height = HEIGHT,
                        layersAsPages = layersAsPages,
                        jpeg = layersAsPages,
                        jpegQuality = 90,
                        title = "Test",
                        outputPath = pdf.path,
                    )
                assertTrue("${result.errorCode} ${result.errorDetail}", result.ok)
                val text = String(pdf.readBytes(), Charsets.ISO_8859_1)
                assertTrue(text.startsWith("%PDF-1.4"))
                assertTrue(text.trimEnd().endsWith("%%EOF"))
                assertTrue(text.contains("/Type /Pages /Kids [") && text.contains("/Count $pages >>"))
            }
        } finally {
            pdf.delete()
        }
    }

    private fun importAndCompose(
        file: File,
        target: File,
    ): ByteArray {
        assertTrue(target.mkdirs())
        val result = InterchangeFiles.importPsd(file.path, target.path, maximumSide = 16_384, maximumLayers = 100)
        assertTrue("${result.errorCode} ${result.errorDetail}", result.ok)

        val layers = JSONObject(File(target, "import.json").readText()).getJSONArray("layers")
        assertEquals(2, layers.length())
        val bottomUuid = layers.getJSONObject(0).getString("uuid")
        val top = layers.getJSONObject(1)
        assertEquals("multiply", top.getString("blend"))
        val topUuid = top.getString("uuid")
        val maskUuid = top.getJSONObject("mask").getString("uuid")

        var state = DocumentState(WIDTH, HEIGHT).addRasterLayer("bottom").first
        val (withTop, topId) = state.addRasterLayer("top")
        state = withTop.setBlend(topId, BlendMode.Multiply).addMask(topId).first
        val (bottomLayer, topLayer) = state.rasterLayers()
        val surfaces =
            listOf(
                SurfaceFolder(bottomLayer.id, "layers/$bottomUuid", false),
                SurfaceFolder(topLayer.id, "layers/$topUuid", false),
                SurfaceFolder(topLayer.mask!!.id, "masks/$maskUuid", true),
            )
        assertNull(engine.openDocumentFolder(WIDTH, HEIGHT, state.toEngineLayerTree().toString(), "import-test", target.path, surfaces))
        return composite()
    }

    private fun composite(): ByteArray {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.readComposite {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "composite readback timed out" }
        return checkNotNull(result).also { assertNull(it.error) }.pixels
    }

    private fun assertPixel(
        pixels: ByteArray,
        x: Int,
        y: Int,
        red: Int,
        green: Int,
        blue: Int,
    ) {
        val offset = (y * WIDTH + x) * 4
        val actual = (0 until 3).map { pixels[offset + it].toInt() and 0xFF }
        val expected = listOf(red, green, blue)
        assertTrue("($x, $y): $actual, expected $expected", actual.zip(expected).all { (a, e) -> abs(a - e) <= 2 })
    }

    private fun twoLayerPsd(): ByteArray {
        val top = Rect(100, 100, 400, 400)
        val mask = Rect(100, 100, 200, 400)
        val layers =
            listOf(
                Layer(
                    Rect(0, 0, WIDTH, HEIGHT),
                    "norm",
                    listOf(0 to plane(WIDTH, HEIGHT, 0), 1 to plane(WIDTH, HEIGHT, 0), 2 to plane(WIDTH, HEIGHT, 255)),
                    null,
                ),
                Layer(
                    top,
                    "mul ",
                    listOf(
                        -1 to plane(top.width, top.height, 128),
                        0 to plane(top.width, top.height, 255),
                        1 to plane(top.width, top.height, 0),
                        2 to plane(top.width, top.height, 0),
                        -2 to plane(mask.width, mask.height, 0),
                    ),
                    mask,
                ),
            )
        val info = ByteArrayOutputStream()
        DataOutputStream(info).use { out ->
            out.writeShort(layers.size)
            val encoded = layers.map { layer -> layer.channels.map { (id, samples) -> id to rleChannel(samples, if (id == -2) layer.mask!! else layer.bounds) } }
            layers.forEachIndexed { index, layer ->
                out.writeInt(layer.bounds.top)
                out.writeInt(layer.bounds.left)
                out.writeInt(layer.bounds.bottom)
                out.writeInt(layer.bounds.right)
                out.writeShort(layer.channels.size)
                encoded[index].forEach { (id, data) ->
                    out.writeShort(id)
                    out.writeInt(data.size)
                }
                out.writeBytes("8BIM")
                out.writeBytes(layer.blend)
                out.write(byteArrayOf(-1, 0, 0, 0))
                val extra = ByteArrayOutputStream()
                DataOutputStream(extra).use { data ->
                    val maskRect = layer.mask
                    if (maskRect == null) {
                        data.writeInt(0)
                    } else {
                        data.writeInt(20)
                        data.writeInt(maskRect.top)
                        data.writeInt(maskRect.left)
                        data.writeInt(maskRect.bottom)
                        data.writeInt(maskRect.right)
                        data.write(255)
                        data.write(0)
                        data.writeShort(0)
                    }
                    data.writeInt(0)
                    data.write(byteArrayOf(0, 0, 0, 0))
                }
                out.writeInt(extra.size())
                out.write(extra.toByteArray())
            }
            encoded.forEach { channels -> channels.forEach { (_, data) -> out.write(data) } }
        }
        val section = ByteArrayOutputStream()
        DataOutputStream(section).use { out ->
            out.writeInt(info.size())
            out.write(info.toByteArray())
            out.writeInt(0)
        }
        val file = ByteArrayOutputStream()
        DataOutputStream(file).use { out ->
            out.writeBytes("8BPS")
            out.writeShort(1)
            out.write(ByteArray(6))
            out.writeShort(3)
            out.writeInt(HEIGHT)
            out.writeInt(WIDTH)
            out.writeShort(8)
            out.writeShort(3)
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(section.size())
            out.write(section.toByteArray())
            out.writeShort(0)
            out.write(ByteArray(WIDTH * HEIGHT * 3))
        }
        return file.toByteArray()
    }

    private data class Rect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private data class Layer(
        val bounds: Rect,
        val blend: String,
        val channels: List<Pair<Int, ByteArray>>,
        val mask: Rect?,
    )

    private fun plane(
        width: Int,
        height: Int,
        value: Int,
    ) = ByteArray(width * height) { value.toByte() }

    private fun rleChannel(
        samples: ByteArray,
        bounds: Rect,
    ): ByteArray {
        val rows =
            (0 until bounds.height).map { row ->
                val packed = ByteArrayOutputStream()
                val start = row * bounds.width
                var offset = 0
                while (offset < bounds.width) {
                    val count = minOf(128, bounds.width - offset)
                    packed.write(count - 1)
                    packed.write(samples, start + offset, count)
                    offset += count
                }
                packed.toByteArray()
            }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeShort(1)
            rows.forEach { data.writeShort(it.size) }
            rows.forEach { data.write(it) }
        }
        return out.toByteArray()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val WIDTH = 600
        private const val HEIGHT = 520
    }
}
