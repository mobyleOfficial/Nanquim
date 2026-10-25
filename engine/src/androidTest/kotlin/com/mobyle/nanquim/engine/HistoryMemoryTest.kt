package com.mobyle.nanquim.engine

import android.util.Log
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class HistoryMemoryTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine
    private val scratch = File(context.cacheDir, "history-memory-test-${renderer.name}")

    @Before
    fun createEngine() {
        scratch.deleteRecursively()
        scratch.mkdirs()
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = MEMORY_BUDGET_BYTES, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }
        scratch.deleteRecursively()
    }

    private fun composite(): ByteArray {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.readComposite {
            result = it
            done.countDown()
        }
        check(done.await(10, TimeUnit.MINUTES)) { "composite readback timed out" }
        return checkNotNull(result).also { assertNull(it.error) }.pixels
    }

    private fun stats(): EngineStats {
        val done = CountDownLatch(1)
        var result: EngineStats? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.requestStats {
                result = it
                done.countDown()
            }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "stats timed out" }
        return checkNotNull(result)
    }

    private fun canvasStroke(index: Int) {
        val samples = StrokeSampleBuffer()
        val passes = 5
        val pointsPerPass = 40
        var time = 0L
        for (pass in 0 until passes) {
            for (point in 0 until pointsPerPass) {
                val t = point / (pointsPerPass - 1f)
                val x = if (pass % 2 == 0) t * CANVAS else (1 - t) * CANVAS
                val y = (pass + t) / passes * CANVAS
                samples.add(x = x, y = y, pressure = 1f, eventTimeNanos = time, toolType = MotionEvent.TOOL_TYPE_STYLUS)
                time += 4_000_000L
            }
        }
        val shade = (index % 10) / 10f
        engine.beginStroke(StrokeTool.Paint, 1, StrokeStyle(sizePx = 900f, opacity = 0.5f, red = shade, green = 0.3f, blue = 1 - shade, historyEntry = index.toLong()))
        engine.addStrokeSamples(samples)
        engine.endStroke()
    }

    @Test
    fun twoHundredFiftyStrokesStayWithinBudgetAndUndo() {
        val state = DocumentState(CANVAS, CANVAS).addRasterLayer("Layer 1").first
        assertNull(engine.openDocument(CANVAS, CANVAS, state.toEngineLayerTree().toString()))
        engine.setHistoryRamBudget(HISTORY_RAM_BUDGET_BYTES)
        val initial = composite()
        var peakHistoryRam = 0L
        var peakTileBytes = 0L
        for (index in 1..STROKES) {
            canvasStroke(index)
            if (index % 10 == 0) {
                composite()
                val stats = stats()
                stats.history?.let { peakHistoryRam = maxOf(peakHistoryRam, it.ramBytes) }
                stats.tiles?.let { peakTileBytes = maxOf(peakTileBytes, it.gpuBytes + it.ramBytes) }
                Log.i(TAG, "after $index strokes: history ${stats.history}, tiles ${stats.tiles?.gpuBytes} GPU + ${stats.tiles?.ramBytes} RAM")
            }
        }
        var settled = stats()
        repeat(120) {
            val history = settled.history!!
            if (history.capturesInFlight == 0L && history.ramBytes <= history.ramBudgetBytes) return@repeat
            Thread.sleep(500)
            settled = stats()
        }
        val history = settled.history!!
        Log.i(TAG, "settled: $history; peak history RAM $peakHistoryRam, peak tiles $peakTileBytes")
        assertTrue("every stroke has an entry", history.entries == STROKES.toLong())
        assertTrue("history RAM within budget: ${history.ramBytes}", history.ramBytes <= history.ramBudgetBytes)
        assertTrue("older entries spilled to disk", history.diskBytes > 0)
        assertTrue("tiles within the engine budget: $peakTileBytes", peakTileBytes <= MEMORY_BUDGET_BYTES)

        for (batchEnd in STROKES downTo 1 step UNDO_BATCH) {
            val started = System.nanoTime()
            for (index in batchEnd downTo maxOf(1, batchEnd - UNDO_BATCH + 1)) engine.applyHistory(index.toLong(), undo = true)
            composite()
            Log.i(TAG, "undid down to ${maxOf(1, batchEnd - UNDO_BATCH + 1)} in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        assertArrayEquals("undoing 250 strokes restores the empty canvas", initial, composite())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val TAG = "HistoryMemoryTest"
        private const val CANVAS = 4096
        private const val STROKES = 250
        private const val MEMORY_BUDGET_BYTES = 1024L * 1024 * 1024
        private const val UNDO_BATCH = 25
        private const val HISTORY_RAM_BUDGET_BYTES = 48L * 1024 * 1024
    }
}
