package com.mobyle.nanquim.engine

import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(Parameterized::class)
class CompositePreviewTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "preview-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    private fun await(request: ((SceneRender) -> Unit) -> Unit): SceneRender {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        request {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "readback timed out" }
        return checkNotNull(result).also { assertNull(it.error) }
    }

    @Test
    fun thePreviewIsTheCompositePremultipliedAtTheFittingLevel() {
        val state = DocumentState(600, 400, background = Background(hidden = true)).addRasterLayer("Layer").first
        assertNull(engine.openDocument(state.widthPx, state.heightPx, state.toEngineLayerTree().toString()))
        val layer = state.rasterLayers().single().id
        val samples = StrokeSampleBuffer()
        for (index in 0 until 30) {
            samples.add(
                x = 40f + index * 18f,
                y = 60f + index * 9f,
                pressure = 1f,
                eventTimeNanos = index * 4_000_000L,
                toolType = MotionEvent.TOOL_TYPE_STYLUS,
            )
        }
        val style = StrokeStyle(sizePx = 40f, opacity = 0.5f, red = 1f, green = 0.2f, blue = 0f, historyEntry = 1)
        engine.beginStroke(StrokeTool.Paint, layer, style)
        engine.addStrokeSamples(samples)
        engine.endStroke()

        val composite = await { engine.readComposite(it) }
        val full = await { engine.requestCompositePreview(1000, it) }
        assertEquals(600, full.width)
        assertEquals(400, full.height)
        var differing = 0
        var maximum = 0
        for (pixel in 0 until 600 * 400) {
            val alpha = composite.pixels[pixel * 4 + 3].toInt() and 0xFF
            for (channel in 0 until 4) {
                val straight = composite.pixels[pixel * 4 + channel].toInt() and 0xFF
                val expected = if (channel == 3) straight else (straight * alpha + 127) / 255
                val difference = abs((full.pixels[pixel * 4 + channel].toInt() and 0xFF) - expected)
                maximum = maxOf(maximum, difference)
                if (difference > 1) differing++
            }
        }
        assertEquals("channels off by more than 1 (max $maximum)", 0, differing)
        assertTrue("the stroke is in the preview", (0 until 600 * 400).any { (full.pixels[it * 4 + 3].toInt() and 0xFF) in 100..160 })
        val half = await { engine.requestCompositePreview(300, it) }
        assertEquals(300, half.width)
        assertEquals(200, half.height)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
