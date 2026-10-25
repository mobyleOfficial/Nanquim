package com.mobyle.nanquim.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(Parameterized::class)
class PredictionResidueTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "prediction-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 512L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    @Test
    fun predictedSamplesLeaveNoResidue() {
        val random = Random(SEED)
        val withPrediction = StringBuilder()
        val withoutPrediction = StringBuilder()
        repeat(STROKE_COUNT) { index ->
            val separator = if (index == 0) "" else ","
            val stroke = randomStroke(random)
            withPrediction.append(separator).append(strokeJson(stroke, includePredicted = true))
            withoutPrediction.append(separator).append(strokeJson(stroke, includePredicted = false))
        }
        val predicted = render(sceneJson(withPrediction))
        val reference = render(sceneJson(withoutPrediction))
        assertNull(predicted.error)
        assertNull(reference.error)
        var differing = 0
        for (index in predicted.pixels.indices) {
            if (predicted.pixels[index] != reference.pixels[index]) differing++
        }
        assertEquals("bytes differing between the renders with and without prediction", 0, differing)
        assertTrue("no provisional tiles were drawn", provisionalTilesRendered() > 0)
    }

    private fun provisionalTilesRendered(): Long {
        val done = CountDownLatch(1)
        var tiles = 0L
        engine.requestStats { stats ->
            tiles = stats.stroke?.provisionalTilesRendered ?: 0
            done.countDown()
        }
        check(done.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "stats timed out" }
        return tiles
    }

    private class Sample(
        val x: Float,
        val y: Float,
        val pressure: Float,
        val timeMilliseconds: Float,
        val predicted: Boolean,
    )

    private class Stroke(
        val erase: Boolean,
        val buildUp: Boolean,
        val size: Int,
        val color: List<Float>,
        val samples: List<Sample>,
    )

    private fun randomStroke(random: Random): Stroke {
        val samples = mutableListOf<Sample>()
        var x = random.nextFloat() * CANVAS_SIZE
        var y = random.nextFloat() * CANVAS_SIZE
        var time = 0f
        repeat(random.nextInt(2, 7)) {
            x = (x + random.nextFloat() * 80 - 40).coerceIn(0f, CANVAS_SIZE.toFloat())
            y = (y + random.nextFloat() * 80 - 40).coerceIn(0f, CANVAS_SIZE.toFloat())
            time += 4f
            samples += Sample(x, y, 0.2f + random.nextFloat() * 0.8f, time, predicted = false)
            var predictedX = x
            var predictedY = y
            repeat(random.nextInt(0, 4)) { step ->
                predictedX += random.nextFloat() * 60 - 30
                predictedY += random.nextFloat() * 60 - 30
                samples += Sample(predictedX, predictedY, random.nextFloat(), time + 4f * (step + 1), predicted = true)
            }
        }
        return Stroke(
            erase = random.nextInt(4) == 0,
            buildUp = random.nextBoolean(),
            size = random.nextInt(4, 40),
            color = listOf(random.nextFloat(), random.nextFloat(), random.nextFloat()),
            samples = samples,
        )
    }

    private fun strokeJson(
        stroke: Stroke,
        includePredicted: Boolean,
    ): String {
        val samples =
            stroke.samples
                .filter { includePredicted || !it.predicted }
                .joinToString(",") { sample ->
                    val flags = if (sample.predicted) ",$PREDICTED_FLAG" else ""
                    "[${sample.x},${sample.y},${sample.pressure},${sample.timeMilliseconds}$flags]"
                }
        val accumulation = if (stroke.buildUp) "buildUp" else "wash"
        val (red, green, blue) = stroke.color
        return """{"type":"stroke","tool":"${if (stroke.erase) "erase" else "paint"}","layer":1,"size":${stroke.size},""" +
            """"opacity":0.8,"color":[$red,$green,$blue,1],"brush":{"accumulation":"$accumulation"},"samples":[$samples]}"""
    }

    private fun sceneJson(actions: CharSequence) =
        """{"format":1,"documentId":"prediction-residue","canvas":{"width":$CANVAS_SIZE,"height":$CANVAS_SIZE,""" +
            """"background":[1,1,1,1]},"layers":1,"actions":[$actions]}"""

    private fun render(sceneJson: String): SceneRender {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.renderScene(sceneJson, emptyMap()) {
            result = it
            done.countDown()
        }
        check(done.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "scene render timed out" }
        return checkNotNull(result)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val SEED = 20261014
        private const val STROKE_COUNT = 1000
        private const val CANVAS_SIZE = 512
        private const val PREDICTED_FLAG = 2
        private const val RENDER_TIMEOUT_SECONDS = 300L
    }
}
