package com.mobyle.nanquim.ui.color

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.ColorValue
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class ClassicAndValuePickerTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var color by mutableStateOf(ColorValue.fromHsb(210f, 0.6f, 0.8f))

    private fun showClassic() =
        compose.setContent {
            ClassicPicker(color, onChange = { color = it }, squareMaximum = 300.dp, modifier = Modifier.width(300.dp))
        }

    private fun showValue() = compose.setContent { ValuePicker(color, onChange = { color = it }, modifier = Modifier.width(340.dp)) }

    private fun string(id: Int) = context.getString(id)

    private fun field(component: ColorComponent) =
        compose.onNodeWithContentDescription(context.getString(R.string.color_component_field, string(component.titleRes)))

    @Test
    fun squarePicksSaturationAndBrightnessAndKeepsTheHue() {
        showClassic()
        compose.onNodeWithContentDescription(string(R.string.color_classic_description)).performTouchInput {
            click(Offset(width * 0.25f, height * 0.25f))
        }
        compose.waitForIdle()
        assertEquals(210f, color.hue, 1e-3f)
        assertEquals(0.25f, color.saturation, 0.01f)
        assertEquals(0.75f, color.brightness, 0.01f)
    }

    @Test
    fun slidersSetTheirComponent() {
        showClassic()
        compose.onNodeWithContentDescription(string(R.string.color_component_hue)).performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.5f)
        }
        compose.waitForIdle()
        assertEquals(180f, color.hue, 1e-3f)
        assertEquals(0.6f, color.saturation, 1e-6f)
        compose.onNodeWithContentDescription(string(R.string.color_component_brightness)).performTouchInput {
            click(Offset(height / 2f + (width - height) * 0.3f, height / 2f))
        }
        compose.waitForIdle()
        assertEquals(0.3f, color.brightness, 0.01f)
    }

    @Test
    fun drawnSquareAndTrackMatchTheFormulas() {
        showClassic()
        val square = compose.onNodeWithContentDescription(string(R.string.color_classic_description)).captureToImage()
        val pixels = square.toPixelMap()
        for ((x, y) in listOf(0.1f to 0.1f, 0.5f to 0.4f, 0.9f to 0.9f, 0.2f to 0.7f)) {
            val expected = ColorValue.fromHsb(210f, x, 1f - y)
            assertColor("square $x, $y", expected, pixels[(x * square.width).roundToInt(), (y * square.height).roundToInt()])
        }
        val slider = compose.onNodeWithContentDescription(string(R.string.color_component_saturation)).captureToImage()
        val track = slider.toPixelMap()
        val inset = slider.height / 2f
        val at = 0.2f
        val expected = ColorComponent.Saturation.withFraction(color, at)
        assertColor("saturation track", expected, track[(inset + at * (slider.width - 2 * inset)).roundToInt(), slider.height / 2])
    }

    @Test
    fun numericFieldsApplyOnDoneAndClamp() {
        showValue()
        field(ColorComponent.Red).performTextReplacement("200")
        field(ColorComponent.Red).performImeAction()
        compose.waitForIdle()
        assertEquals(200 / 255f, color.red, 1e-6f)
        field(ColorComponent.Saturation).performTextReplacement("150")
        field(ColorComponent.Saturation).performImeAction()
        compose.waitForIdle()
        assertEquals(1f, color.saturation, 1e-6f)
        val before = color
        field(ColorComponent.Hue).performTextReplacement("abc")
        field(ColorComponent.Hue).performImeAction()
        compose.waitForIdle()
        assertEquals(before, color)
        compose.onNodeWithText(ColorComponent.Hue.displayValue(color).toString()).assertExists()
    }

    @Test
    fun hexFieldAndPaste() {
        showValue()
        val hex = compose.onNodeWithContentDescription(string(R.string.color_hex))
        hex.performTextReplacement("00ff80")
        hex.performImeAction()
        compose.waitForIdle()
        assertEquals("#00FF80", color.toHex())
        hex.performTextReplacement("#12")
        hex.performImeAction()
        compose.waitForIdle()
        assertEquals("#00FF80", color.toHex())
        compose.onNodeWithText(string(R.string.color_hex_invalid)).assertExists()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("color", " #3366cc "))
        }
        compose.onNodeWithText(string(R.string.color_paste)).performClick()
        compose.waitUntil(5_000) { color.toHex() == "#3366CC" }
        assertEquals("#3366CC", color.toHex())
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
