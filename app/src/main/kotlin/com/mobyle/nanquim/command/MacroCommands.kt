package com.mobyle.nanquim.command

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.mobyle.nanquim.R
import com.mobyle.nanquim.macro.Macro
import com.mobyle.nanquim.macro.Macros
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType

object MacroCommandIds {
    const val PANEL = "macro.panel.show"
    const val RECORD_START = "macro.record.start"
    const val RECORD_STOP = "macro.record.stop"
    const val RECORD_CANCEL = "macro.record.cancel"
    const val LIST = "macro.list"
    const val RUN = "macro.run"
    const val RENAME = "macro.rename"
    const val DELETE = "macro.delete"
}

fun registerMacroCommands(
    registry: CommandRegistry,
    macros: Macros,
) {
    fun register(
        id: String,
        titleRes: Int,
        parameters: List<ParameterSpec> = emptyList(),
        handler: suspend (com.mobyle.nanquim.document.command.CommandContext, JsonObject) -> CommandResult,
    ) = registry.register(CommandSpec(id, titleRes, CommandCategory.Settings, parameters, undoable = false)) { context, arguments ->
        handler(context, arguments)
    }

    fun list() =
        CommandResult.Success(
            buildJsonObject {
                put(
                    "macros",
                    JsonArray(
                        macros.macros.map {
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(it.id),
                                    "name" to JsonPrimitive(it.name),
                                    "steps" to JsonPrimitive(it.steps.size),
                                ),
                            )
                        },
                    ),
                )
                put("recording", macros.recordingName?.let(::JsonPrimitive) ?: JsonNull)
                put("recordedSteps", macros.recordedSteps)
            },
        )

    val idParameter = ParameterSpec("id", ParameterType.String)

    fun JsonObject.text(name: String) = (getValue(name) as JsonPrimitive).content

    fun unknown(id: String) = CommandResult.Failure(CommandErrorCode.InvalidArguments, "No macro $id")
    val running = mutableSetOf<String>()

    register(
        MacroCommandIds.PANEL,
        R.string.command_macro_panel,
        listOf(ParameterSpec("visible", ParameterType.Boolean, JsonNull)),
    ) { _, arguments ->
        macros.panelVisible = (arguments["visible"] as? JsonPrimitive)?.booleanOrNull ?: !macros.panelVisible
        list()
    }
    register(
        MacroCommandIds.RECORD_START,
        R.string.command_macro_record_start,
        listOf(ParameterSpec("name", ParameterType.String)),
    ) { _, arguments ->
        val name = arguments.text("name").trim()
        if (name.isEmpty()) return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A macro needs a name")
        macros.startRecording(name)
        list()
    }
    register(MacroCommandIds.RECORD_STOP, R.string.command_macro_record_stop) { _, _ ->
        if (macros.recordingName == null) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "Not recording")
        val saved = macros.stopRecording()
        CommandResult.Success(JsonObject(mapOf("id" to (saved?.id?.let(::JsonPrimitive) ?: JsonNull))))
    }
    register(MacroCommandIds.RECORD_CANCEL, R.string.command_macro_record_cancel) { _, _ ->
        macros.cancelRecording()
        list()
    }
    register(MacroCommandIds.LIST, R.string.command_macro_list) { _, _ -> list() }
    register(MacroCommandIds.RUN, R.string.command_macro_run, listOf(idParameter)) { context, arguments ->
        val macro = macros.find(arguments.text("id")) ?: return@register unknown(arguments.text("id"))
        if (!running.add(macro.id)) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The macro is already running")
        try {
            macro.steps.forEachIndexed { index, step ->
                val result = registry.execute(context, step.commandId, step.arguments)
                if (result is CommandResult.Failure) {
                    return@register CommandResult.Failure(
                        result.code,
                        "Step ${index + 1} (${step.commandId}) failed: ${result.message}",
                    )
                }
            }
        } finally {
            running -= macro.id
        }
        CommandResult.Success(JsonObject(mapOf("steps" to JsonPrimitive(macro.steps.size))))
    }
    register(
        MacroCommandIds.RENAME,
        R.string.command_macro_rename,
        listOf(idParameter, ParameterSpec("name", ParameterType.String)),
    ) { _, arguments ->
        if (!macros.rename(arguments.text("id"), arguments.text("name").trim())) return@register unknown(arguments.text("id"))
        list()
    }
    register(MacroCommandIds.DELETE, R.string.command_macro_delete, listOf(idParameter)) { _, arguments ->
        if (!macros.delete(arguments.text("id"))) return@register unknown(arguments.text("id"))
        list()
    }
}
