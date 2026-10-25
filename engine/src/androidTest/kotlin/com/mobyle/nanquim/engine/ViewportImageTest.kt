package com.mobyle.nanquim.engine

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ViewportImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var engine: Engine

    @Before
    fun createEngine() {
        val scratch = File(context.cacheDir, "viewport-test").apply { mkdirs() }
        engine = Engine(EngineConfig(memoryBudgetBytes = 512L * 1024 * 1024, scratchDirectory = scratch.absolutePath))
    }

    @After
    fun closeEngine() = InstrumentationRegistry.getInstrumentation().runOnMainSync { engine.close() }

    private class Camera(
        val name: String,
        val fitFactor: Double?,
        val scale: Double,
        val degrees: Double,
        val offsetX: Double,
        val offsetY: Double,
        val showPixels: Boolean,
    )

    @Test
    fun drawScenesThroughTheViewport() {
        val output = File(context.getExternalFilesDir(null), "viewport").apply { mkdirs() }
        val failures =
            forEachImage(listOf(engine)) { name, images ->
                Log.i(TAG, "$name ${sha256(images[0].pixels)}")
                save(File(output, "$name.png"), images[0])
            }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun vulkanDrawsTheSameImages() {
        val scratch = File(context.cacheDir, "viewport-test-vulkan").apply { mkdirs() }
        val vulkan =
            Engine(
                EngineConfig(memoryBudgetBytes = 512L * 1024 * 1024, scratchDirectory = scratch.absolutePath, renderer = Renderer.Vulkan),
            )
        try {
            vulkan.checkRenderer(Renderer.Vulkan)
            val mismatches = mutableListOf<String>()
            val contentDiffers = mutableListOf<String>()
            var compared = 0
            var worst = 0
            val failures =
                forEachImage(
                    listOf(engine, vulkan),
                    lutScenes = setOf(LUT_SCENE, "layer_tree"),
                    onScene = { scene, renders ->
                        val (largest, outside) = difference(renders[0], renders[1])
                        Log.i(TAG, "vulkan $scene composite: largest difference $largest, $outside channels beyond 2")
                        if (largest != 0) contentDiffers += scene
                        if (outside > renders[0].pixels.size / 1000) mismatches += "$scene composite: $outside channels beyond 2/255"
                        true
                    },
                ) { name, images ->
                    val (largest, outside) = difference(images[0], images[1])
                    worst = maxOf(worst, largest)
                    compared++
                    Log.i(TAG, "vulkan $name: largest difference $largest, $outside channels beyond 2")
                    if (outside > images[0].pixels.size / 1000) mismatches += "$name: $outside channels beyond 2/255 (largest $largest)"
                }
            Log.i(TAG, "vulkan: $compared images, largest difference $worst/255; composed differently: $contentDiffers")
            assertTrue((failures + mismatches).joinToString("\n"), failures.isEmpty() && mismatches.isEmpty())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { vulkan.close() }
        }
    }

    private fun difference(
        first: SceneRender,
        second: SceneRender,
    ): Pair<Int, Int> {
        var largest = 0
        var outside = 0
        for (index in first.pixels.indices) {
            val difference = abs((first.pixels[index].toInt() and 0xFF) - (second.pixels[index].toInt() and 0xFF))
            largest = maxOf(largest, difference)
            if (difference > 2) outside++
        }
        return largest to outside
    }

    private fun forEachImage(
        engines: List<Engine>,
        lutScenes: Set<String> = setOf(LUT_SCENE),
        onScene: (scene: String, renders: List<SceneRender>) -> Boolean = { _, _ -> true },
        block: (name: String, images: List<SceneRender>) -> Unit,
    ): List<String> {
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
        val files = traces + brushFiles
        val failures = mutableListOf<String>()
        for ((scene, selection) in SCENES) {
            val renders = engines.map { render(it, String(read("scenes/$scene.json")), files) }
            val error = renders.firstNotNullOfOrNull { it.error }
            if (error != null) {
                failures += "$scene: $error"
                continue
            }
            if (!onScene(scene, renders)) continue
            val variants =
                buildList {
                    add(Triple("", false, false))
                    if (selection) add(Triple("_selection", true, true))
                    if (scene in lutScenes) add(Triple("_p3lut", false, false))
                }
            for ((suffix, showSelection, dim) in variants) {
                if (suffix == "_p3lut") {
                    engines.forEach { check(it.setDisplayColor(read("profiles/DisplayP3-v4.icc"), null, false) == null) }
                }
                for (camera in CAMERAS) {
                    val name = "$scene${suffix}_${camera.name}"
                    val images =
                        engines.map {
                            renderViewport(
                                it,
                                transform(camera, renders[0].width, renders[0].height),
                                camera.showPixels,
                                if (showSelection) SCENE_SELECTION_SURFACE else -1L,
                                dim,
                            )
                        }
                    val imageError = images.firstNotNullOfOrNull { it.error }
                    if (imageError != null) {
                        failures += "$name: $imageError"
                        continue
                    }
                    block(name, images)
                }
                if (suffix == "_p3lut") engines.forEach { it.setDisplayColor(null, null, false) }
            }
        }
        return failures
    }

    private fun transform(
        camera: Camera,
        canvasWidth: Int,
        canvasHeight: Int,
    ): DoubleArray {
        val fit = min(IMAGE_WIDTH.toDouble() / canvasWidth, IMAGE_HEIGHT.toDouble() / canvasHeight) * 0.85
        val scale = camera.fitFactor?.let { it * fit } ?: camera.scale
        val radians = camera.degrees * PI / 180
        val a = scale * cos(radians)
        val b = scale * sin(radians)
        val c = -b
        val d = a
        val tx = IMAGE_WIDTH / 2.0 + camera.offsetX - (a * canvasWidth / 2 + c * canvasHeight / 2)
        val ty = IMAGE_HEIGHT / 2.0 + camera.offsetY - (b * canvasWidth / 2 + d * canvasHeight / 2)
        return doubleArrayOf(a, b, c, d, tx, ty)
    }

    private fun render(
        engine: Engine,
        sceneJson: String,
        files: Map<String, ByteArray>,
    ): SceneRender = await { engine.renderScene(sceneJson, files, it) }

    private fun renderViewport(
        engine: Engine,
        canvasToScreen: DoubleArray,
        showPixels: Boolean,
        selectionSurface: Long,
        dim: Boolean,
    ): SceneRender =
        await {
            engine.renderViewport(IMAGE_WIDTH, IMAGE_HEIGHT, canvasToScreen, showPixels, selectionSurface, dim, ANTS_PHASE, it)
        }

    private fun await(start: ((SceneRender) -> Unit) -> Unit): SceneRender {
        val done = CountDownLatch(1)
        var result: SceneRender? = null
        start {
            result = it
            done.countDown()
        }
        check(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "render timed out" }
        return checkNotNull(result)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun save(
        file: File,
        image: SceneRender,
    ) {
        val pixels = IntArray(image.width * image.height)
        for (index in pixels.indices) {
            val r = image.pixels[index * 4].toInt() and 0xFF
            val g = image.pixels[index * 4 + 1].toInt() and 0xFF
            val b = image.pixels[index * 4 + 2].toInt() and 0xFF
            pixels[index] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun read(path: String): ByteArray = assets.open(path).use { it.readBytes() }

    private fun assetFiles(folder: String): List<String> =
        assets.list(folder).orEmpty().flatMap { name ->
            val path = "$folder/$name"
            if (assets.list(path).isNullOrEmpty()) listOf(path) else assetFiles(path)
        }

    private companion object {
        const val TAG = "ViewportImage"
        const val IMAGE_WIDTH = 600
        const val IMAGE_HEIGHT = 800
        const val TIMEOUT_SECONDS = 120L
        const val ANTS_PHASE = 3.5F

        const val SCENE_SELECTION_SURFACE = 0xFFFFFF00L
        const val LUT_SCENE = "library_painting"

        val SCENES =
            listOf(
                "library_painting" to false,
                "layer_tree" to false,
                "blend_modes" to false,
                "selection_shapes" to true,
                "deep_selection_basic" to true,
                "deep_layer_tree" to false,
            )

        val CAMERAS =
            listOf(
                Camera("fit", 1.0, 0.0, 0.0, 0.0, 0.0, false),
                Camera("far", 0.3, 0.0, 0.0, 0.0, 0.0, false),
                Camera("between", 0.62, 0.0, 0.0, 0.3, 0.7, false),
                Camera("rotated", 0.8, 0.0, 25.0, 0.0, 0.0, false),
                Camera("zoomed", null, 2.5, -10.0, 0.37, 0.61, false),
                Camera("pixels", null, 10.0, 0.0, 0.25, 0.5, true),
            )
    }
}
