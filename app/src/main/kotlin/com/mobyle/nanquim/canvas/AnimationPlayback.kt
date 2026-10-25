package com.mobyle.nanquim.canvas

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.animation.playbackSequence
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.session.DocumentSession
import com.mobyle.nanquim.engine.Engine

class AnimationPlayback(
    private val engine: Engine,
    private val session: () -> DocumentSession?,
) {
    var playing: Boolean by mutableStateOf(false)
        private set

    var framesShown = 0
        private set

    private var startNanos = 0L
    private var sequenceState: DocumentState? = null
    private var sequence: List<Int> = emptyList()
    private var shownIndex = -1
    private var prefetched: List<Int> = emptyList()

    private val callback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!playing) return
                if (tick(frameTimeNanos)) Choreographer.getInstance().postFrameCallback(this)
            }
        }

    fun play() {
        val document = session() ?: return
        if (playing || !document.state.animation.enabled) return
        playing = true
        startNanos = 0L
        shownIndex = -1
        framesShown = 0
        Choreographer.getInstance().postFrameCallback(callback)
    }

    fun stop() {
        if (!playing) return
        playing = false
        Choreographer.getInstance().removeFrameCallback(callback)
        session()?.playbackFrameId = null
        prefetched = emptyList()
        engine.prefetchSurfaces(IntArray(0))
    }

    fun toggle() = if (playing) stop() else play()

    private fun tick(frameTimeNanos: Long): Boolean {
        val document = session()
        val state = document?.state
        if (document == null || state == null || !state.animation.enabled) {
            stop()
            return false
        }
        if (state !== sequenceState) {
            sequenceState = state
            sequence = state.playbackSequence()
        }
        if (sequence.isEmpty()) {
            stop()
            return false
        }
        if (startNanos == 0L) startNanos = frameTimeNanos
        val elapsedFrames = (((frameTimeNanos - startNanos) * state.animation.framesPerSecond + NANOS_PER_SECOND / 2) / NANOS_PER_SECOND).toInt()
        if (state.animation.playback == PlaybackMode.OneShot && elapsedFrames >= sequence.size) {
            stop()
            return false
        }
        val index = elapsedFrames % sequence.size
        if (index != shownIndex) {
            shownIndex = index
            framesShown++
            document.playbackFrameId = sequence[index]
            prefetch(state, index)
        }
        return true
    }

    private fun prefetch(
        state: DocumentState,
        index: Int,
    ) {
        val upcoming = LinkedHashSet<Int>()
        var step = 1
        while (upcoming.size < PREFETCH_FRAMES && step <= sequence.size) {
            upcoming += sequence[(index + step) % sequence.size]
            step++
        }
        val frames = upcoming.toList()
        if (frames == prefetched) return
        prefetched = frames
        val byId = state.layers.associateBy { it.id }
        engine.prefetchSurfaces(frames.flatMap { id -> byId[id]?.let(::surfaces).orEmpty() }.toIntArray())
    }

    private fun surfaces(node: LayerNode): List<Int> =
        when (node) {
            is RasterLayer -> listOfNotNull(node.id, node.mask?.id)
            is LayerGroup -> node.children.flatMap(::surfaces)
        }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val PREFETCH_FRAMES = 8
    }
}
