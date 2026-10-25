package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import android.content.ContentResolver
import androidx.core.net.toUri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.ArtworkTransfer
import com.mobyle.nanquim.gallery.TimelapseExportWorker
import com.mobyle.nanquim.gallery.TimelapseLength
import java.io.File
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.document.command.AppState
import com.mobyle.nanquim.document.command.Availability
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.formats.nanquim.TimelapseResolution
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings

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
    settings: AppSettings,
    workManager: WorkManager?,
    cacheDirectory: File,
    contentResolver: ContentResolver,
    shareSheet: ShareSheet,
) {
    val artwork: (AppState) -> Availability = { state ->
        if (state.hasOpenDocument) Availability.Enabled else Availability.Disabled("No open document")
    }

    fun JsonObject.flag(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull

    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.SET_ENABLED,
            titleRes = R.string.command_timelapse_set_enabled,
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
            titleRes = R.string.command_timelapse_set_settings,
            category = CommandCategory.Canvas,
            parameters =
                listOf(
                    ParameterSpec("resolution", ParameterType.Enumeration, JsonNull, allowedValues = TimelapseResolution.entries.map { it.id }),
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
            titleRes = R.string.command_timelapse_replay,
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
            titleRes = R.string.command_timelapse_export,
            category = CommandCategory.Export,
            parameters =
                listOf(
                    ParameterSpec("length", ParameterType.Enumeration, JsonPrimitive(TimelapseLength.Full.id), allowedValues = TimelapseLength.entries.map { it.id }),
                    ParameterSpec("cards", ParameterType.Boolean, JsonPrimitive(false)),
                    ParameterSpec("author", ParameterType.String, JsonNull),
                    ParameterSpec("uri", ParameterType.String, JsonNull),
                ),
            undoable = false,
            availability = artwork,
        ),
    ) { _, arguments ->
        val open = canvas.artwork ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No artwork")
        val manager = workManager ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No WorkManager")
        canvas.timelapse.flush()
        val segments = canvas.timelapse.segmentsOf(open.folder)
        if (segments.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No timelapse recorded")
        val name = open.info.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "timelapse" }
        val folder = File(cacheDirectory, ArtworkTransfer.EXPORT_FOLDER).apply { mkdirs() }
        val output = File(folder, "$name-timelapse.mp4")
        val cards =
            if (arguments.flag("cards") == true) {
                val author = (arguments["author"] as? JsonPrimitive)?.contentOrNull ?: open.info.artist?.name
                listOfNotNull(open.info.name, author?.takeIf { it.isNotBlank() })
            } else {
                emptyList()
            }
        val request =
            OneTimeWorkRequestBuilder<TimelapseExportWorker>()
                .setInputData(
                    workDataOf(
                        TimelapseExportWorker.KEY_SEGMENTS to segments.map { it.path }.toTypedArray(),
                        TimelapseExportWorker.KEY_OUTPUT to output.path,
                        TimelapseExportWorker.KEY_LENGTH to ((arguments["length"] as? JsonPrimitive)?.contentOrNull ?: TimelapseLength.Full.id),
                        TimelapseExportWorker.KEY_CARD_LINES to cards.toTypedArray(),
                    ),
                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        manager.enqueueUniqueWork(EXPORT_WORK, ExistingWorkPolicy.REPLACE, request)
        val finished = manager.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished }
        if (finished.state != WorkInfo.State.SUCCEEDED) return@register CommandResult.Failure(CommandErrorCode.HandlerFailed, "Timelapse export failed")
        val uri = (arguments["uri"] as? JsonPrimitive)?.contentOrNull?.toUri()
        if (uri != null) {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Only content:// URIs are accepted")
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri, "wt")?.use { stream -> output.inputStream().use { it.copyTo(stream) } }
            }
        } else {
            shareSheet.share(listOf(output), "video/mp4")
        }
        CommandResult.Success(JsonObject(mapOf("bytes" to JsonPrimitive(output.length()))))
    }
    registry.register(
        CommandSpec(
            id = TimelapseCommandIds.NEW_CANVASES,
            titleRes = R.string.command_timelapse_new_canvases,
            category = CommandCategory.Settings,
            parameters = listOf(ParameterSpec("enabled", ParameterType.Boolean)),
            undoable = false,
        ),
    ) { _, arguments ->
        settings.changeRecordTimelapse(arguments.flag("enabled") == true)
        CommandResult.Success()
    }
}

private const val EXPORT_WORK = "timelapse-export"
