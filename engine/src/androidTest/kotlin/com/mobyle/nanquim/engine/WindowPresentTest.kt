package com.mobyle.nanquim.engine

import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class WindowPresentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun vulkanPresentsWhatGlesPresents() {
        val intent =
            Intent(context, ProbeSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ProbeSurfaceActivity
        val copyThread = HandlerThread("PixelCopy").apply { start() }
        try {
            val surface = activity.awaitSurface(10)
            assertNotNull("The window has no surface", surface)
            val captures =
                Renderer.entries.associateWith { renderer ->
                    val scratch = File(context.cacheDir, "window-test-${renderer.name}").apply { mkdirs() }
                    val engine =
                        Engine(
                            EngineConfig(
                                memoryBudgetBytes = 256L * 1024 * 1024,
                                scratchDirectory = scratch.absolutePath,
                                renderer = renderer,
                            ),
                        )
                    try {
                        engine.checkRenderer(renderer)
                        instrumentation.runOnMainSync { engine.attachSurface(surface!!, activity.surfaceWidth, activity.surfaceHeight) }
                        engine.setViewportStyle(ViewportStyle(0.3f, 0.32f, 0.36f))
                        engine.openTestDocument(1200, 900, 1, floatArrayOf(1f, 0.95f, 0.8f, 1f))
                        engine.fillRandomTiles(1, 6, 7)
                        engine.setCamera(AffineTransform(0.85, 0.15, -0.15, 0.85, 220.0, 180.0))
                        engine.setLatencyTestPoint(60f, 80f)
                        val srgb = capture(activity, copyThread)
                        check(engine.setDisplayColor(null, null, true) == null)
                        val displayP3 = capture(activity, copyThread)
                        instrumentation.runOnMainSync { engine.detachSurface() }
                        listOf(srgb, displayP3)
                    } finally {
                        instrumentation.runOnMainSync { engine.close() }
                    }
                }
            val output = File(context.getExternalFilesDir(null), "window").apply { mkdirs() }
            val failures = mutableListOf<String>()
            for ((index, name) in listOf("srgb", "p3").withIndex()) {
                val gles = captures.getValue(Renderer.Gles)[index]
                val vulkan = captures.getValue(Renderer.Vulkan)[index]
                for ((renderer, bitmap) in listOf("gles" to gles, "vulkan" to vulkan)) {
                    File(output, "${name}_$renderer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                assertEquals(gles.width, vulkan.width)
                assertEquals(gles.height, vulkan.height)
                val (largest, outside) = difference(gles, vulkan)
                Log.i(TAG, "$name: largest difference $largest, $outside channels beyond 2")
                if (outside > gles.width * gles.height * 3 / 1000) failures += "$name: $outside channels beyond 2/255 (largest $largest)"
                val dot = vulkan.getPixel(60, 80)
                if ((dot shr 16 and 0xFF) < 200 ||
                    (dot shr 8 and 0xFF) > 60
                ) {
                    failures += "$name: no latency dot at (60, 80): ${Integer.toHexString(dot)}"
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            copyThread.quitSafely()
            activity.finish()
        }
    }

    @Test
    fun vulkanDrawsFrontBufferedStrokesLikeGles() {
        val intent =
            Intent(context, ProbeSurfaceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as ProbeSurfaceActivity
        val copyThread = HandlerThread("PixelCopy").apply { start() }
        try {
            val surface = activity.awaitSurface(10)
            assertNotNull("The window has no surface", surface)
            val failures = mutableListOf<String>()
            val captures =
                Renderer.entries.associateWith { renderer ->
                    val scratch = File(context.cacheDir, "window-stroke-test-${renderer.name}").apply { mkdirs() }
                    val engine =
                        Engine(
                            EngineConfig(
                                memoryBudgetBytes = 256L * 1024 * 1024,
                                scratchDirectory = scratch.absolutePath,
                                renderer = renderer,
                            ),
                        )
                    try {
                        engine.checkRenderer(renderer)
                        instrumentation.runOnMainSync { engine.attachSurface(surface!!, activity.surfaceWidth, activity.surfaceHeight) }
                        engine.setViewportStyle(ViewportStyle(0.3f, 0.32f, 0.36f))
                        engine.openTestDocument(1200, 900, 1, floatArrayOf(1f, 0.95f, 0.8f, 1f))
                        engine.setCamera(AffineTransform(1.0, 0.0, 0.0, 1.0, 40.0, 30.0))
                        Thread.sleep(SETTLE_MILLISECONDS)
                        engine.resetFrameStatistics()
                        val style = StrokeStyle(sizePx = 24f, opacity = 1f, red = 0.1f, green = 0.2f, blue = 0.8f)
                        engine.beginStroke(StrokeTool.Paint, 1, style)
                        stroke(engine, 100f, 100f, 900f, 600f)
                        val penDown = capture(activity, copyThread)
                        val during = latency(engine)
                        engine.endStroke()
                        Thread.sleep(SETTLE_MILLISECONDS)
                        engine.beginStroke(StrokeTool.Paint, 1, style.copy(red = 0.8f, blue = 0.1f))
                        stroke(engine, 100f, 600f, 900f, 100f)
                        engine.endStroke()
                        engine.beginStroke(StrokeTool.Paint, 1, style.copy(red = 0.1f, green = 0.7f, blue = 0.2f))
                        stroke(engine, 100f, 800f, 1100f, 800f)
                        engine.endStroke()
                        val lifted = capture(activity, copyThread)
                        val after = latency(engine)
                        Log.i(
                            TAG,
                            "$renderer: ${during.frontBufferSampleCount} front-buffered updates with the pen down, " +
                                "first update of ${after.firstFrontBufferSampleCount} strokes after " +
                                "${after.inputToFirstFrontBufferMedianMilliseconds} ms (median)",
                        )
                        if (during.frontBufferSampleCount == 0) failures += "$renderer: no front-buffered update while the pen was down"
                        if (after.firstFrontBufferSampleCount != 3) {
                            failures += "$renderer: ${after.firstFrontBufferSampleCount} strokes front-buffered, 3 expected"
                        }
                        instrumentation.runOnMainSync { engine.detachSurface() }
                        listOf(penDown, lifted)
                    } finally {
                        instrumentation.runOnMainSync { engine.close() }
                    }
                }
            val output = File(context.getExternalFilesDir(null), "window").apply { mkdirs() }
            for ((index, name) in listOf("pen_down", "lifted").withIndex()) {
                val gles = captures.getValue(Renderer.Gles)[index]
                val vulkan = captures.getValue(Renderer.Vulkan)[index]
                for ((renderer, bitmap) in listOf("gles" to gles, "vulkan" to vulkan)) {
                    File(output, "stroke_${name}_$renderer.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val (largest, outside) = difference(gles, vulkan)
                Log.i(TAG, "stroke $name: largest difference $largest, $outside channels beyond 2")
                if (outside > gles.width * gles.height * 3 / 1000) {
                    failures += "stroke $name: $outside channels beyond 2/255 (largest $largest)"
                }
                val first = vulkan.getPixel(340, 255)
                if ((first and 0xFF) < 150 || (first shr 16 and 0xFF) > 100) {
                    failures += "stroke $name: no first stroke at (340, 255): ${Integer.toHexString(first)}"
                }
                val second = vulkan.getPixel(340, 505)
                if (name == "lifted" && ((second shr 16 and 0xFF) < 150 || (second and 0xFF) > 100)) {
                    failures += "stroke $name: no second stroke at (340, 505): ${Integer.toHexString(second)}"
                }
            }
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            copyThread.quitSafely()
            activity.finish()
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
        val count = 120
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

    private fun latency(engine: Engine): LatencyStats {
        Thread.sleep(SETTLE_MILLISECONDS)
        val done = CountDownLatch(1)
        var latency: LatencyStats? = null
        engine.requestStats {
            latency = it.latency
            done.countDown()
        }
        check(done.await(10, TimeUnit.SECONDS)) { "stats timed out" }
        return checkNotNull(latency)
    }

    private fun capture(
        activity: ProbeSurfaceActivity,
        copyThread: HandlerThread,
    ): Bitmap {
        Thread.sleep(SETTLE_MILLISECONDS)
        val bitmap = Bitmap.createBitmap(activity.surfaceWidth, activity.surfaceHeight, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.surfaceView, bitmap, {
            result = it
            done.countDown()
        }, Handler(copyThread.looper))
        check(done.await(10, TimeUnit.SECONDS)) { "PixelCopy timed out" }
        assertEquals("PixelCopy", PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun difference(
        first: Bitmap,
        second: Bitmap,
    ): Pair<Int, Int> {
        val a = IntArray(first.width * first.height)
        val b = IntArray(a.size)
        first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
        second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
        var largest = 0
        var outside = 0
        for (index in a.indices) {
            for (shift in intArrayOf(0, 8, 16)) {
                val difference = abs((a[index] shr shift and 0xFF) - (b[index] shr shift and 0xFF))
                largest = maxOf(largest, difference)
                if (difference > 2) outside++
            }
        }
        return largest to outside
    }

    private companion object {
        const val TAG = "WindowPresent"
        const val SETTLE_MILLISECONDS = 1500L
    }
}
