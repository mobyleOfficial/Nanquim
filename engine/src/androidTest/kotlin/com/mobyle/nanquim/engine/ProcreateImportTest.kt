package com.mobyle.nanquim.engine

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import com.mobyle.nanquim.formats.interchange.ImportJson
import com.mobyle.nanquim.formats.nanquim.NanquimManifest
import com.mobyle.nanquim.formats.nanquim.SurfaceFormat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(Parameterized::class)
class ProcreateImportTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine
    private lateinit var folder: File

    @Before
    fun setUp() {
        val scratch = File(context.cacheDir, "procreate-test-scratch-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 1024L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
        folder = File(context.filesDir, "procreate-test-document").apply { deleteRecursively() }
    }

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        folder.deleteRecursively()
    }

    @Test
    fun localArtworksComposeLikeTheirStoredComposite() {
        val directory = context.getExternalFilesDir(null)
        val artworks = directory?.listFiles { file -> file.name.endsWith(".procreate") }.orEmpty()
        assumeTrue("no local .procreate artworks in $directory", artworks.isNotEmpty())
        for (artwork in artworks) {
            val reference = File(artwork.path.removeSuffix(".procreate") + ".composite.png")
            if (!reference.isFile) continue
            folder.deleteRecursively()
            assertTrue(folder.mkdirs())
            val started = System.nanoTime()
            val result = InterchangeFiles.importProcreate(artwork.path, folder.path, maximumSide = 16_384, maximumLayers = 1000)
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue("${artwork.name}: ${result.errorCode} ${result.errorDetail}", result.ok)
            val imported = ImportJson.parse(File(folder, ImportJson.FILE_NAME).readText()) { "Layer $it" }
            val state = imported.state
            val surfaces =
                NanquimManifest.surfaces(state, imported.uuids).map { surface ->
                    SurfaceFolder(surface.id, surface.folder, isMask = surface.format == SurfaceFormat.R8)
                }
            assertNull(
                engine.openDocumentFolder(
                    state.widthPx,
                    state.heightPx,
                    state.toEngineLayerTree().toString(),
                    artwork.name,
                    folder.path,
                    surfaces,
                ),
            )
            val pixels = composite()
            engine.closeDocument()

            val expected = BitmapFactory.decodeFile(reference.path)
            assertTrue(expected.width == state.widthPx && expected.height == state.heightPx)
            val row = IntArray(expected.width)
            var differing = 0L
            var worst = 0
            for (y in 0 until expected.height) {
                expected.getPixels(row, 0, expected.width, 0, y, expected.width, 1)
                for (x in 0 until expected.width) {
                    val argb = row[x]
                    val offset = (y * expected.width + x) * 4
                    val difference =
                        maxOf(
                            abs((pixels[offset].toInt() and 0xFF) - ((argb shr 16) and 0xFF)),
                            abs((pixels[offset + 1].toInt() and 0xFF) - ((argb shr 8) and 0xFF)),
                            abs((pixels[offset + 2].toInt() and 0xFF) - (argb and 0xFF)),
                        )
                    worst = maxOf(worst, difference)
                    if (difference > 2) differing++
                }
            }
            val total = expected.width.toLong() * expected.height
            println(
                "ProcreateImportTest ${artwork.name}: import %.1f s, %d of %d pixels over 2/255, worst %d".format(
                    seconds,
                    differing,
                    total,
                    worst,
                ),
            )
            assertTrue("${artwork.name}: $differing of $total pixels differ (worst $worst)", differing * 1000 <= total)
        }
    }

    private fun composite(): ByteArray {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.readComposite {
            result = it
            done.countDown()
        }
        check(done.await(120, TimeUnit.SECONDS)) { "composite readback timed out" }
        return checkNotNull(result).also { assertNull(it.error) }.pixels
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
