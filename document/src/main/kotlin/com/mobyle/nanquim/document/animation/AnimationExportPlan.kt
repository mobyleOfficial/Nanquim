package com.mobyle.nanquim.document.animation

import com.mobyle.nanquim.document.layer.DocumentState
import kotlin.math.roundToLong

data class ExportFrame(
    val frameId: Int,
    val durationMilliseconds: Int,
)

data class AnimationExportPlan(
    val slots: List<Int>,
    val framesPerSecond: Int,
) {
    val frames: List<ExportFrame>
        get() {
            val result = ArrayList<ExportFrame>()
            var start = 0
            while (start < slots.size) {
                var end = start + 1
                while (end < slots.size && slots[end] == slots[start]) end++
                result += ExportFrame(slots[start], (timeOf(end) - timeOf(start)).toInt())
                start = end
            }
            return result
        }

    val durationMilliseconds: Long get() = timeOf(slots.size)

    private fun timeOf(slot: Int): Long = (slot * 1000.0 / framesPerSecond).roundToLong()
}

fun DocumentState.animationExportPlan(
    firstFrame: Int = 1,
    lastFrame: Int = Int.MAX_VALUE,
    framesPerSecond: Int? = null,
): AnimationExportPlan {
    val played = playedFrames
    val first = firstFrame.coerceIn(1, maxOf(played.size, 1))
    val last = lastFrame.coerceIn(first, maxOf(played.size, 1))
    val range = if (played.isEmpty()) emptyList() else played.subList(first - 1, last)
    val forward = range.flatMap { frame -> List(1 + (animation.holdFrames[frame.id] ?: 0)) { frame.id } }
    val slots =
        if (animation.playback == PlaybackMode.PingPong && forward.size > 1) {
            forward + forward.dropLast(1).drop(1).reversed()
        } else {
            forward
        }
    return AnimationExportPlan(slots, (framesPerSecond ?: animation.framesPerSecond).coerceIn(AnimationSettings.FRAMES_PER_SECOND))
}
