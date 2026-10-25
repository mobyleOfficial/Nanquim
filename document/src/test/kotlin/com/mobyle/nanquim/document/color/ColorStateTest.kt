package com.mobyle.nanquim.document.color

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ColorStateTest {
    @Test
    fun historyHasTheMostRecentFirstWithoutRepeats() {
        val red = ColorValue.fromRgb(1f, 0f, 0f)
        val blue = ColorValue.fromRgb(0f, 0f, 1f)
        val state = ColorState().recordUse(red).recordUse(blue).recordUse(red)
        assertEquals(listOf(red, blue), state.history)
    }

    @Test
    fun colorsEqualAt8BitsAreRepeats() {
        val red = ColorValue.fromRgb(1f, 0f, 0f)
        val almostRed = ColorValue.fromRgb(0.999f, 0.001f, 0f)
        assertEquals(listOf(almostRed), ColorState().recordUse(red).recordUse(almostRed).history)
    }

    @Test
    fun historyKeepsTenColors() {
        var state = ColorState()
        for (level in 0 until 15) state = state.recordUse(ColorValue.fromRgb(level / 14f, 0f, 0f))
        assertEquals(ColorState.HISTORY_SIZE, state.history.size)
        assertEquals(1f, state.history.first().red)
        assertEquals(5 / 14f, state.history.last().red, 1e-6f)
    }

    @Test
    fun changingColorsDoesNotTouchTheHistory() {
        val green = ColorValue.fromRgb(0f, 1f, 0f)
        val state = ColorState().withPrimary(green).withSecondary(ColorValue.BLACK).swapped()
        assertEquals(ColorValue.BLACK, state.primary)
        assertEquals(green, state.secondary)
        assertEquals(emptyList<ColorValue>(), state.history)
        assertEquals(listOf(ColorValue.BLACK), state.recordUse().history)
        assertEquals(emptyList<ColorValue>(), state.recordUse().withoutHistory().history)
    }

    @Test
    fun jsonRoundTripKeepsHueOfGrays() {
        val orange = ColorValue.fromHsb(30f, 0.8f, 0.9f, alpha = 0.75f)
        val grayWithHue = ColorValue.fromRgb(0.4f, 0.4f, 0.4f, previous = orange)
        val state =
            ColorState(primary = grayWithHue, secondary = orange)
                .recordUse(orange)
                .recordUse(ColorValue.fromRgb(0.2f, 0.4f, 0.6f))
        val restored = ColorState.fromJson(state.toJson())!!
        assertEquals(state, restored)
        assertEquals(30f, restored.primary.hue, 1e-3f)
    }

    @Test
    fun invalidJsonIsRejected() {
        val threeComponents = """{"primary":{"rgba":[1,0,0],"hsb":[0,1,1]},"secondary":{"rgba":[1,1,1,1],"hsb":[0,0,1]}}"""
        for (text in listOf("", "{}", "not json", threeComponents)) {
            assertNull(ColorState.fromJson(text), text)
        }
    }

    @Test
    fun storedValuesOutOfRangeAreClampedAndRepeatsDropped() {
        val text =
            """
            {"primary":{"rgba":[2,0,-1,1],"hsb":[0,1,1]},
             "secondary":{"rgba":[1,1,1,1],"hsb":[0,0,1]},
             "history":[{"rgba":[1,0,0,1],"hsb":[0,1,1]},{"rgba":[1,0,0,1],"hsb":[0,1,1]}]}
            """.trimIndent()
        val state = ColorState.fromJson(text)!!
        assertEquals(1f, state.primary.red)
        assertEquals(0f, state.primary.blue)
        assertEquals(1, state.history.size)
    }
}
