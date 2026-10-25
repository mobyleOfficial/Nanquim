package com.mobyle.nanquim.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidthClass { Compact, Medium, Expanded }

@Immutable
data class WindowLayout(
    val width: Dp,
    val height: Dp,
) {
    val widthClass: WindowWidthClass =
        when {
            width < MEDIUM_WIDTH -> WindowWidthClass.Compact
            width < EXPANDED_WIDTH -> WindowWidthClass.Medium
            else -> WindowWidthClass.Expanded
        }

    val compact: Boolean get() = widthClass == WindowWidthClass.Compact

    val shortHeight: Boolean get() = height < SHORT_HEIGHT

    private companion object {
        val MEDIUM_WIDTH = 600.dp
        val EXPANDED_WIDTH = 840.dp
        val SHORT_HEIGHT = 480.dp
    }
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout(1280.dp, 800.dp) }

@Composable
fun ProvideWindowLayout(content: @Composable () -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val layout = with(LocalDensity.current) { WindowLayout(size.width.toDp(), size.height.toDp()) }
    CompositionLocalProvider(LocalWindowLayout provides layout, content = content)
}
