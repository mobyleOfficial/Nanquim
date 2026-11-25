package com.mobyle.nanquim.engine

import android.content.Intent
import android.util.Log
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Strokes with history on a window: committing a stroke reads the tiles it covers back for undo,
 * and each readback inserts a GL fence. Mali's GLES driver (realme C53, SM-X510) dereferences null
 * in glFenceSync when the fence is the first GL work after eglSwapBuffers on an EGL_SINGLE_BUFFER
 * surface, which is where a stroke commit usually lands.
 */
@RunWith(AndroidJUnit4::class)
class FrontBufferHistoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun glesCommitsOverlappingStrokesWithHistoryOnAWindow() {
        val intent =
            Intent(context, ProbeSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ProbeSurfaceActivity
        val scratch = File(context.cacheDir, "front-buffer-history-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val surface = activity.awaitSurface(10)
            assertNotNull("The window has no surface", surface)
            val engine =
                Engine(
                    EngineConfig(
                        memoryBudgetBytes = 256L * 1024 * 1024,
                        scratchDirectory = scratch.absolutePath,
                        renderer = Renderer.Gles,
                    ),
                )
            try {
                engine.checkRenderer(Renderer.Gles)
                instrumentation.runOnMainSync { engine.attachSurface(surface!!, activity.surfaceWidth, activity.surfaceHeight) }
                val state = DocumentState(CANVAS, CANVAS).addRasterLayer("Layer 1").first
                assertNull(engine.openDocument(CANVAS, CANVAS, state.toEngineLayerTree().toString()))
                engine.setCamera(AffineTransform(1.0, 0.0, 0.0, 1.0, 20.0, 20.0))
                Thread.sleep(SETTLE_MILLISECONDS)
                for (index in 1..STROKES) {
                    val shade = (index % 10) / 10f
                    engine.beginStroke(
                        StrokeTool.Paint,
                        1,
                        StrokeStyle(
                            sizePx = 40f,
                            opacity = 1f,
                            red = shade,
                            green = 0.3f,
                            blue = 1 - shade,
                            historyEntry = index.toLong(),
                        ),
                    )
                    val offset = (index % 8) * 60f
                    if (index % 2 == 0) {
                        stroke(engine, 80f, 80f + offset, 900f, 700f - offset)
                    } else {
                        stroke(engine, 80f + offset, 700f, 900f - offset, 80f)
                    }
                    engine.endStroke()
                    Thread.sleep(STROKE_GAP_MILLISECONDS)
                }
                val history = settledHistory(engine)
                Log.i(TAG, "front buffer supported ${frontBufferSupported(engine)}; history $history")
                assertEquals("every stroke has an entry", STROKES.toLong(), history.entries)
                instrumentation.runOnMainSync { engine.detachSurface() }
            } finally {
                instrumentation.runOnMainSync { engine.close() }
            }
        } finally {
            activity.finish()
            scratch.deleteRecursively()
        }
    }

    private fun stroke(
        engine: Engine,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) {
        val samples = StrokeSampleBuffer()
        val count = 48
        for (index in 0 until count) {
            val t = index / (count - 1f)
            samples.add(
                x = x0 + (x1 - x0) * t,
                y = y0 + (y1 - y0) * t,
                pressure = 1f,
                eventTimeNanos = System.nanoTime(),
                toolType = MotionEvent.TOOL_TYPE_STYLUS,
            )
            if (index % 4 == 3) {
                engine.addStrokeSamples(samples)
                samples.clear()
                Thread.sleep(8)
            }
        }
        engine.addStrokeSamples(samples)
    }

    private fun stats(engine: Engine): EngineStats {
        val done = CountDownLatch(1)
        var result: EngineStats? = null
        engine.requestStats {
            result = it
            done.countDown()
        }
        check(done.await(30, TimeUnit.SECONDS)) { "stats timed out" }
        return checkNotNull(result)
    }

    private fun frontBufferSupported(engine: Engine): Boolean = stats(engine).latency?.frontBufferSupported == true

    private fun settledHistory(engine: Engine): HistoryStats {
        var history = checkNotNull(stats(engine).history)
        repeat(60) {
            if (history.capturesInFlight == 0L && history.entries == STROKES.toLong()) return history
            Thread.sleep(250)
            history = checkNotNull(stats(engine).history)
        }
        return history
    }

    private companion object {
        const val TAG = "FrontBufferHistory"
        const val CANVAS = 1200
        const val STROKES = 40
        const val SETTLE_MILLISECONDS = 1500L
        const val STROKE_GAP_MILLISECONDS = 120L
    }
}
