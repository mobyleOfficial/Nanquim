package com.mobyle.nanquim.engine

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

@RunWith(Parameterized::class)
class BandingTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "banding-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    @Test
    fun sixteenBitDocumentStaysWithinTwoLevels() {
        val error = largestError("deep_banding", linear = true)
        Log.i(TAG, "${renderer.name} 16 bits: largest error ${"%.2f".format(error)}/255")
        assertTrue("16-bit gradient off by $error/255", abs(error) <= 2.0)
    }

    @Test
    fun eightBitDocumentBands() {
        val error = largestError("banding", linear = false)
        Log.i(TAG, "${renderer.name} 8 bits: largest error ${"%.2f".format(error)}/255")
        assertTrue("8-bit gradient should band, off by only $error/255", abs(error) > 4.0)
    }

    private fun largestError(
        sceneName: String,
        linear: Boolean,
    ): Double {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        val scene = String(assets.open("scenes/$sceneName.json").use { it.readBytes() })
        engine.renderScene(scene, emptyMap()) {
            result = it
            done.countDown()
        }
        check(done.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "scene render timed out" }
        val render = checkNotNull(result)
        assertNull(render.error)
        var largest = 0.0
        for (step in 0..STEPS) {
            val exact = (1 - ALPHA).pow(step)
            val expected = (if (linear) linearToSrgb(exact) else exact) * 255
            val x = step * STRIP + STRIP / 2
            val actual = render.pixels[((render.height / 2) * render.width + x) * 4].toInt() and 0xFF
            if (abs(actual - expected) > abs(largest)) largest = actual - expected
        }
        return largest
    }

    private fun linearToSrgb(value: Double) = if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1 / 2.4) - 0.055

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val TAG = "BandingTest"
        private const val RENDER_TIMEOUT_SECONDS = 60L

        private const val STRIP = 5
        private const val STEPS = 200
        private const val ALPHA = 0.03
    }
}
