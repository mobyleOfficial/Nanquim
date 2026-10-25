package com.mobyle.nanquim.document.animation

import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RgbaColor

enum class PlaybackMode(
    val id: String,
) {
    Loop("loop"),
    PingPong("pingPong"),
    OneShot("oneShot"),
    ;

    companion object {
        fun fromId(id: String?): PlaybackMode? = entries.firstOrNull { it.id == id }
    }
}

enum class OnionBlend(
    val id: String,
) {
    Colorize("colorize"),
    Normal("normal"),
    ;

    companion object {
        fun fromId(id: String?): OnionBlend? = entries.firstOrNull { it.id == id }
    }
}

data class OnionSkin(
    val frames: Int = 2,
    val opacity: Float = 0.5f,
    val colorPrevious: RgbaColor = RgbaColor(1f, 0.2f, 0.2f, 1f),
    val colorNext: RgbaColor = RgbaColor(0.2f, 0.6f, 1f, 1f),
    val blend: OnionBlend = OnionBlend.Colorize,
    val enabled: Boolean = true,
) {
    companion object {
        const val MAXIMUM_FRAMES = 12
    }
}

data class AnimationSettings(
    val enabled: Boolean = false,
    val playback: PlaybackMode = PlaybackMode.Loop,
    val framesPerSecond: Int = DEFAULT_FRAMES_PER_SECOND,
    val onionSkin: OnionSkin = OnionSkin(),
    val firstFrameIsBackground: Boolean = false,
    val lastFrameIsForeground: Boolean = false,
    val holdFrames: Map<Int, Int> = emptyMap(),
    val extra: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        const val DEFAULT_FRAMES_PER_SECOND = 12
        val FRAMES_PER_SECOND = 1..60
        const val MAXIMUM_HOLD_FRAMES = 60
    }
}

val DocumentState.frames: List<LayerNode> get() = layers

val DocumentState.backgroundFrame: LayerNode?
    get() = layers.firstOrNull()?.takeIf { animation.firstFrameIsBackground && layers.size > 1 }

val DocumentState.foregroundFrame: LayerNode?
    get() = layers.lastOrNull()?.takeIf { animation.lastFrameIsForeground && layers.size > 1 && it != backgroundFrame }

val DocumentState.playedFrames: List<LayerNode>
    get() = layers.filter { it != backgroundFrame && it != foregroundFrame }

fun DocumentState.playbackSequence(): List<Int> {
    val forward = playedFrames.flatMap { frame -> List(1 + (animation.holdFrames[frame.id] ?: 0)) { frame.id } }
    return when (animation.playback) {
        PlaybackMode.PingPong -> forward + forward.dropLast(1).drop(1).reversed().let { back -> if (forward.size > 1) back else emptyList() }
        else -> forward
    }
}

fun DocumentState.withAnimation(settings: AnimationSettings): DocumentState {
    val frameIds = layers.map { it.id }.toSet()
    val clamped =
        settings.copy(
            framesPerSecond = settings.framesPerSecond.coerceIn(AnimationSettings.FRAMES_PER_SECOND),
            onionSkin =
                settings.onionSkin.copy(
                    frames = settings.onionSkin.frames.coerceIn(0, OnionSkin.MAXIMUM_FRAMES),
                    opacity = settings.onionSkin.opacity.coerceIn(0f, 1f),
                ),
            holdFrames =
                settings.holdFrames
                    .filter { (id, hold) -> id in frameIds && hold > 0 }
                    .mapValues { it.value.coerceAtMost(AnimationSettings.MAXIMUM_HOLD_FRAMES) },
        )
    return copy(animation = clamped)
}
