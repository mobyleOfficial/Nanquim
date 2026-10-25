package com.mobyle.nanquim.document.animation

import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.RgbaColor

data class FrameAppearance(
    val hidden: Boolean,
    val opacityFactor: Float = 1f,
    val tint: RgbaColor? = null,
)

fun DocumentState.animationAppearance(
    currentFrameId: Int?,
    onion: Boolean = true,
): Map<Int, FrameAppearance>? {
    if (!animation.enabled) return null
    val played = playedFrames
    val currentIndex = played.indexOfFirst { it.id == currentFrameId }.takeIf { it >= 0 } ?: 0
    val skin = animation.onionSkin
    val appearance = HashMap<Int, FrameAppearance>()
    backgroundFrame?.let { appearance[it.id] = FrameAppearance(hidden = false) }
    foregroundFrame?.let { appearance[it.id] = FrameAppearance(hidden = false) }
    played.forEachIndexed { index, frame ->
        val distance = index - currentIndex
        val k = kotlin.math.abs(distance)
        appearance[frame.id] =
            when {
                distance == 0 -> FrameAppearance(hidden = false)
                onion && skin.enabled && k <= skin.frames && skin.opacity > 0f ->
                    FrameAppearance(
                        hidden = false,
                        opacityFactor = skin.opacity * (1f - k.toFloat() / (skin.frames + 1)),
                        tint = if (skin.blend == OnionBlend.Colorize) (if (distance < 0) skin.colorPrevious else skin.colorNext) else null,
                    )
                else -> FrameAppearance(hidden = true)
            }
    }
    return appearance
}
