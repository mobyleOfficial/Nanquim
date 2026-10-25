package com.mobyle.nanquim.engine

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
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.addMask
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.duplicate
import com.mobyle.nanquim.document.layer.mergeDown
import com.mobyle.nanquim.document.layer.nodeBelow
import com.mobyle.nanquim.document.layer.setBlend
import com.mobyle.nanquim.document.layer.setOpacity
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(Parameterized::class)
class HistoryPropertyTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "history-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    private data class Step(
        val pixelEntries: List<Long> = emptyList(),
        val before: DocumentState? = null,
        val after: DocumentState? = null,
    )

    private fun composite(): ByteArray {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.readComposite {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "composite readback timed out" }
        val render = checkNotNull(result)
        assertNull(render.error)
        return render.pixels
    }

    private fun DocumentState.tree() = toEngineLayerTree().toString()

    private fun stroke(
        random: Random,
        state: DocumentState,
        entry: Long,
    ) {
        val layer = state.rasterLayers().random(random)
        val onMask = layer.mask != null && random.nextBoolean()
        val tool = if (random.nextInt(3) == 0) StrokeTool.Erase else StrokeTool.Paint
        val style =
            StrokeStyle(
                sizePx = random.nextInt(8, 90).toFloat(),
                opacity = 0.3f + 0.7f * random.nextFloat(),
                red = random.nextFloat(),
                green = random.nextFloat(),
                blue = random.nextFloat(),
                maskTarget = onMask,
                historyEntry = entry,
            )
        val samples = StrokeSampleBuffer()
        val x0 = random.nextFloat() * state.widthPx
        val y0 = random.nextFloat() * state.heightPx
        val x1 = random.nextFloat() * state.widthPx
        val y1 = random.nextFloat() * state.heightPx
        val count = 24
        for (index in 0 until count) {
            val t = index / (count - 1f)
            samples.add(
                x = x0 + (x1 - x0) * t,
                y = y0 + (y1 - y0) * t,
                pressure = 0.4f + 0.6f * random.nextFloat(),
                eventTimeNanos = index * 4_000_000L,
                toolType = MotionEvent.TOOL_TYPE_STYLUS,
            )
        }
        engine.beginStroke(tool, layer.id, style)
        engine.addStrokeSamples(samples)
        engine.endStroke()
    }

    private fun randomStep(
        random: Random,
        state: DocumentState,
        nextEntry: () -> Long,
    ): Step {
        val layers = state.rasterLayers()
        when (random.nextInt(10)) {
            0, 1, 2 -> {
                lastKind = "stroke"
                val entry = nextEntry()
                stroke(random, state, entry)
                return Step(listOf(entry))
            }
            3 -> {
                lastKind = "fill"
                val entry = nextEntry()
                val layer = layers.random(random)
                val mask = layer.mask != null && random.nextBoolean()
                engine.fillSurface(if (mask) layer.mask!!.id else layer.id, random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat(), mask, entry)
                return Step(listOf(entry))
            }
            4 -> {
                lastKind = "clear"
                val entry = nextEntry()
                engine.clearSurface(layers.random(random).id, entry)
                return Step(listOf(entry))
            }
            5 -> {
                lastKind = "invert"
                val entry = nextEntry()
                val layer = layers.random(random)
                val mask = layer.mask != null && random.nextBoolean()
                engine.invertSurface(if (mask) layer.mask!!.id else layer.id, mask, entry)
                return Step(listOf(entry))
            }
            6 -> {
                lastKind = "addLayer"
                val after = state.addRasterLayer("Layer").first
                assertNull(engine.setLayerTree(after.tree()))
                return Step(before = state, after = after)
            }
            7 -> {
                lastKind = "mergeDown"
                val upper = layers.firstOrNull { state.nodeBelow(it.id) is RasterLayer } ?: return Step()
                val lower = state.nodeBelow(upper.id) as RasterLayer
                val entry = nextEntry()
                val result = state.mergeDown(upper.id)
                val merged = DocumentState(state.widthPx, state.heightPx, layers = listOf<LayerNode>(lower.copy(blend = BlendMode.Normal), upper))
                assertNull(engine.composeIntoSurface(lower.id, merged.tree(), entry))
                assertNull(engine.setLayerTree(result.state.tree()))
                return Step(listOf(entry), before = state, after = result.state)
            }
            8 -> {
                lastKind = "duplicate"
                val result = state.duplicate(layers.random(random).id) { "$it copy" }
                result.surfaceCopies.forEach { (source, copy) -> engine.copySurface(source, copy) }
                assertNull(engine.setLayerTree(result.state.tree()))
                return Step(before = state, after = result.state)
            }
            else -> {
                lastKind = "property"
                val layer = layers.random(random)
                val after =
                    if (random.nextBoolean()) {
                        state.setBlend(layer.id, BlendMode.LAYER_MODES.random(random))
                    } else {
                        state.setOpacity(layer.id, random.nextFloat())
                    }
                assertNull(engine.setLayerTree(after.tree()))
                return Step(before = state, after = after)
            }
        }
    }

    @Test
    fun anUndoneStrokeDroppedAtOnceIsStillUndone() {
        var state = DocumentState(widthPx = 640, heightPx = 480)
        state = state.addRasterLayer("Layer 1").first
        val layer = state.rasterLayers().single().id

        fun circle(
            radius: Float,
            entry: Long,
        ) {
            val samples = StrokeSampleBuffer()
            for (index in 0..60) {
                val angle = index / 60.0 * 2 * Math.PI
                samples.add(
                    x = 320 + radius * kotlin.math.cos(angle).toFloat(),
                    y = 240 + radius * kotlin.math.sin(angle).toFloat(),
                    pressure = 1f,
                    eventTimeNanos = index * 4_000_000L,
                    toolType = MotionEvent.TOOL_TYPE_STYLUS,
                )
            }
            val style = StrokeStyle(sizePx = 20f, opacity = 1f, red = 0.2f, green = 0.4f, blue = 0.9f, historyEntry = entry)
            engine.beginStroke(StrokeTool.Paint, layer, style)
            engine.addStrokeSamples(samples)
            engine.endStroke()
        }
        assertNull(engine.openDocument(state.widthPx, state.heightPx, state.tree()))
        circle(80f, 1)
        val expected = composite()
        assertNull(engine.openDocument(state.widthPx, state.heightPx, state.tree()))
        circle(200f, 2)
        engine.applyHistory(2, undo = true)
        engine.releaseHistory(longArrayOf(2))
        circle(80f, 3)
        assertArrayEquals("the undone stroke is gone", expected, composite())
    }

    @Test
    fun mergeDownOntoEmptyTilesIgnoresRecycledSlots() {
        var state = DocumentState(widthPx = 640, heightPx = 480)
        state = state.addRasterLayer("Layer 1").first
        state = state.addRasterLayer("Layer 2").first
        val (lower, upper) = state.rasterLayers()
        assertNull(engine.openDocument(state.widthPx, state.heightPx, state.tree()))
        engine.fillSurface(upper.id, 0.1f, 0.3f, 0.9f, 0.5f, false, 0)
        engine.fillSurface(lower.id, 1f, 0f, 0f, 1f, false, 0)
        engine.clearSurface(lower.id, 0)
        val expected = composite()
        val merged = DocumentState(state.widthPx, state.heightPx, layers = listOf<LayerNode>(lower.copy(blend = BlendMode.Normal), upper))
        assertNull(engine.composeIntoSurface(lower.id, merged.tree(), 0))
        assertNull(engine.setLayerTree(state.mergeDown(upper.id).state.tree()))
        val actual = composite()
        val worst = expected.indices.maxOf { kotlin.math.abs((expected[it].toInt() and 0xFF) - (actual[it].toInt() and 0xFF)) }
        assertTrue("merged composite differs by $worst", worst <= 2)
    }

    @Test
    fun undoAllAndRedoAllRestoreTheEndStates() {
        val random = Random(20261014)
        var nextEntry = 1L
        repeat(SEQUENCES) { sequence ->
            var state = DocumentState(widthPx = 640, heightPx = 480)
            state = state.addRasterLayer("Layer 1").first
            state = state.addRasterLayer("Layer 2").first
            state = state.addMask(state.rasterLayers().last().id).first
            assertNull(engine.openDocument(state.widthPx, state.heightPx, state.tree()))
            val initial = composite()
            val steps = mutableListOf<Step>()
            val kinds = mutableListOf<String>()
            val composites = mutableListOf(initial)
            repeat(STEPS) {
                val step = randomStep(random, state) { nextEntry++ }
                step.after?.let { state = it }
                steps += step
                kinds += lastKind
                if (DIAGNOSE) composites += composite()
            }
            val final = composite()
            for ((index, step) in steps.withIndex().reversed()) {
                step.pixelEntries.asReversed().forEach { engine.applyHistory(it, undo = true) }
                step.before?.let { assertNull(engine.setLayerTree(it.tree())) }
                if (DIAGNOSE) assertArrayEquals("undo of step $index (${kinds[index]}, sequence $sequence)", composites[index], composite())
            }
            assertArrayEquals("undo all restores the first composite", initial, composite())
            for (step in steps) {
                step.pixelEntries.forEach { engine.applyHistory(it, undo = false) }
                step.after?.let { assertNull(engine.setLayerTree(it.tree())) }
            }
            assertArrayEquals("redo all restores the last composite", final, composite())
        }
    }

    private var lastKind = ""

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val DIAGNOSE = true
        private const val SEQUENCES = 8
        private const val STEPS = 14
    }
}
