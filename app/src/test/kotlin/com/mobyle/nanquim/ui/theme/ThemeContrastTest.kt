package com.mobyle.nanquim.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThemeContrastTest {
    private fun contrast(
        first: Color,
        second: Color,
    ): Double {
        val lighter = maxOf(first.luminance(), second.luminance()) + 0.05
        val darker = minOf(first.luminance(), second.luminance()) + 0.05
        return lighter / darker
    }

    private fun check(
        name: String,
        colors: ColorScheme,
    ) {
        val text =
            mapOf(
                "onSurface on surface" to (colors.onSurface to colors.surface),
                "onBackground on background" to (colors.onBackground to colors.background),
                "onSurfaceVariant on surface" to (colors.onSurfaceVariant to colors.surface),
                "onSurfaceVariant on surfaceVariant" to (colors.onSurfaceVariant to colors.surfaceVariant),
                "onPrimary on primary" to (colors.onPrimary to colors.primary),
                "onPrimaryContainer on primaryContainer" to (colors.onPrimaryContainer to colors.primaryContainer),
                "onSecondaryContainer on secondaryContainer" to (colors.onSecondaryContainer to colors.secondaryContainer),
                "primary on surface" to (colors.primary to colors.surface),
                "error on surface" to (colors.error to colors.surface),
            )
        val components =
            mapOf(
                "outline on surface" to (colors.outline to colors.surface),
                "primaryContainer on surface" to (colors.primaryContainer to colors.surface),
                "secondaryContainer on surface" to (colors.secondaryContainer to colors.surface),
            )
        for ((pair, value) in text) {
            val ratio = contrast(value.first, value.second)
            assertTrue(ratio >= TEXT_CONTRAST, "$name $pair: %.2f".format(ratio))
        }
        for ((pair, value) in components) {
            val ratio = contrast(value.first, value.second)
            assertTrue(ratio >= COMPONENT_CONTRAST, "$name $pair: %.2f".format(ratio))
        }
    }

    @Test
    fun darkThemeMeetsTheContrastTargets() = check("dark", DarkColors)

    @Test
    fun lightThemeMeetsTheContrastTargets() = check("light", LightColors)

    private companion object {
        const val TEXT_CONTRAST = 4.5
        const val COMPONENT_CONTRAST = 3.0
    }
}
