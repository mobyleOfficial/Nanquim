package com.mobyle.nanquim.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val NeutralGray05 = Color(0xFF0D0D0D)
private val NeutralGray10 = Color(0xFF1A1A1A)
private val NeutralGray20 = Color(0xFF2E2E2E)
private val NeutralGray30 = Color(0xFF474747)
private val NeutralGray60 = Color(0xFF999999)
private val NeutralGray80 = Color(0xFFCCCCCC)
private val NeutralGray90 = Color(0xFFE6E6E6)
private val NeutralGray95 = Color(0xFFF2F2F2)

// Light mode sits on a soft, slightly warm paper tone instead of near-white, which is
// tiring to look at for long drawing sessions. Every surface container is set explicitly
// so Material's default (near-white, purple-tinted) containers never show through on
// sheets, dialogs, menus and cards.
private val Paper = Color(0xFFE9E5DE)
private val PaperSurface = Color(0xFFE2DED6)
private val PaperVariant = Color(0xFFCFC9BF)
private val PaperLowest = Color(0xFFEFECE6)
private val PaperLow = Color(0xFFE6E2DB)
private val PaperContainer = Color(0xFFDFDAD2)
private val PaperHigh = Color(0xFFD9D4CB)
private val PaperHighest = Color(0xFFD3CEC4)

internal val DarkColors =
    darkColorScheme(
        primary = NeutralGray90,
        onPrimary = NeutralGray05,
        primaryContainer = NeutralGray80,
        onPrimaryContainer = NeutralGray05,
        secondary = NeutralGray80,
        onSecondary = NeutralGray05,
        secondaryContainer = NeutralGray60,
        onSecondaryContainer = NeutralGray05,
        background = NeutralGray05,
        onBackground = NeutralGray90,
        surface = NeutralGray10,
        onSurface = NeutralGray90,
        surfaceVariant = NeutralGray20,
        onSurfaceVariant = NeutralGray80,
        outline = NeutralGray60,
    )

internal val LightColors =
    lightColorScheme(
        primary = NeutralGray10,
        onPrimary = NeutralGray95,
        primaryContainer = NeutralGray30,
        onPrimaryContainer = NeutralGray95,
        secondary = NeutralGray20,
        onSecondary = NeutralGray95,
        secondaryContainer = NeutralGray30,
        onSecondaryContainer = NeutralGray95,
        background = Paper,
        onBackground = NeutralGray10,
        surface = PaperSurface,
        onSurface = NeutralGray10,
        surfaceVariant = PaperVariant,
        onSurfaceVariant = NeutralGray20,
        surfaceBright = PaperLowest,
        surfaceDim = PaperHighest,
        surfaceContainerLowest = PaperLowest,
        surfaceContainerLow = PaperLow,
        surfaceContainer = PaperContainer,
        surfaceContainerHigh = PaperHigh,
        surfaceContainerHighest = PaperHighest,
        outline = NeutralGray30,
    )

val LocalDarkTheme = staticCompositionLocalOf { true }

@Composable
fun NanquimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
    }
}
