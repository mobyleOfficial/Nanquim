package com.mobyle.nanquim.engine

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(Parameterized::class)
class TextLayerTest(
    private val renderer: Renderer,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "text-layer-test-${renderer.name}").apply { mkdirs() }
        engine = Engine(EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer))
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = instrumentation.runOnMainSync { engine.close() }

    @Test
    fun setTextLayerDrawsTheModel() {
        val scene = JSONObject(String(read("scenes/text_latin.json")))
        val action = scene.getJSONArray("actions").getJSONObject(0)
        val fonts = action.getJSONObject("fonts")
        for (fontId in fonts.keys()) {
            val bytes = read(fonts.getString(fontId).removePrefix("../"))
            if (fontId == "NotoSerif-Regular") {
                val file = File(context.cacheDir, "$fontId.ttf").apply { writeBytes(bytes) }
                engine.registerFont(fontId, path = file.absolutePath)
            } else {
                engine.registerFont(fontId, data = bytes)
            }
        }
        val fallbacks = action.getJSONArray("fallbacks")
        engine.setFontFallbacks(List(fallbacks.length()) { fallbacks.getString(it) })
        engine.openTestDocument(560, 200, 1, floatArrayOf(1f, 1f, 1f, 1f))
        val model = action.getJSONObject("model")
        engine.setTextLayer(1, model.toString(), 72.0)
        val expected = decode("expected/text_latin.png")
        val (differing, largest) = awaitComparison(expected)
        assertTrue("$differing pixels differ (largest $largest)", differing <= expected.size / 4 / 1000)

        model.getJSONArray("content").getJSONObject(0).put("text", "x")
        engine.setTextLayer(1, model.toString(), 72.0)
        val (changed, _) = awaitComparison(expected, expectSame = false)
        assertTrue("the new model left the old text ($changed pixels differ)", changed > expected.size / 4 / 100)
        Thread.sleep(500)
        val incremental = readComposite()
        engine.setTextLayer(1, null, 72.0)
        engine.setTextLayer(1, model.toString(), 72.0)
        Thread.sleep(1000)
        val (fromScratch, largestDifference) = compare(readComposite(), incremental)
        assertEquals("incremental and full rasterization differ (largest $largestDifference)", 0, fromScratch)
        engine.setTextLayer(1, null, 72.0)
        val white = ByteArray(560 * 200 * 4) { 0xFF.toByte() }
        val (left, _) = awaitComparison(white, expectSame = true)
        assertEquals("a cleared text layer shows the background", 0, left)
    }

    private fun awaitComparison(
        expected: ByteArray,
        expectSame: Boolean = true,
    ): Pair<Int, Int> {
        var result = Int.MAX_VALUE to 0
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLISECONDS
        while (System.currentTimeMillis() < deadline) {
            result = compare(readComposite(), expected)
            val same = result.first <= expected.size / 4 / 1000
            if (same == expectSame) return result
            Thread.sleep(50)
        }
        return result
    }

    private fun readComposite(): ByteArray {
        val done = CountDownLatch(1)
        var pixels = ByteArray(0)
        engine.readComposite {
            pixels = it.pixels
            done.countDown()
        }
        check(done.await(TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)) { "composite timed out" }
        return pixels
    }

    private fun compare(
        actual: ByteArray,
        expected: ByteArray,
    ): Pair<Int, Int> {
        if (actual.size != expected.size) return Int.MAX_VALUE to 255
        var differing = 0
        var largest = 0
        for (pixel in 0 until expected.size / 4) {
            var difference = 0
            for (channel in 0 until 4) {
                val index = pixel * 4 + channel
                difference = maxOf(difference, abs((actual[index].toInt() and 0xFF) - (expected[index].toInt() and 0xFF)))
            }
            largest = maxOf(largest, difference)
            if (difference > 2) differing++
        }
        return differing to largest
    }

    private fun decode(path: String): ByteArray {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = BitmapFactory.decodeByteArray(read(path), 0, read(path).size, options)
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(argb.size * 4).also { rgba ->
            for ((index, color) in argb.withIndex()) {
                rgba[index * 4] = (color shr 16).toByte()
                rgba[index * 4 + 1] = (color shr 8).toByte()
                rgba[index * 4 + 2] = color.toByte()
                rgba[index * 4 + 3] = (color ushr 24).toByte()
            }
        }
    }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    companion object {
        private const val TIMEOUT_MILLISECONDS = 10_000L

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
