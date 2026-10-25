package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.ColorDisc
import com.mobyle.nanquim.document.color.ColorHarmony
import com.mobyle.nanquim.document.color.ColorValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class HarmonyPickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var color by mutableStateOf(ColorValue.fromHsb(0f, 0.8f, 0.9f))
    private var harmony by mutableStateOf(ColorHarmony.Complementary)

    private fun show() =
        compose.setContent {
            HarmonyPicker(
                color,
                harmony,
                onHarmonyChange = { harmony = it },
                onChange = { color = it },
                discMaximum = 300.dp,
                modifier = Modifier.width(300.dp),
            )
        }

    private fun disc() = compose.onNodeWithContentDescription(context.getString(R.string.color_harmony_description))

    private fun at(
        width: Int,
        x: Double,
        y: Double,
    ) = Offset(width / 2f + (x * width / 2f).toFloat(), width / 2f - (y * width / 2f).toFloat())

    @Test
    fun tapOnTheDiscMovesTheBaseAndKeepsBrightness() {
        show()
        disc().performTouchInput { click(at(width, 0.5, 0.0)) }
        compose.waitForIdle()
        assertEquals(90f, color.hue, 0.5f)
        assertEquals(0.5f, color.saturation, 0.01f)
        assertEquals(0.9f, color.brightness, 1e-6f)
    }

    @Test
    fun tapOnAMarkerSelectsItsColor() {
        show()
        disc().performTouchInput { click(at(width, 0.0, -0.8)) }
        compose.waitForIdle()
        assertEquals(180f, color.hue, 1e-3f)
        assertEquals(0.8f, color.saturation, 1e-6f)
    }

    @Test
    fun draggingAMarkerTurnsTheSetSoItFollowsTheFinger() {
        show()
        disc().performTouchInput { swipe(at(width, 0.0, -0.8), at(width, -0.6, 0.0), durationMillis = 400) }
        compose.waitForIdle()
        assertEquals(90f, color.hue, 1f)
        assertEquals(0.6f, color.saturation, 0.02f)
    }

    @Test
    fun harmonyChoiceAndSwatches() {
        show()
        compose.onNodeWithText(context.getString(R.string.color_harmony_complementary) + " ▾").performClick()
        compose.onNodeWithText(context.getString(R.string.color_harmony_triadic)).performClick()
        compose.waitForIdle()
        assertEquals(ColorHarmony.Triadic, harmony)
        val third = ColorHarmony.Triadic.colors(color)[2]
        compose.onNodeWithContentDescription(context.getString(R.string.color_harmony_swatch, 3, third.toHex())).performClick()
        compose.waitForIdle()
        assertEquals(240f, color.hue, 1e-3f)
    }

    @Test
    fun drawnDiscMatchesTheFormulas() {
        show()
        val image = disc().captureToImage()
        val pixels = image.toPixelMap()
        for ((x, y) in listOf(0.5 to 0.2, -0.3 to -0.3, 0.7 to -0.5, -0.8 to 0.1, 0.1 to 0.05)) {
            val expected = ColorValue.fromHsb(ColorDisc.hueAt(x, y), hypot(x, y).toFloat(), 0.9f)
            val point = at(image.width, x, y)
            assertColor("disc $x, $y", expected, pixels[point.x.roundToInt(), point.y.roundToInt()])
        }
    }

    private fun assertColor(
        label: String,
        expected: ColorValue,
        actual: Color,
    ) {
        val tolerance = 3f / 255f
        assertTrue("$label red ${actual.red} vs ${expected.red}", abs(actual.red - expected.red) <= tolerance)
        assertTrue("$label green ${actual.green} vs ${expected.green}", abs(actual.green - expected.green) <= tolerance)
        assertTrue("$label blue ${actual.blue} vs ${expected.blue}", abs(actual.blue - expected.blue) <= tolerance)
    }
}
