package com.mobyle.nanquim.engine

import android.content.Intent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Adjustment and transform previews draw the active layer through a layer override while no stroke
 * preview is set. The render loop composing those frames used to abort in Compositor::prepareTile,
 * which required a stroke preview whenever there was a stroke surface. The engine needs a window
 * here: reads are deferred while a session is open, so only the render loop composes the preview.
 */
@RunWith(Parameterized::class)
class LayerOverridePreviewTest(
    private val renderer: Renderer,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var activity: ProbeSurfaceActivity
    private lateinit var engine: Engine
    private var layer = 0

    @Before
    fun openPaintedDocumentInAWindow() {
        val intent = Intent(context, ProbeSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(intent) as ProbeSurfaceActivity
        val surface = activity.awaitSurface(10)
        assertNotNull("The window has no surface", surface)
        val scratch = File(context.cacheDir, "override-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
        instrumentation.runOnMainSync { engine.attachSurface(surface!!, activity.surfaceWidth, activity.surfaceHeight) }
        val state = DocumentState(600, 400, background = Background(hidden = true)).addRasterLayer("Layer").first
        assertNull(engine.openDocument(state.widthPx, state.heightPx, state.toEngineLayerTree().toString()))
        engine.setCamera(AffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        layer = state.rasterLayers().single().id
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
        engine.beginStroke(
            StrokeTool.Paint,
            layer,
            StrokeStyle(sizePx = 40f, opacity = 1f, red = 1f, green = 0.2f, blue = 0f, historyEntry = 1),
        )
        engine.addStrokeSamples(samples)
        engine.endStroke()
        readComposite()
    }

    @After
    fun closeEngine() {
        instrumentation.runOnMainSync {
            engine.detachSurface()
            engine.close()
        }
        activity.finish()
    }

    private fun readComposite() {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.readComposite {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "readback timed out" }
        assertNull(checkNotNull(result).error)
    }

    @Test
    fun anAdjustmentPreviewComposesWithoutAStrokePreview() {
        engine.beginAdjustment(
            layerId = layer,
            selectionId = 0,
            maskId = 0,
            filter = AdjustmentFilter.HueSaturationBrightness,
            values = floatArrayOf(0.5f, 0f, 0f),
            table = FloatArray(0),
            amount = 1f,
        )
        Thread.sleep(PREVIEW_MILLISECONDS)
        engine.endAdjustment(commit = false, historyEntry = 2)
        readComposite()
    }

    @Test
    fun aTransformPreviewComposesWithoutAStrokePreview() {
        engine.beginTransform(layerId = layer, selectionId = 0, requestId = 1)
        Thread.sleep(PREVIEW_MILLISECONDS)
        engine.endTransform(commit = false, historyEntry = 2)
        readComposite()
    }

    companion object {
        private const val PREVIEW_MILLISECONDS = 1500L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
