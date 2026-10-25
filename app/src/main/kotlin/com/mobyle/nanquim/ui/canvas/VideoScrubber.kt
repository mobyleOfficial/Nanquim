package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.VideoCommandIds
import com.mobyle.nanquim.document.layer.RasterLayer
import java.util.Locale

@Composable
fun VideoScrubber(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val documentVersion = session.documentVersion
    val layer = session.documentSession?.state?.activeLayer as? RasterLayer ?: return
    val video = layer.video ?: return
    var time by remember(layer.id, video.timeMicroseconds) { mutableFloatStateOf(video.timeMicroseconds.toFloat()) }
    val duration = video.durationMicroseconds.coerceAtLeast(1).toFloat()
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.video_time, clock(time.toLong()), clock(video.durationMicroseconds)))
            Slider(
                value = time,
                onValueChange = {
                    time = it
                    onCommand(VideoCommandIds.SET_TIME, mapOf("layerId" to layer.id, "timeMicroseconds" to it.toLong(), "final" to false))
                },
                onValueChangeFinished = { onCommand(VideoCommandIds.SET_TIME, mapOf("layerId" to layer.id, "timeMicroseconds" to time.toLong(), "final" to true)) },
                valueRange = 0f..duration,
                modifier = Modifier.width(SCRUBBER_WIDTH),
            )
            TextButton(onClick = { onCommand(VideoCommandIds.RASTERIZE, mapOf("layerId" to layer.id)) }) { Text(stringResource(R.string.video_rasterize)) }
        }
    }
}

private fun clock(microseconds: Long): String {
    val tenths = microseconds / 100_000
    return String.format(Locale.ROOT, "%d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
}

private val SCRUBBER_WIDTH = 320.dp
