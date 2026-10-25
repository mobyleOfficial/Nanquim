package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.animation.OnionBlend
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.animation.frames
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.layer.RgbaColor

object AnimationCommandIds {
    const val SET_ENABLED = "animation.setEnabled"
    const val SET_SETTINGS = "animation.setSettings"
    const val SET_HOLD = "animation.setHold"
    const val GO_TO_FRAME = "animation.goToFrame"
    const val ADD_FRAME = "animation.addFrame"
    const val PLAY = "animation.play"
    const val PAUSE = "animation.pause"
    const val TOGGLE_PLAYBACK = "animation.togglePlayback"
}

fun registerAnimationCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
) {
    val document: (AppState) -> Availability = { state ->
        if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
    }

    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        handler: suspend (JsonObject) -> CommandResult,
    ) = registry.register(
        CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Animation, parameters = parameters, undoable = true, availability = document),
    ) { _, arguments -> handler(arguments) }

    fun optional(
        name: String,
        type: ParameterType,
    ) = ParameterSpec(name, type, JsonNull)

    fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull

    fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull

    fun JsonObject.color(name: String): RgbaColor? {
        val values = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() } ?: return null
        return if (values.size >= 3) RgbaColor(values[0], values[1], values[2], values.getOrElse(3) { 1f }) else null
    }

    register(AnimationCommandIds.SET_ENABLED, R.string.command_animation_set_enabled, listOf(ParameterSpec("enabled", ParameterType.Boolean))) {
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        session.setAnimation(session.state.animation.copy(enabled = it.flag("enabled") == true))
        CommandResult.Success()
    }
    register(
        AnimationCommandIds.SET_SETTINGS,
        R.string.command_animation_set_settings,
        listOf(
            optional("playback", ParameterType.String),
            optional("framesPerSecond", ParameterType.Integer),
            optional("firstFrameIsBackground", ParameterType.Boolean),
            optional("lastFrameIsForeground", ParameterType.Boolean),
            optional("onionEnabled", ParameterType.Boolean),
            optional("onionFrames", ParameterType.Integer),
            optional("onionOpacity", ParameterType.Number),
            optional("onionBlend", ParameterType.String),
            optional("colorPrevious", ParameterType.Json),
            optional("colorNext", ParameterType.Json),
            ParameterSpec("final", ParameterType.Boolean, JsonPrimitive(true)),
        ),
    ) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        val current = session.state.animation
        val playbackId = (arguments["playback"] as? JsonPrimitive)?.contentOrNull
        val playback = playbackId?.let { PlaybackMode.fromId(it) ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "playback is loop, pingPong or oneShot") }
        val blendId = (arguments["onionBlend"] as? JsonPrimitive)?.contentOrNull
        val blend = blendId?.let { OnionBlend.fromId(it) ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "onionBlend is colorize or normal") }
        val onion = current.onionSkin
        session.setAnimation(
            current.copy(
                playback = playback ?: current.playback,
                framesPerSecond = (arguments["framesPerSecond"] as? JsonPrimitive)?.intOrNull ?: current.framesPerSecond,
                firstFrameIsBackground = arguments.flag("firstFrameIsBackground") ?: current.firstFrameIsBackground,
                lastFrameIsForeground = arguments.flag("lastFrameIsForeground") ?: current.lastFrameIsForeground,
                onionSkin =
                    onion.copy(
                        enabled = arguments.flag("onionEnabled") ?: onion.enabled,
                        frames = (arguments["onionFrames"] as? JsonPrimitive)?.intOrNull ?: onion.frames,
                        opacity = arguments.number("onionOpacity")?.toFloat() ?: onion.opacity,
                        blend = blend ?: onion.blend,
                        colorPrevious = arguments.color("colorPrevious") ?: onion.colorPrevious,
                        colorNext = arguments.color("colorNext") ?: onion.colorNext,
                    ),
            ),
            final = arguments.flag("final") != false,
        )
        CommandResult.Success()
    }
    register(
        AnimationCommandIds.SET_HOLD,
        R.string.command_animation_set_hold,
        listOf(ParameterSpec("layerId", ParameterType.Integer), ParameterSpec("holdFrames", ParameterType.Integer), ParameterSpec("final", ParameterType.Boolean, JsonPrimitive(true))),
    ) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        val id = (arguments["layerId"] as? JsonPrimitive)?.intOrNull ?: 0
        if (session.state.frames.none { it.id == id }) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Layer $id is not a frame")
        val hold = ((arguments["holdFrames"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val animation = session.state.animation
        session.setAnimation(animation.copy(holdFrames = animation.holdFrames + (id to hold)), final = arguments.flag("final") != false)
        CommandResult.Success()
    }
    register(AnimationCommandIds.GO_TO_FRAME, R.string.command_animation_go_to_frame, listOf(ParameterSpec("index", ParameterType.Integer))) { arguments ->
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        val frame = session.state.frames.getOrNull((arguments["index"] as? JsonPrimitive)?.intOrNull ?: -1)
            ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "No such frame")
        session.select(frame.id)
        CommandResult.Success()
    }
    fun registerPlayback(
        id: String,
        titleRes: Int,
        action: () -> Unit,
    ) = registry.register(CommandSpec(id = id, titleRes = titleRes, category = CommandCategory.Animation, undoable = false, availability = document)) { _, _ ->
        if (canvas.documentSession?.state?.animation?.enabled != true) {
            CommandResult.Failure(CommandErrorCode.Unavailable, "Animation is off")
        } else {
            action()
            CommandResult.Success()
        }
    }
    registerPlayback(AnimationCommandIds.PLAY, R.string.command_animation_play) { canvas.playback.play() }
    registerPlayback(AnimationCommandIds.PAUSE, R.string.command_animation_pause) { canvas.playback.stop() }
    registerPlayback(AnimationCommandIds.TOGGLE_PLAYBACK, R.string.command_animation_toggle_playback) { canvas.playback.toggle() }
    register(AnimationCommandIds.ADD_FRAME, R.string.command_animation_add_frame) {
        val session = canvas.documentSession ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No document")
        session.currentFrameId?.let(session::select)
        val id = session.newLayer()
        CommandResult.Success(kotlinx.serialization.json.buildJsonObject { put("layerId", JsonPrimitive(id)) })
    }
}
