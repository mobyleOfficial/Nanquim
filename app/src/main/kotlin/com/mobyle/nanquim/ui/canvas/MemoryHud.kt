package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.mobyle.nanquim.R
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineStats
import java.util.Locale

@Composable
fun MemoryHud(
    engine: Engine,
    modifier: Modifier = Modifier,
) {
    var stats by remember { mutableStateOf<EngineStats?>(null) }
    LaunchedEffect(engine) {
        while (true) {
            engine.requestStats { stats = it }
            delay(REFRESH_INTERVAL_MILLISECONDS)
        }
    }
    val text = stats?.let(::formatStats) ?: stringResource(R.string.diagnostics_loading)
    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
    )
}

private fun formatStats(stats: EngineStats): String =
    buildString {
        val frames = stats.frames
        appendLine(
            "frame  median %.1f ms  p99 %.1f  max %.1f  dropped %d/%d".format(
                Locale.ROOT,
                frames.intervalMedianMilliseconds,
                frames.intervalP99Milliseconds,
                frames.intervalMaxMilliseconds,
                frames.droppedFrames,
                frames.sampleCount,
            ),
        )
        appendLine("cpu    median %.2f ms  p99 %.2f".format(Locale.ROOT, frames.cpuMedianMilliseconds, frames.cpuP99Milliseconds))
        stats.latency?.takeIf { it.frontBufferSampleCount > 0 }?.let { latency ->
            appendLine(
                "input→front buffer (GPU)  median %.1f ms  p95 %.1f  (%d updates)".format(
                    Locale.ROOT,
                    latency.inputToFrontBufferMedianMilliseconds,
                    latency.inputToFrontBufferP95Milliseconds,
                    latency.frontBufferSampleCount,
                ),
            )
        }
        stats.latency?.takeIf { it.sampleCount > 0 }?.let { latency ->
            appendLine(
                "input→display  median %.1f ms  p95 %.1f  (%d frames)".format(
                    Locale.ROOT,
                    latency.inputToDisplayMedianMilliseconds,
                    latency.inputToDisplayP95Milliseconds,
                    latency.sampleCount,
                ),
            )
        }
        stats.document?.let { appendLine("doc    ${it.widthPixels}×${it.heightPixels}  ${it.layerCount} layers") }
        stats.composite?.let {
            appendLine(
                "comp   dirty %d  composed %d  pyramid %d  visible %.0f ms  settle %.0f ms".format(
                    Locale.ROOT,
                    it.dirtyTiles,
                    it.tilesComposedLastFrame,
                    it.pyramidTilesBuiltLastFrame,
                    it.lastVisibleResultMilliseconds,
                    it.lastSettleMilliseconds,
                ),
            )
            appendLine(
                "       composite %s  caches %s  budget %d tiles/frame".format(
                    Locale.ROOT,
                    megabytes(it.compositeBytes),
                    megabytes(it.cacheBytes),
                    it.layerTileBudget,
                ),
            )
        }
        stats.tiles?.let {
            appendLine("tiles  %d total".format(Locale.ROOT, it.count))
            appendLine(
                "gpu    %d tiles  %s / %s  pages %d".format(
                    Locale.ROOT,
                    it.gpuTiles,
                    megabytes(it.gpuBytes),
                    megabytes(it.gpuBudgetBytes),
                    it.atlasPages,
                ),
            )
            appendLine("ram    %d tiles  %s / %s".format(Locale.ROOT, it.ramTiles, megabytes(it.ramBytes), megabytes(it.ramBudgetBytes)))
            appendLine("disk   %d tiles  file %s".format(Locale.ROOT, it.diskTiles, megabytes(it.scratchFileBytes)))
            append(
                "io     loads %d  readbacks %d  spills %d".format(Locale.ROOT, it.loadsInFlight, it.readbacksInFlight, it.spillsInFlight),
            )
        }
    }

private fun megabytes(bytes: Long) = "%.0f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))

private const val REFRESH_INTERVAL_MILLISECONDS = 250L
