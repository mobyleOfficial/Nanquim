package com.mobyle.nanquim.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(Parameterized::class)
class GpuGoldenTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "golden-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 512L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    @Test
    fun everySceneMatchesTheCpuReference() {
        val sceneNames = assets.list("scenes").orEmpty().filter { it.endsWith(".json") }
        assertTrue("no golden scenes found", sceneNames.isNotEmpty())
        val traces =
            assets
                .list("scenes")
                .orEmpty()
                .filterNot { it.endsWith(".json") }
                .associateWith { read("scenes/$it") }
        val brushFiles =
            assetFiles("brushes").flatMap { path ->
                val bytes = read(path)
                listOf("../$path" to bytes, "../../assets-src/$path" to bytes)
            }
        val fontFiles = assetFiles("fonts").associate { path -> "../$path" to read(path) }
        val files = traces + brushFiles + fontFiles
        val failures = mutableListOf<String>()
        for (sceneName in sceneNames) {
            val name = sceneName.removeSuffix(".json")
            val render = render(String(read("scenes/$sceneName")), files)
            if (render.error != null) {
                failures += "${renderer.name}/$name: ${render.error}"
                continue
            }
            val expected = decodeExpected("expected/$name.png")
            val comparison = compare(render, expected)
            Log.i(TAG, "${renderer.name}/$name: $comparison sha256 ${sha256(render.pixels)}")
            if (!comparison.matches) {
                failures += "${renderer.name}/$name: $comparison (GPU image: ${save(name, render)})"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    private fun render(
        sceneJson: String,
        files: Map<String, ByteArray>,
    ): SceneRender {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.renderScene(sceneJson, files) {
            result = it
            done.countDown()
        }
        check(done.await(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "scene render timed out" }
        return checkNotNull(result)
    }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assetFiles(folder: String): List<String> =
        assets.list(folder).orEmpty().flatMap { name ->
            val path = "$folder/$name"
            if (assets.list(path).isNullOrEmpty()) listOf(path) else assetFiles(path)
        }

    private class Expected(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    private fun decodeExpected(path: String): Expected {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(read(path), 0, read(path).size, options)) { "cannot decode $path" }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Expected(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
    }

    private data class Comparison(
        val differingPixels: Int,
        val totalPixels: Int,
        val maximumDifference: Int,
        val sizeMatches: Boolean,
    ) {
        val matches: Boolean get() = sizeMatches && differingPixels <= totalPixels * MAXIMUM_DIFFERING_RATIO

        override fun toString() =
            if (sizeMatches) {
                "$differingPixels of $totalPixels pixels differ (max channel difference $maximumDifference)"
            } else {
                "size mismatch"
            }
    }

    private fun compare(
        render: SceneRender,
        expected: Expected,
    ): Comparison {
        if (render.width != expected.width || render.height != expected.height) return Comparison(0, 0, 0, false)
        var differing = 0
        var maximum = 0
        for (index in expected.pixels.indices) {
            val argb = expected.pixels[index]
            val expectedChannels = intArrayOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF, argb ushr 24)
            var pixelDifference = 0
            for (channel in 0 until 4) {
                val actual = render.pixels[index * 4 + channel].toInt() and 0xFF
                pixelDifference = maxOf(pixelDifference, abs(actual - expectedChannels[channel]))
            }
            maximum = maxOf(maximum, pixelDifference)
            if (pixelDifference > CHANNEL_TOLERANCE) differing++
        }
        return Comparison(differing, expected.pixels.size, maximum, true)
    }

    private fun save(
        name: String,
        render: SceneRender,
    ): String {
        val pixels = IntArray(render.width * render.height)
        for (index in pixels.indices) {
            val r = render.pixels[index * 4].toInt() and 0xFF
            val g = render.pixels[index * 4 + 1].toInt() and 0xFF
            val b = render.pixels[index * 4 + 2].toInt() and 0xFF
            val a = render.pixels[index * 4 + 3].toInt() and 0xFF
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, render.width, render.height, Bitmap.Config.ARGB_8888)
        val file = File(context.getExternalFilesDir(null), "golden-gpu-${renderer.name}-$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries

        private const val TAG = "GpuGoldenTest"
        private const val CHANNEL_TOLERANCE = 2
        private const val MAXIMUM_DIFFERING_RATIO = 0.001
        private const val RENDER_TIMEOUT_SECONDS = 60L
    }
}
