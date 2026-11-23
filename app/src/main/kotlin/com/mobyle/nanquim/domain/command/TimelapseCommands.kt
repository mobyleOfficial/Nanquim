package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.data.media.TimelapseLength
import com.mobyle.nanquim.data.transfer.ArtworkTransfer
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.command.TextKey
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.platform.ExternalFiles
import com.mobyle.nanquim.domain.platform.ShareSheet
import com.mobyle.nanquim.domain.platform.TimelapseExporter
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import com.mobyle.nanquim.formats.nanquim.TimelapseResolution
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

object TimelapseCommandIds {
    const val SET_ENABLED = "timelapse.setEnabled"
    const val SET_SETTINGS = "timelapse.setSettings"
    const val NEW_CANVASES = "settings.timelapse.newCanvases"
    const val EXPORT = "timelapse.export"
    const val REPLAY = "timelapse.replay"
}

fun registerTimelapseCommands(
    registry: CommandRegistry,
    canvas: CanvasSession,
    settings: AppSettingsRepository,
    timelapseExporter: TimelapseExporter?,
    cacheDirectory: File,
    files: ExternalFiles,
    shareSheet: ShareSheet,
) {
    val artwork: (AppState) -> Availability = { state ->
        if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
    }

    fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull

    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.SET_ENABLED,
            titleKey = TextKey("command_timelapse_set_enabled"),
            category = CommandCategory.Canvas,
            parameters =
                listOf(
                    ParameterSpec("enabled", ParameterType.Boolean),
                    ParameterSpec("purge", ParameterType.Boolean, JsonPrimitive(false)),
                ),
            undoable = false,
            availability = artwork,
        ),
    ) { _, arguments ->
        val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork")
        val enabled = arguments.flag("enabled") == true
        canvas.changeTimelapse((open.info.timelapse ?: TimelapseSettings()).copy(enabled = enabled))
        if (!enabled && arguments.flag("purge") == true) canvas.timelapse.purge(open.folder)
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.SET_SETTINGS,
            titleKey = TextKey("command_timelapse_set_settings"),
            category = CommandCategory.Canvas,
            parameters =
                listOf(
                    ParameterSpec(
                        "resolution",
                        ParameterType.Enumeration,
                        JsonNull,
                        allowedValues = TimelapseResolution.entries.map { it.id },
                    ),
                    ParameterSpec("hevc", ParameterType.Boolean, JsonNull),
                ),
            undoable = false,
            availability = artwork,
        ),
    ) { _, arguments ->
        val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork")
        val current = open.info.timelapse ?: TimelapseSettings(enabled = false)
        canvas.changeTimelapse(
            current.copy(
                resolution = TimelapseResolution.fromId((arguments["resolution"] as? JsonPrimitive)?.contentOrNull) ?: current.resolution,
                hevc = arguments.flag("hevc") ?: current.hevc,
            ),
        )
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.REPLAY,
            titleKey = TextKey("command_timelapse_replay"),
            category = CommandCategory.Canvas,
            parameters = listOf(ParameterSpec("open", ParameterType.Boolean, JsonPrimitive(true))),
            undoable = false,
            availability = artwork,
        ),
    ) { _, arguments ->
        if (arguments.flag("open") == false) {
            canvas.timelapseReplay = null
        } else {
            val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork")
            canvas.timelapse.flush()
            val segments = canvas.timelapse.segmentsOf(open.folder)
            if (segments.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No timelapse recorded")
            canvas.timelapseReplay = segments
        }
        CommandResult.Success()
    }
    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.EXPORT,
            titleKey = TextKey("command_timelapse_export"),
            category = CommandCategory.Export,
            parameters =
                listOf(
                    ParameterSpec(
                        "length",
                        ParameterType.Enumeration,
                        JsonPrimitive(
                            TimelapseLength.Full.id,
                        ),
                        allowedValues =
                            TimelapseLength.entries.map {
                                it.id
                            },
                    ),
                    ParameterSpec("cards", ParameterType.Boolean, JsonPrimitive(false)),
                    ParameterSpec("author", ParameterType.String, JsonNull),
                    ParameterSpec("uri", ParameterType.String, JsonNull),
                ),
            undoable = false,
            availability = artwork,
        ),
    ) { _, arguments ->
        val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork")
        val exporter = timelapseExporter ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No timelapse exporter")
        canvas.timelapse.flush()
        val segments = canvas.timelapse.segmentsOf(open.folder)
        if (segments.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No timelapse recorded")
        val name =
            open.info.name
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .trim()
                .ifEmpty { "timelapse" }
        val folder = File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).apply { mkdirs() }
        val output = File(folder, "$name-timelapse.mp4")
        val cards =
            if (arguments.flag("cards") == true) {
                val author = (arguments["author"] as? JsonPrimitive)?.contentOrNull ?: open.info.artist?.name
                listOfNotNull(open.info.name, author?.takeIf { it.isNotBlank() })
            } else {
                emptyList()
            }
        val length = (arguments["length"] as? JsonPrimitive)?.contentOrNull ?: TimelapseLength.Full.id
        if (!exporter.export(segments, output, length, cards)) {
            return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Timelapse export failed")
        }
        val uri = (arguments["uri"] as? JsonPrimitive)?.contentOrNull
        if (uri != null) {
            if (!files.accepts(uri)) {
                return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
            }
            withContext(Dispatchers.IO) {
                files.openOutput(uri)?.use { stream -> output.inputStream().use { it.copyTo(stream) } }
            }
        } else {
            shareSheet.share(listOf(output), "video/mp4")
        }
        CommandResult.Success(JsonObject(mapOf("bytes" to JsonPrimitive(output.length()))))
    }
    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.NEW_CANVASES,
            titleKey = TextKey("command_timelapse_new_canvases"),
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        settings.changeRecordTimelapse(arguments.flag("enabled") == true)
        CommandResult.Success()
    }
}
