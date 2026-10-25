package com.mobyle.nanquim.ui.canvas

import com.mobyle.nanquim.ui.common.LabeledSwitch
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.AnimationCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.animation.OnionBlend
import com.mobyle.nanquim.document.animation.OnionSkin
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.animation.backgroundFrame
import com.mobyle.nanquim.document.animation.foregroundFrame
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import kotlin.math.roundToInt

@Composable
fun TimelineBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = session.document ?: return
    val currentFrameId = session.documentSession?.currentFrameId
    var settingsShown by remember { mutableStateOf(false) }
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onCommand(AnimationCommandIds.TOGGLE_PLAYBACK, emptyMap()) }) {
                Text(stringResource(if (session.playback.playing) R.string.animation_pause else R.string.animation_play))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    TextButton(onClick = { settingsShown = true }) { Text(stringResource(R.string.animation_settings)) }
                    DropdownMenu(expanded = settingsShown, onDismissRequest = { settingsShown = false }) {
                        AnimationSettingsMenu(state.animation, onCommand)
                    }
                }
                TextButton(onClick = { onCommand(AnimationCommandIds.ADD_FRAME, emptyMap()) }) { Text(stringResource(R.string.animation_add_frame)) }
            }
            FrameStrip(session, state, currentFrameId, onCommand, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FrameStrip(
    session: CanvasSession,
    state: DocumentState,
    currentFrameId: Int?,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier,
) {
    val frames = state.layers
    val listState = rememberLazyListState()
    val currentIndex = frames.indexOfFirst { it.id == currentFrameId }
    LaunchedEffect(currentIndex) { if (currentIndex >= 0) listState.animateScrollToItem(currentIndex) }
    var draggedId by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var optionsFor by remember { mutableStateOf<Int?>(null) }
    val stepPixels = with(LocalDensity.current) { (FRAME_SIZE + FRAME_SPACING).toPx() }
    LazyRow(modifier, state = listState, horizontalArrangement = Arrangement.spacedBy(FRAME_SPACING)) {
        itemsIndexed(frames, key = { _, frame -> frame.id }) { index, frame ->
            val current = frame.id == currentFrameId
            val latestIndex by rememberUpdatedState(index)
            val latestCurrent by rememberUpdatedState(current)
            val dragging = draggedId == frame.id
            Box(
                Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationX = if (dragging) dragOffset else 0f }
                    .pointerInput(frame.id) {
                        detectTapGestures {
                            if (latestCurrent) {
                                optionsFor = frame.id
                            } else {
                                onCommand(AnimationCommandIds.GO_TO_FRAME, mapOf("index" to latestIndex))
                            }
                        }
                    }.pointerInput(frame.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedId = frame.id
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.x
                            },
                            onDragEnd = {
                                val target = (latestIndex + (dragOffset / stepPixels).roundToInt()).coerceIn(0, frames.lastIndex)
                                if (target != latestIndex) onCommand(DocumentCommandIds.LAYER_MOVE, mapOf("layerId" to frame.id, "parentId" to null, "index" to target))
                                draggedId = null
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggedId = null
                                dragOffset = 0f
                            },
                        )
                    },
            ) {
                FrameCell(session, state, frame, index, current)
                DropdownMenu(expanded = optionsFor == frame.id, onDismissRequest = { optionsFor = null }) {
                    FrameOptions(state, frame, index, onCommand) { optionsFor = null }
                }
            }
        }
    }
}

@Composable
private fun FrameCell(
    session: CanvasSession,
    state: DocumentState,
    frame: LayerNode,
    index: Int,
    current: Boolean,
) {
    val outline = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val hold = state.animation.holdFrames[frame.id] ?: 0
    val label =
        when (frame.id) {
            state.backgroundFrame?.id -> stringResource(R.string.animation_background_short)
            state.foregroundFrame?.id -> stringResource(R.string.animation_foreground_short)
            else -> (index + 1).toString()
        }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(FRAME_SIZE)
                .clip(RoundedCornerShape(4.dp))
                .border(if (current) 2.dp else 1.dp, outline, RoundedCornerShape(4.dp))
                .background(Color.White),
        ) {
            for (surface in rasterIds(frame)) {
                session.layerThumbnails.images[surface]?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(FRAME_SIZE))
                }
            }
        }
        Text(
            if (hold > 0) "$label +$hold" else label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(FRAME_SIZE),
        )
    }
}

private fun rasterIds(node: LayerNode): List<Int> =
    when (node) {
        is RasterLayer -> if (node.hidden) emptyList() else listOf(node.id)
        is LayerGroup -> if (node.hidden) emptyList() else node.children.flatMap(::rasterIds)
    }

@Composable
private fun FrameOptions(
    state: DocumentState,
    frame: LayerNode,
    index: Int,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onClose: () -> Unit,
) {
    var hold by remember(frame.id) { mutableFloatStateOf((state.animation.holdFrames[frame.id] ?: 0).toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).width(OPTIONS_WIDTH)) {
        Text(pluralStringResource(R.plurals.animation_hold, hold.roundToInt(), hold.roundToInt()))
        Slider(
            value = hold,
            onValueChange = {
                hold = it
                onCommand(AnimationCommandIds.SET_HOLD, mapOf("layerId" to frame.id, "holdFrames" to it.roundToInt(), "final" to false))
            },
            onValueChangeFinished = { onCommand(AnimationCommandIds.SET_HOLD, mapOf("layerId" to frame.id, "holdFrames" to hold.roundToInt(), "final" to true)) },
            valueRange = 0f..MAXIMUM_HOLD_SHOWN.toFloat(),
            steps = MAXIMUM_HOLD_SHOWN - 1,
        )
        if (index == 0) {
            SwitchRow(R.string.animation_first_is_background, state.animation.firstFrameIsBackground) {
                onCommand(AnimationCommandIds.SET_SETTINGS, mapOf("firstFrameIsBackground" to it))
            }
        }
        if (index == state.layers.lastIndex && index > 0) {
            SwitchRow(R.string.animation_last_is_foreground, state.animation.lastFrameIsForeground) {
                onCommand(AnimationCommandIds.SET_SETTINGS, mapOf("lastFrameIsForeground" to it))
            }
        }
    }
    DropdownMenuItem(text = { Text(stringResource(R.string.animation_duplicate_frame)) }, onClick = {
        onCommand(DocumentCommandIds.LAYER_DUPLICATE, mapOf("layerId" to frame.id))
        onClose()
    })
    DropdownMenuItem(text = { Text(stringResource(R.string.animation_delete_frame)) }, onClick = {
        onCommand(DocumentCommandIds.LAYER_DELETE, mapOf("layerIds" to listOf(frame.id)))
        onClose()
    })
}

@Composable
private fun AnimationSettingsMenu(
    animation: AnimationSettings,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val onion = animation.onionSkin
    var framesPerSecond by remember { mutableFloatStateOf(animation.framesPerSecond.toFloat()) }
    var onionFrames by remember { mutableFloatStateOf(onion.frames.toFloat()) }
    var onionOpacity by remember { mutableFloatStateOf(onion.opacity) }

    fun set(
        name: String,
        value: Any,
        final: Boolean = true,
    ) = onCommand(AnimationCommandIds.SET_SETTINGS, mapOf(name to value, "final" to final))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(SETTINGS_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((mode, label) in listOf(PlaybackMode.Loop to R.string.animation_loop, PlaybackMode.PingPong to R.string.animation_ping_pong, PlaybackMode.OneShot to R.string.animation_one_shot)) {
                FilterChip(selected = animation.playback == mode, onClick = { set("playback", mode.id) }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.animation_frames_per_second, framesPerSecond.roundToInt()))
        Slider(
            value = framesPerSecond,
            onValueChange = {
                framesPerSecond = it
                set("framesPerSecond", it.roundToInt(), final = false)
            },
            onValueChangeFinished = { set("framesPerSecond", framesPerSecond.roundToInt()) },
            valueRange = AnimationSettings.FRAMES_PER_SECOND.first.toFloat()..AnimationSettings.FRAMES_PER_SECOND.last.toFloat(),
        )
        SwitchRow(R.string.animation_onion_skin, onion.enabled) { set("onionEnabled", it) }
        Text(stringResource(R.string.animation_onion_frames, onionFrames.roundToInt()))
        Slider(
            value = onionFrames,
            onValueChange = {
                onionFrames = it
                set("onionFrames", it.roundToInt(), final = false)
            },
            onValueChangeFinished = { set("onionFrames", onionFrames.roundToInt()) },
            valueRange = 0f..OnionSkin.MAXIMUM_FRAMES.toFloat(),
            steps = OnionSkin.MAXIMUM_FRAMES - 1,
        )
        Text(stringResource(R.string.animation_onion_opacity, (onionOpacity * 100).roundToInt()))
        Slider(
            value = onionOpacity,
            onValueChange = {
                onionOpacity = it
                set("onionOpacity", it.toDouble(), final = false)
            },
            onValueChangeFinished = { set("onionOpacity", onionOpacity.toDouble()) },
        )
        SwitchRow(R.string.animation_onion_colorize, onion.blend == OnionBlend.Colorize) {
            set("onionBlend", if (it) OnionBlend.Colorize.id else OnionBlend.Normal.id)
        }
    }
}

@Composable
private fun SwitchRow(
    @StringRes label: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    LabeledSwitch(stringResource(label), checked, onChange)
}

private val FRAME_SIZE = 56.dp
private val FRAME_SPACING = 6.dp
private val OPTIONS_WIDTH = 260.dp
private val SETTINGS_WIDTH = 300.dp

private const val MAXIMUM_HOLD_SHOWN = 12
