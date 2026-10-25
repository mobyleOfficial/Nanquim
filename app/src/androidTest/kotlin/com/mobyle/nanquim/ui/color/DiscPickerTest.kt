package com.mobyle.nanquim.ui.color

import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.document.color.ColorDisc
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscZoom
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DiscPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private var color by mutableStateOf(ColorValue.fromHsb(30f, 0.1f, 0.1f))
    private var zoom by mutableStateOf(DiscZoom())

    private fun show() {
        compose.setContent {
            DiscPicker(
                color = color,
                zoom = zoom,
                onZoomChange = { zoom = it },
                onPick = { hue, saturation, brightness -> color = ColorValue.fromHsb(hue, saturation, brightness) },
                description = DESCRIPTION,
                modifier = Modifier.size(300.dp),
            )
        }
    }

    private fun discOffset(
        width: Int,
        x: Double,
        y: Double,
    ): Offset {
        val radius = width / 2f * DISC_FRACTION
        return Offset(width / 2f + (x * radius).toFloat(), width / 2f - (y * radius).toFloat())
    }

    @Test
    fun tapOnTheDiscPicksItsSaturationAndBrightness() {
        show()
        compose.onNodeWithContentDescription(DESCRIPTION).performTouchInput { click(discOffset(width, 0.5, 0.3)) }
        compose.waitForIdle()
        val (saturation, brightness) = ColorDisc.saturationBrightnessAt(0.5, 0.3)
        assertEquals(30f, color.hue, 1e-3f)
        assertEquals(saturation, color.saturation, 0.01f)
        assertEquals(brightness, color.brightness, 0.01f)
    }

    @Test
    fun tapOnTheRingPicksTheHue() {
        show()
        compose.onNodeWithContentDescription(DESCRIPTION).performTouchInput { click(Offset(width * 0.96f, height / 2f)) }
        compose.waitForIdle()
        assertEquals(90f, color.hue, 0.5f)
        assertEquals(0.1f, color.saturation, 1e-4f)
    }

    @Test
    fun doubleTapSnapsToAPureValue() {
        show()
        compose.onNodeWithContentDescription(DESCRIPTION).performTouchInput { doubleClick(discOffset(width, -0.55, 0.6)) }
        compose.waitForIdle()
        assertEquals(0f, color.saturation, 1e-4f)
        assertEquals(1f, color.brightness, 1e-4f)
        compose.onNodeWithContentDescription(DESCRIPTION).performTouchInput { doubleClick(Offset(width * 0.96f, height * 0.42f)) }
        compose.waitForIdle()
        assertEquals(60f, color.hue, 1e-3f)
    }

    @Test
    fun pinchZoomsTheDisc() {
        show()
        compose.onNodeWithContentDescription(DESCRIPTION).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(120f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(120f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("scale ${zoom.scale}", zoom.scale > 2.0)
    }

    @Test
    fun drawnColorsMatchTheKotlinFormulas() {
        show()
        val image = compose.onNodeWithContentDescription(DESCRIPTION).captureToImage()
        val pixels = image.toPixelMap()
        val width = image.width
        for ((x, y) in listOf(0.5 to 0.3, -0.2 to 0.6, 0.6 to -0.4, 0.0 to 0.0)) {
            val (saturation, brightness) = ColorDisc.saturationBrightnessAt(x, y)
            val expected = ColorValue.fromHsb(30f, saturation, brightness)
            val at = discOffset(width, x, y)
            assertColor("disc $x, $y", expected, pixels[at.x.roundToInt(), at.y.roundToInt()])
        }
        val ringMiddle = width / 2f * (1f - RING_FRACTION / 2f)
        assertColor("ring 180", ColorValue.fromHsb(180f, 1f, 1f), pixels[width / 2, (width / 2f + ringMiddle).roundToInt()])
        assertColor("ring 270", ColorValue.fromHsb(270f, 1f, 1f), pixels[(width / 2f - ringMiddle).roundToInt(), width / 2])
    }

    private fun assertColor(
        label: String,
        expected: ColorValue,
        actual: androidx.compose.ui.graphics.Color,
    ) {
        val tolerance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 4f / 255f else 2f / 255f
        assertTrue("$label red ${actual.red} vs ${expected.red}", abs(actual.red - expected.red) <= tolerance)
        assertTrue("$label green ${actual.green} vs ${expected.green}", abs(actual.green - expected.green) <= tolerance)
        assertTrue("$label blue ${actual.blue} vs ${expected.blue}", abs(actual.blue - expected.blue) <= tolerance)
    }

    private companion object {
        const val DESCRIPTION = "disc picker"

        const val RING_FRACTION = 0.16f
        const val DISC_FRACTION = 0.78f
    }
}
