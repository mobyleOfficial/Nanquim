package com.mobyle.nanquim.engine

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.formats.blend.BlendReference
import com.mobyle.nanquim.formats.blend.Rgba
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(Parameterized::class)
class BlendGridTest(
    private val renderer: Renderer,
) {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "blend-grid-test-${renderer.name}").apply { mkdirs() }
        engine =
            Engine(
                EngineConfig(memoryBudgetBytes = 256L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = renderer),
            )
        engine.checkRenderer(renderer)
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    private data class Sample(
        val color: List<Double>,
        val alpha: Double,
    )

    private val colors =
        listOf(
            listOf(0.0, 0.0, 0.0),
            listOf(1.0, 1.0, 1.0),
            listOf(0.9, 0.2, 0.1),
            listOf(0.1, 0.65, 0.85),
            listOf(0.45, 0.45, 0.45),
            listOf(0.3, 0.8, 0.25),
            listOf(0.7, 0.35, 0.95),
            listOf(0.15, 0.1, 0.4),
        )

    private val backdrops = colors.flatMap { color -> listOf(0.4, 1.0).map { Sample(color, it) } }
    private val sources = colors.flatMap { color -> listOf(0.0, 0.4, 1.0).map { Sample(color, it) } }

    private fun sceneFor(mode: BlendMode): String =
        buildJsonObject {
            put("format", 1)
            put("documentId", "blend-grid")
            putJsonObject("canvas") {
                put("width", backdrops.size)
                put("height", sources.size)
                put("background", JsonArray(List(4) { JsonPrimitive(0.0) }))
            }
            putJsonArray("layers") {
                addJsonObject {
                    put("type", "raster")
                    put("id", 1)
                }
                addJsonObject {
                    put("type", "raster")
                    put("id", 2)
                    put("blend", mode.id)
                }
            }
            put(
                "actions",
                buildJsonArray {
                    backdrops.forEachIndexed { column, sample ->
                        addJsonObject {
                            put("type", "fill")
                            put("layer", 1)
                            put("rect", JsonArray(listOf(column, 0, 1, sources.size).map(::JsonPrimitive)))
                            put("color", JsonArray((sample.color + sample.alpha).map(::JsonPrimitive)))
                        }
                    }
                    sources.forEachIndexed { row, sample ->
                        addJsonObject {
                            put("type", "fill")
                            put("layer", 2)
                            put("rect", JsonArray(listOf(0, row, backdrops.size, 1).map(::JsonPrimitive)))
                            put("color", JsonArray((sample.color + sample.alpha).map(::JsonPrimitive)))
                        }
                    }
                },
            )
        }.toString()

    private fun stored(sample: Sample): Rgba {
        fun byte(value: Double) = (value * 255).roundToInt() / 255.0
        return Rgba(
            byte(sample.color[0] * sample.alpha),
            byte(sample.color[1] * sample.alpha),
            byte(sample.color[2] * sample.alpha),
            byte(sample.alpha),
        )
    }

    private fun render(sceneJson: String): SceneRender {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        engine.renderScene(sceneJson, emptyMap()) {
            result = it
            done.countDown()
        }
        check(done.await(60, TimeUnit.SECONDS)) { "scene render timed out" }
        return checkNotNull(result)
    }

    @Test
    fun everyModeMatchesTheReference() {
        val failures = mutableListOf<String>()
        var worst = 0.0
        for (mode in BlendMode.LAYER_MODES) {
            val render = render(sceneFor(mode))
            if (render.error != null) {
                failures += "${mode.id}: ${render.error}"
                continue
            }
            val failuresBefore = failures.size
            for ((row, source) in sources.withIndex()) {
                for ((column, backdrop) in backdrops.withIndex()) {
                    val expected = BlendReference.composite(mode, stored(backdrop), stored(source))
                    val offset = (row * render.width + column) * 4
                    val alpha = (render.pixels[offset + 3].toInt() and 0xFF) / 255.0
                    val actual =
                        listOf(0, 1, 2).map { channel -> (render.pixels[offset + channel].toInt() and 0xFF) / 255.0 * alpha } + alpha
                    val wanted = listOf(expected.red, expected.green, expected.blue, expected.alpha)
                    val difference = wanted.indices.maxOf { abs(wanted[it] - actual[it]) }
                    worst = maxOf(worst, difference)
                    if (difference > TOLERANCE) {
                        failures += "${mode.id} backdrop $backdrop source $source: expected $wanted, got $actual"
                    }
                }
            }
            if (failures.size > failuresBefore) {
                android.util.Log.w(
                    "BlendGridTest",
                    "${mode.id}: ${failures.size - failuresBefore} pixels; first: ${failures[failuresBefore]}",
                )
            }
        }
        android.util.Log.i("BlendGridTest", "largest difference ${worst * 255}/255")
        if (failures.isNotEmpty()) fail(failures.take(20).joinToString("\n") + "\n(${failures.size} in all)")
        assertTrue(worst <= TOLERANCE)
    }

    companion object {
        private const val TOLERANCE = 1.5 / 255

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun renderers(): List<Renderer> = Renderer.entries
    }
}
