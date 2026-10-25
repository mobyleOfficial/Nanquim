package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.ColorPanelMode
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.document.color.InkChannel
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.DiscZoom

@Composable
fun ColorPanel(
    session: CanvasSession,
    compact: Boolean,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = session.colors
    var discZoom by remember { mutableStateOf(DiscZoom()) }
    val setPrimary: (ColorValue) -> Unit = { color ->
        onCommand(ToolCommandIds.COLOR_SET_PRIMARY, color.commandArguments(colors.primary))
    }
    val shape = if (compact) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp) else RoundedCornerShape(16.dp)
    Surface(modifier, shape = shape, tonalElevation = 4.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.color_panel_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        session.cmykProfile?.name ?: session.colorProfile?.name ?: stringResource(R.string.color_panel_profile_srgb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                ColorPairSwatch(colors) { onCommand(ToolCommandIds.COLOR_SWAP, emptyMap()) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (mode in ColorPanelMode.entries) {
                    val selected = mode == session.colorPanelMode
                    TextButton(
                        onClick = { onCommand(ToolCommandIds.COLOR_PANEL_SET_MODE, mapOf("mode" to mode.id)) },
                        enabled = mode.available,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            stringResource(mode.titleRes),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (session.colorPanelMode) {
                    ColorPanelMode.Disc ->
                        DiscPicker(
                            color = colors.primary,
                            zoom = discZoom,
                            onZoomChange = { discZoom = it },
                            onPick = { hue, saturation, brightness ->
                                onCommand(
                                    ToolCommandIds.COLOR_SET_PRIMARY,
                                    mapOf("hue" to hue, "saturation" to saturation, "brightness" to brightness),
                                )
                            },
                            description = stringResource(R.string.color_disc_description),
                            modifier = Modifier.widthIn(max = DISC_SIZE),
                        )
                    ColorPanelMode.Classic -> ClassicPicker(colors.primary, setPrimary, squareMaximum = DISC_SIZE)
                    ColorPanelMode.Value ->
                        ValuePicker(
                            colors.primary,
                            setPrimary,
                            cmyk = session.cmykConversion,
                            inks = session.inksOf(colors.primary),
                            onInks = { inks ->
                                onCommand(
                                    ToolCommandIds.COLOR_SET_PRIMARY,
                                    InkChannel.entries.associate { it.id to it.of(inks) },
                                )
                            },
                        )
                    ColorPanelMode.Harmony ->
                        HarmonyPicker(
                            colors.primary,
                            session.colorHarmony,
                            onHarmonyChange = { onCommand(ToolCommandIds.COLOR_PANEL_SET_HARMONY, mapOf("harmony" to it.id)) },
                            onChange = setPrimary,
                            discMaximum = DISC_SIZE,
                        )
                    ColorPanelMode.Palettes -> PalettesMode(session.palettes, colors.primary, onCommand)
                }
            }
            ColorHistoryRow(colors.history, onCommand)
            if (session.colorPanelMode != ColorPanelMode.Palettes) DefaultPaletteRow(session.palettes, colors.primary, onCommand)
        }
    }
}

private val ColorPanelMode.titleRes: Int
    @StringRes get() =
        when (this) {
            ColorPanelMode.Disc -> R.string.color_mode_disc
            ColorPanelMode.Classic -> R.string.color_mode_classic
            ColorPanelMode.Harmony -> R.string.color_mode_harmony
            ColorPanelMode.Value -> R.string.color_mode_value
            ColorPanelMode.Palettes -> R.string.color_mode_palettes
        }

@Composable
private fun ColorPairSwatch(
    colors: ColorState,
    onSwap: () -> Unit,
) {
    val primaryLabel = stringResource(R.string.color_primary)
    val secondaryLabel = stringResource(R.string.color_secondary_swap)
    Box(
        Modifier
            .size(PAIR_SIZE)
            .clickable(onClick = onSwap)
            .semantics { contentDescription = "$primaryLabel, $secondaryLabel" },
    ) {
        ColorDot(colors.secondary, SECONDARY_SIZE, Modifier.align(Alignment.BottomEnd))
        ColorDot(colors.primary, PRIMARY_SIZE)
    }
}

@Composable
private fun ColorDot(
    color: ColorValue,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(2.dp)
            .background(Color(color.red, color.green, color.blue, color.alpha), CircleShape),
    )
}

@Composable
private fun ColorHistoryRow(
    history: List<ColorValue>,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val label = stringResource(R.string.color_history)
    val entryLabels = history.map { stringResource(R.string.color_history_entry, it.toHex()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (index in 0 until ColorState.HISTORY_SIZE) {
                val color = history.getOrNull(index)
                if (color == null) {
                    Box(Modifier.size(HISTORY_SWATCH).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                } else {
                    val entryLabel = entryLabels[index]
                    ColorDot(
                        color,
                        HISTORY_SWATCH,
                        Modifier
                            .clickable {
                                onCommand(
                                    ToolCommandIds.COLOR_SET_PRIMARY,
                                    mapOf("red" to color.red, "green" to color.green, "blue" to color.blue, "alpha" to color.alpha),
                                )
                            }.semantics { contentDescription = entryLabel },
                    )
                }
            }
        }
    }
}

private val DISC_SIZE = 300.dp
private val PAIR_SIZE = 48.dp
private val PRIMARY_SIZE = 32.dp
private val SECONDARY_SIZE = 22.dp
private val HISTORY_SWATCH = 24.dp

private fun ColorValue.commandArguments(previous: ColorValue): Map<String, Any?> =
    if (ColorValue.fromRgb(red, green, blue, alpha, previous) == this) {
        mapOf("red" to red, "green" to green, "blue" to blue, "alpha" to alpha)
    } else {
        mapOf("hue" to hue, "saturation" to saturation, "brightness" to brightness, "alpha" to alpha)
    }
