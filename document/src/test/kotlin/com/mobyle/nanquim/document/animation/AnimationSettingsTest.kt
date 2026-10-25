package com.mobyle.nanquim.document.animation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer

class AnimationSettingsTest {
    private fun document(frames: Int): DocumentState {
        var state = DocumentState(1920, 1080)
        repeat(frames) { state = state.addRasterLayer("Frame ${it + 1}").first }
        return state
    }

    @Test
    fun holdsRepeatFramesAndPingPongReturns() {
        val state = document(3)
        val (a, b, c) = state.layers.map { it.id }
        val held = state.withAnimation(state.animation.copy(enabled = true, holdFrames = mapOf(b to 2)))
        assertEquals(listOf(a, b, b, b, c), held.playbackSequence())
        val pingPong = held.withAnimation(held.animation.copy(playback = PlaybackMode.PingPong))
        assertEquals(listOf(a, b, b, b, c, b, b, b), pingPong.playbackSequence())
    }

    @Test
    fun backgroundAndForegroundFramesDoNotPlay() {
        val state = document(4)
        val ids = state.layers.map { it.id }
        val framed = state.withAnimation(state.animation.copy(firstFrameIsBackground = true, lastFrameIsForeground = true))
        assertEquals(ids[0], framed.backgroundFrame?.id)
        assertEquals(ids[3], framed.foregroundFrame?.id)
        assertEquals(listOf(ids[1], ids[2]), framed.playbackSequence())
        val single = document(1).let { it.withAnimation(it.animation.copy(firstFrameIsBackground = true)) }
        assertNull(single.backgroundFrame)
    }

    @Test
    fun settingsAreClamped() {
        val state = document(2)
        val clamped =
            state.withAnimation(
                state.animation.copy(
                    framesPerSecond = 500,
                    onionSkin = OnionSkin(frames = 99, opacity = 3f),
                    holdFrames = mapOf(state.layers[0].id to 999, 12345 to 3),
                ),
            )
        assertEquals(60, clamped.animation.framesPerSecond)
        assertEquals(OnionSkin.MAXIMUM_FRAMES, clamped.animation.onionSkin.frames)
        assertEquals(1f, clamped.animation.onionSkin.opacity)
        assertEquals(mapOf(state.layers[0].id to AnimationSettings.MAXIMUM_HOLD_FRAMES), clamped.animation.holdFrames)
    }
}
