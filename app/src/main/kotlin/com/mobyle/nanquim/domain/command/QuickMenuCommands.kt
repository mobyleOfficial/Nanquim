package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandContext
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.command.TextKey
import com.mobyle.nanquim.domain.canvas.CanvasSession
import com.mobyle.nanquim.domain.canvas.QuickMenuAnchor
import com.mobyle.nanquim.domain.settings.models.QuickMenuPreset
import com.mobyle.nanquim.domain.settings.models.QuickMenuSlot
import com.mobyle.nanquim.domain.settings.repositories.QuickMenuRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int

object QuickMenuCommandIds {
    const val SHOW = "quickMenu.show"
    const val HIDE = "quickMenu.hide"
    const val RUN = "quickMenu.run"
    const val GET = "quickMenu.get"
    const val SET_SLOT = "quickMenu.setSlot"
    const val ADD_PRESET = "quickMenu.addPreset"
    const val RENAME_PRESET = "quickMenu.renamePreset"
    const val DELETE_PRESET = "quickMenu.deletePreset"
    const val SELECT_PRESET = "quickMenu.selectPreset"
}

fun registerQuickMenuCommands(
    registry: CommandRegistry,
    session: CanvasSession,
    settings: QuickMenuRepository,
) {
    fun register(
        id: String,
        titleKey: TextKey,
        parameters: List<ParameterSpec> = emptyList(),
        handler: suspend (CommandContext, JsonObject) -> CommandResult,
    ) = registry.register(CommandSpec(id, titleKey, CommandCategory.Canvas, parameters, undoable = false)) { context, arguments ->
        handler(context, arguments)
    }

    fun answer() = CommandResult.Success(quickMenuJson(settings))
    val slotParameter = ParameterSpec("slot", ParameterType.Integer, minimum = 0.0, maximum = (QuickMenuPreset.SLOTS - 1).toDouble())
    val presetParameter = ParameterSpec("preset", ParameterType.Integer, minimum = 0.0)

    fun JsonObject.preset(): Int? = (this["preset"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.int

    fun badPreset(index: Int) = CommandResult.Failure(CommandErrorCode.InvalidArguments, "No preset $index")

    register(
        QuickMenuCommandIds.SHOW,
        TextKey("command_quick_menu_show"),
        listOf(ParameterSpec("x", ParameterType.Number, JsonNull), ParameterSpec("y", ParameterType.Number, JsonNull)),
    ) { _, arguments ->
        fun coordinate(name: String) = (arguments[name] as? JsonPrimitive)?.doubleOrNull?.toFloat()
        session.quickMenu = QuickMenuAnchor(coordinate("x"), coordinate("y"))
        answer()
    }
    register(QuickMenuCommandIds.HIDE, TextKey("command_quick_menu_hide")) { _, _ ->
        session.quickMenu = null
        CommandResult.Success()
    }
    register(QuickMenuCommandIds.GET, TextKey("command_quick_menu_get")) { _, _ -> answer() }
    register(QuickMenuCommandIds.RUN, TextKey("command_quick_menu_run"), listOf(slotParameter)) { context, arguments ->
        session.quickMenu = null
        val slot =
            settings.active.slots[arguments.getValue("slot").jsonInt()]
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The slot is empty")
        registry.execute(context, slot.commandId, slot.arguments)
    }
    register(
        QuickMenuCommandIds.SET_SLOT,
        TextKey("command_quick_menu_set_slot"),
        listOf(
            slotParameter,
            ParameterSpec("command", ParameterType.String, JsonNull),
            ParameterSpec("arguments", ParameterType.Object, JsonNull),
            ParameterSpec("label", ParameterType.String, JsonNull),
            presetParameter.copy(defaultValue = JsonNull),
        ),
    ) { _, arguments ->
        val preset = arguments.preset() ?: settings.activeIndex
        if (preset !in settings.presets.indices) return@register badPreset(preset)
        val commandId = (arguments["command"] as? JsonPrimitive)?.contentOrNull
        val slot =
            commandId?.let { id ->
                val spec =
                    registry.find(id) ?: return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "Unknown command $id")
                if (spec.id.startsWith(QUICK_MENU_PREFIX)) {
                    return@register CommandResult.Failure(CommandErrorCode.InvalidArguments, "A slot cannot hold a quick-menu command")
                }
                QuickMenuSlot(
                    spec.id,
                    arguments["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
                    (arguments["label"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        settings.setSlot(preset, arguments.getValue("slot").jsonInt(), slot)
        answer()
    }
    register(
        QuickMenuCommandIds.ADD_PRESET,
        TextKey("command_quick_menu_add_preset"),
        listOf(ParameterSpec("name", ParameterType.String)),
    ) { _, arguments ->
        settings.addPreset(arguments.getValue("name").jsonString())
        answer()
    }
    register(
        QuickMenuCommandIds.RENAME_PRESET,
        TextKey("command_quick_menu_rename_preset"),
        listOf(presetParameter, ParameterSpec("name", ParameterType.String)),
    ) { _, arguments ->
        val preset = arguments.preset()!!
        if (preset !in settings.presets.indices) return@register badPreset(preset)
        settings.renamePreset(preset, arguments.getValue("name").jsonString())
        answer()
    }
    register(QuickMenuCommandIds.DELETE_PRESET, TextKey("command_quick_menu_delete_preset"), listOf(presetParameter)) { _, arguments ->
        val preset = arguments.preset()!!
        if (preset !in settings.presets.indices) return@register badPreset(preset)
        if (!settings.deletePreset(preset)) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The last preset stays")
        answer()
    }
    register(QuickMenuCommandIds.SELECT_PRESET, TextKey("command_quick_menu_select_preset"), listOf(presetParameter)) { _, arguments ->
        val preset = arguments.preset()!!
        if (preset !in settings.presets.indices) return@register badPreset(preset)
        settings.select(preset)
        answer()
    }
}

private const val QUICK_MENU_PREFIX = "quickMenu."

private fun JsonElement.jsonInt(): Int = (this as JsonPrimitive).int

private fun JsonElement.jsonString(): String = (this as JsonPrimitive).content

private fun quickMenuJson(settings: QuickMenuRepository): JsonObject =
    JsonObject(
        mapOf(
            "active" to JsonPrimitive(settings.activeIndex),
            "presets" to JsonArray(settings.presets.map { it.toJson() }),
        ),
    )
