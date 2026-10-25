package com.mobyle.nanquim.document.animation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer

class AnimationExportPlanTest {
    private fun document(frames: Int): DocumentState {
        var state = DocumentState(640, 480)
        repeat(frames) { state = state.addRasterLayer("Frame ${it + 1}").first }
        return state.withAnimation(state.animation.copy(enabled = true))
    }

    @Test
    fun holdsMergeIntoLongerFramesWithoutDrift() {
        val state = document(3).let { it.withAnimation(it.animation.copy(holdFrames = mapOf(it.layers[1].id to 2))) }
        val (a, b, c) = state.layers.map { it.id }
        val plan = state.animationExportPlan()
        assertEquals(listOf(a, b, b, b, c), plan.slots)
        assertEquals(listOf(ExportFrame(a, 83), ExportFrame(b, 250), ExportFrame(c, 84)), plan.frames)
        assertEquals(417, plan.durationMilliseconds)
        assertEquals(plan.durationMilliseconds, plan.frames.sumOf { it.durationMilliseconds.toLong() })
    }

    @Test
    fun rangeFrameRateBackgroundAndPingPong() {
        var state = document(5)
        state = state.withAnimation(state.animation.copy(firstFrameIsBackground = true, playback = PlaybackMode.PingPong))
        val ids = state.layers.map { it.id }
        val plan = state.animationExportPlan(firstFrame = 2, lastFrame = 4, framesPerSecond = 10)
        assertEquals(listOf(ids[2], ids[3], ids[4], ids[3]), plan.slots)
        assertEquals(List(4) { 100 }, plan.frames.map { it.durationMilliseconds })
        assertEquals(listOf(ids[4]), state.animationExportPlan(firstFrame = 9, lastFrame = 2).slots)
    }
}
