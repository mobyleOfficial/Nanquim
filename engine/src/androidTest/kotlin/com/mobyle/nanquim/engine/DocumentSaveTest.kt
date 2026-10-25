package com.mobyle.nanquim.engine

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(Parameterized::class)
class DocumentSaveTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine
    private lateinit var folder: File

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "save-test-scratch-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
        folder = File(context.filesDir, "save-test-document").apply { deleteRecursively() }
    }

    @After
    fun closeEngine() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        folder.deleteRecursively()
    }

    private val state: DocumentState by lazy {
        var document = DocumentState(600, 520).addRasterLayer("A").first
        val (withB, b) = document.addRasterLayer("B")
        document = withB.setBlend(b, BlendMode.Multiply)
        document.addMask(b).first
    }

    private val surfaces: List<SurfaceFolder> by lazy {
        state.rasterLayers().flatMap { layer ->
            listOf(SurfaceFolder(layer.id, "layers/layer-${layer.id}", false)) +
                listOfNotNull(layer.mask?.let { SurfaceFolder(it.id, "masks/mask-${it.id}", true) })
        }
    }

    private fun open() = assertNull(engine.openDocumentFolder(state.widthPx, state.heightPx, state.toEngineLayerTree().toString(), "save-test", folder.path, surfaces))

    private fun save(): DocumentSaveResult {
        val done = CountDownLatch(1)
        var result: DocumentSaveResult? = null
        engine.saveDocument(surfaces, mapOf("manifest.json" to """{"format":"nanquim"}""".toByteArray())) {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "save timed out" }
        val saved = checkNotNull(result)
        assertTrue(saved.error ?: "", saved.ok)
        return saved
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

    private fun stroke(
        surface: Int,
        mask: Boolean,
        random: Random,
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
    ) {
        val samples = StrokeSampleBuffer()
        val count = 24
        for (index in 0 until count) {
            val t = index / (count - 1f)
            samples.add(
                x = from.first + (to.first - from.first) * t,
                y = from.second + (to.second - from.second) * t,
                pressure = 0.5f + 0.5f * random.nextFloat(),
                eventTimeNanos = index * 4_000_000L,
                toolType = MotionEvent.TOOL_TYPE_STYLUS,
            )
        }
        engine.beginStroke(StrokeTool.Paint, surface, StrokeStyle(sizePx = 40f, opacity = 0.9f, red = random.nextFloat(), green = 0.4f, blue = random.nextFloat(), maskTarget = mask))
        engine.addStrokeSamples(samples)
        engine.endStroke()
    }

    @Test
    fun savesIncrementallyAndReopensExactly() {
        val random = Random(6)
        val (a, b) = state.rasterLayers()
        open()
        stroke(a.id, false, random, 10f to 10f, 590f to 510f)
        stroke(b.id, false, random, 590f to 10f, 10f to 510f)
        stroke(b.id, true, random, 0f to 260f, 600f to 260f)
        val first = save()
        assertTrue(first.tilesWritten > 0)
        val painted = composite()

        assertEquals(0, save().tilesWritten)

        engine.closeDocument()
        open()
        assertArrayEquals(painted, composite())

        stroke(a.id, false, random, 100f to 100f, 120f to 110f)
        val small = save()
        assertEquals(1, small.tilesWritten)
        val changed = composite()
        engine.closeDocument()
        open()
        assertArrayEquals(changed, composite())
    }

    @Test
    fun damagedTileOpensEmptyWithWarning() =
        runBlocking {
            val random = Random(9)
            val layer = state.rasterLayers().first()
            open()
            stroke(layer.id, false, random, 10f to 10f, 590f to 510f)
            save()
            engine.closeDocument()
            val tile = File(folder, "layers/layer-${layer.id}/tiles/0_0.lz4")
            assertTrue(tile.isFile)
            tile.writeBytes(byteArrayOf(4, 34, 77, 24, 1, 2, 3))
            val warning = CompletableEvent()
            val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) { warning.value = engine.events.filterIsInstance<EngineEvent.DocumentTilesCorrupt>().first() }
            open()
            composite()
            withTimeout(10_000) { collector.join() }
            assertEquals(1L, warning.value?.count)
            save()
            assertFalse(tile.isFile && tile.readBytes().contentEquals(byteArrayOf(4, 34, 77, 24, 1, 2, 3)))
        }

    private class CompletableEvent {
        @Volatile
        var value: EngineEvent.DocumentTilesCorrupt? = null
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
