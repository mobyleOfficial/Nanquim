package com.mobyle.nanquim.ui.canvas

import android.media.MediaMetadataRetriever
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.R
import java.io.File

@Composable
fun TimelapseReplay(
    segments: List<File>,
    onClose: () -> Unit,
) {
    val clips by produceState(emptyList<Pair<File, Long>>(), segments) {
        value =
            withContext(Dispatchers.IO) {
                segments.mapNotNull { file ->
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.path)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let { file to it }
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                }
            }
    }
    var view by remember { mutableStateOf<VideoView?>(null) }
    var clip by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }
    var seeking by remember { mutableStateOf(false) }
    val total = clips.sumOf { it.second }.coerceAtLeast(1)
    val starts = clips.runningFold(0L) { start, entry -> start + entry.second }

    fun show(
        index: Int,
        offsetMilliseconds: Int,
        play: Boolean,
    ) {
        val video = view ?: return
        val entry = clips.getOrNull(index) ?: return
        clip = index
        video.setVideoPath(entry.first.path)
        video.setOnPreparedListener { player ->
            player.seekTo(offsetMilliseconds.toLong(), android.media.MediaPlayer.SEEK_CLOSEST)
            if (play) video.start()
        }
        video.setOnCompletionListener {
            if (clip + 1 < clips.size) show(clip + 1, 0, true) else playing = false
        }
    }
    LaunchedEffect(clips, view) { if (clips.isNotEmpty() && view != null) show(0, 0, true) }
    LaunchedEffect(view) {
        while (true) {
            val video = view
            if (video != null && !seeking && clips.isNotEmpty()) position = (starts.getOrElse(clip) { 0L } + video.currentPosition).toFloat()
            delay(POSITION_MILLIS)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> VideoView(context).also { view = it } }, modifier = Modifier.align(Alignment.Center))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = position.coerceIn(0f, total.toFloat()),
                onValueChange = {
                    seeking = true
                    position = it
                },
                onValueChangeFinished = {
                    seeking = false
                    val index = (starts.indexOfLast { start -> start <= position.toLong() }).coerceIn(0, (clips.size - 1).coerceAtLeast(0))
                    show(index, (position.toLong() - starts[index]).toInt(), playing)
                },
                valueRange = 0f..total.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = {
                    val video = view ?: return@FilledTonalButton
                    if (playing) video.pause() else video.start()
                    playing = !playing
                }) { Text(stringResource(if (playing) R.string.animation_pause else R.string.animation_play)) }
                TextButton(onClick = onClose) { Text(stringResource(R.string.timelapse_close), color = Color.White) }
            }
        }
    }
}

private const val POSITION_MILLIS = 100L
