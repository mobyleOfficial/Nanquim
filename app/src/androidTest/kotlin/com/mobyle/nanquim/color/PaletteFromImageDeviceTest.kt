package com.mobyle.nanquim.color

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.document.color.PaletteExtractor

@RunWith(AndroidJUnit4::class)
class PaletteFromImageDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun goldenImagesGiveTheJvmPalettes() {
        for (name in listOf("bars", "gradient", "blobs", "noisy")) {
            val options = BitmapFactory.Options().apply { inPremultiplied = false }
            val bitmap = assets.open("$name.png").use { BitmapFactory.decodeStream(it, null, options) }!!
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val palette = PaletteExtractor.extract(pixels, bitmap.width, bitmap.height).map { it.toHex() }
            val saved = JSONArray(assets.open("$name.json").bufferedReader().readText())
            assertEquals(name, List(saved.length()) { saved.getString(it) }, palette)
        }
    }

    @Test
    fun extractionTimes() {
        val options = BitmapFactory.Options().apply { inPremultiplied = false }
        val bitmap = assets.open("noisy.png").use { BitmapFactory.decodeStream(it, null, options) }!!
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        PaletteExtractor.extract(pixels, bitmap.width, bitmap.height)
        val start = System.nanoTime()
        val first = PaletteExtractor.extract(pixels, bitmap.width, bitmap.height)
        val full = (System.nanoTime() - start) / 1e6
        val live = PaletteExtractor.extract(pixels, bitmap.width, bitmap.height, maximumSide = PaletteExtractor.LIVE_SIDE)
        val warmStart = System.nanoTime()
        PaletteExtractor.extract(
            pixels,
            bitmap.width,
            bitmap.height,
            initial = PaletteExtractor.centersOf(live),
            iterations = 3,
            maximumSide = PaletteExtractor.LIVE_SIDE,
        )
        val warm = (System.nanoTime() - warmStart) / 1e6
        android.util.Log.i("NanquimTest", "palette extraction full %.1f ms (%d colors), live frame %.1f ms".format(full, first.size, warm))
        assertTrue("live frame $warm ms", warm < 1000.0 / 5)
    }
}
