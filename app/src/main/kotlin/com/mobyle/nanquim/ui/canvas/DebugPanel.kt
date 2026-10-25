package com.mobyle.nanquim.ui.canvas

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.NanquimApplication
import com.mobyle.nanquim.R
import com.mobyle.nanquim.automation.ControlChannel
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.CanvasCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugPanel(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 640.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
    ) {
        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.debug_panel_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.debug_section_documents), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TEST_DOCUMENTS.forEach { preset ->
                    OutlinedButton(onClick = {
                        onCommand(
                            CanvasCommandIds.DEBUG_CREATE_TEST_DOCUMENT,
                            mapOf(
                                "width" to preset.width,
                                "height" to preset.height,
                                "layers" to preset.layers,
                                "fillTiles" to CanvasSession.FILL_ALL_TILES,
                            ),
                        )
                    }) {
                        Text(stringResource(R.string.debug_test_document, preset.width, preset.height, preset.layers))
                    }
                }
            }
            Text(stringResource(R.string.debug_section_actions), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onCommand(
                        CanvasCommandIds.DEBUG_FILL_RANDOM_TILES,
                        mapOf("seed" to (System.nanoTime() % Int.MAX_VALUE).toInt()),
                    )
                }) {
                    Text(stringResource(R.string.debug_fill_random_tiles))
                }
                val bottomLayer = session.document?.rasterLayers()?.firstOrNull()
                if (bottomLayer != null) {
                    OutlinedButton(onClick = {
                        onCommand(DocumentCommandIds.LAYER_SET_HIDDEN, mapOf("layerId" to bottomLayer.id, "hidden" to !bottomLayer.hidden))
                    }) {
                        Text(stringResource(if (bottomLayer.hidden) R.string.debug_show_bottom_layer else R.string.debug_hide_bottom_layer))
                    }
                }
                val topLayer = session.document?.rasterLayers()?.lastOrNull()
                if (topLayer != null && topLayer != bottomLayer) {
                    OutlinedButton(onClick = {
                        onCommand(DocumentCommandIds.LAYER_SET_HIDDEN, mapOf("layerId" to topLayer.id, "hidden" to !topLayer.hidden))
                    }) {
                        Text(stringResource(if (topLayer.hidden) R.string.debug_show_top_layer else R.string.debug_hide_top_layer))
                    }
                }
                OutlinedButton(
                    onClick = { onCommand(CanvasCommandIds.VIEW_FIT, emptyMap()) },
                ) { Text(stringResource(R.string.command_view_fit)) }
                OutlinedButton(onClick = { onCommand(CanvasCommandIds.VIEW_ACTUAL_PIXELS, emptyMap()) }) {
                    Text(stringResource(R.string.command_view_actual_pixels))
                }
                OutlinedButton(
                    onClick = { onCommand(CanvasCommandIds.VIEW_FLIP, emptyMap()) },
                ) { Text(stringResource(R.string.command_view_flip)) }
                (LocalContext.current.applicationContext as? NanquimApplication)?.controlChannel?.let { channel ->
                    Text(stringResource(R.string.debug_control_channel, ControlChannel.SOCKET_NAME, channel.token))
                }
                OutlinedButton(onClick = { onCommand(CanvasCommandIds.DEBUG_CAMERA_TOUR, emptyMap()) }) {
                    Text(stringResource(R.string.command_debug_camera_tour))
                }
                OutlinedButton(onClick = { onCommand(CanvasCommandIds.DEBUG_RESET_FRAME_STATISTICS, emptyMap()) }) {
                    Text(stringResource(R.string.command_debug_reset_frame_statistics))
                }
                SMOOTHING_PRESETS.forEach { (labelRes, amounts) ->
                    OutlinedButton(
                        onClick = {
                            onCommand(
                                CanvasCommandIds.DEBUG_BRUSH_SMOOTHING,
                                mapOf("streamline" to amounts[0], "stabilization" to amounts[1], "jitterFilter" to amounts[2]),
                            )
                        },
                    ) { Text(stringResource(labelRes)) }
                }
                MEMORY_BUDGETS_MEGABYTES.forEach { megabytes ->
                    OutlinedButton(onClick = { onCommand(CanvasCommandIds.DEBUG_SET_MEMORY_BUDGET, mapOf("megabytes" to megabytes)) }) {
                        Text(stringResource(R.string.debug_low_memory_budget, megabytes))
                    }
                }
            }
            DebugSwitch(stringResource(R.string.command_debug_dirty_tile_overlay), session.dirtyTileOverlayVisible) { enabled ->
                onCommand(CanvasCommandIds.DEBUG_DIRTY_TILE_OVERLAY, mapOf("enabled" to enabled))
            }
            DebugSwitch(stringResource(R.string.command_debug_memory_hud), session.memoryHudVisible) { enabled ->
                onCommand(CanvasCommandIds.DEBUG_MEMORY_HUD, mapOf("enabled" to enabled))
            }
            DebugSwitch(stringResource(R.string.command_debug_front_buffer), session.frontBufferEnabled) { enabled ->
                onCommand(CanvasCommandIds.DEBUG_FRONT_BUFFER, mapOf("enabled" to enabled))
            }
        }
    }
}

@Composable
private fun DebugSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Switch(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

private data class TestDocumentPreset(
    val width: Int,
    val height: Int,
    val layers: Int,
)

private val TEST_DOCUMENTS =
    listOf(
        TestDocumentPreset(4096, 4096, 20),
        TestDocumentPreset(4096, 4096, 30),
        TestDocumentPreset(2732, 2048, 60),
    )

private val MEMORY_BUDGETS_MEGABYTES = listOf(256, 1024, 1536)

private val SMOOTHING_PRESETS =
    listOf(
        R.string.debug_smoothing_off to listOf(0f, 0f, 0f),
        R.string.debug_smoothing_streamline to listOf(0.6f, 0f, 0f),
        R.string.debug_smoothing_stabilization to listOf(0f, 0.5f, 0f),
        R.string.debug_smoothing_jitter to listOf(0f, 0f, 0.6f),
    )
