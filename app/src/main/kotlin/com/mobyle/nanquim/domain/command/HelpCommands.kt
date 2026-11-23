package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.data.help.Help
import com.mobyle.nanquim.data.help.WelcomeCard
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.document.command.TextKey
import com.mobyle.nanquim.domain.canvas.input.CanvasTool
import com.mobyle.nanquim.presentation.settings.SupportLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

object HelpCommandIds {
    const val WELCOME_SHOW = "help.welcome.show"
    const val WELCOME_CARD = "help.welcome.card"
    const val WELCOME_FINISH = "help.welcome.finish"
    const val TIP_SHOW = "help.tip.show"
    const val TIP_DISMISS = "help.tip.dismiss"
    const val TIPS_RESET = "help.tips.reset"
    const val MANUAL_OPEN = "help.manual.open"
    const val AUTOMATIC_SET = "help.automatic.set"
    const val SAMPLES_INSTALL = "gallery.samples.install"
    const val SUPPORT_OPEN_LINK = "settings.support.openLink"
}

fun registerHelpCommands(
    registry: CommandRegistry,
    help: Help,
    manualUrl: () -> String,
    openUrl: (String) -> Boolean,
    installSamples: suspend () -> List<String>,
    supportLinks: () -> List<SupportLink>,
) {
    fun spec(
        id: String,
        title: TextKey,
        parameters: List<ParameterSpec> = emptyList(),
    ) = CommandSpec(id, title, CommandCategory.Settings, parameters, undoable = false)

    registry.register(spec(HelpCommandIds.WELCOME_SHOW, TextKey("command_help_welcome_show"))) { _, _ ->
        help.showWelcome()
        CommandResult.Success()
    }
    registry.register(
        spec(
            HelpCommandIds.WELCOME_CARD,
            TextKey("command_help_welcome_card"),
            listOf(ParameterSpec("index", ParameterType.Integer, minimum = 0.0, maximum = WelcomeCard.entries.lastIndex.toDouble())),
        ),
    ) { _, arguments ->
        if (!help.welcomeVisible) return@register CommandResult.Failure(CommandErrorCode.Unavailable, "The welcome cards are not shown")
        help.welcomeCard = arguments.getValue("index").jsonPrimitive.int
        CommandResult.Success()
    }
    registry.register(spec(HelpCommandIds.WELCOME_FINISH, TextKey("command_help_welcome_finish"))) { _, _ ->
        help.finishWelcome(completed = false)
        CommandResult.Success()
    }
    val tipTools = Help.TIPS.map { it.name }
    registry.register(
        spec(
            HelpCommandIds.TIP_SHOW,
            TextKey("command_help_tip_show"),
            listOf(ParameterSpec("tool", ParameterType.Enumeration, allowedValues = tipTools)),
        ),
    ) { _, arguments ->
        help.showTip(CanvasTool.valueOf(arguments.getValue("tool").jsonPrimitive.content))
        CommandResult.Success()
    }
    registry.register(spec(HelpCommandIds.TIP_DISMISS, TextKey("command_help_tip_dismiss"))) { _, _ ->
        help.dismissTip()
        CommandResult.Success()
    }
    registry.register(spec(HelpCommandIds.TIPS_RESET, TextKey("command_help_tips_reset"))) { _, _ ->
        help.resetTips()
        CommandResult.Success()
    }
    registry.register(spec(HelpCommandIds.MANUAL_OPEN, TextKey("command_help_manual_open"))) { _, _ ->
        val url = manualUrl()
        when {
            url.isBlank() -> CommandResult.Failure(CommandErrorCode.Unavailable, "The project has no manual site yet")
            openUrl(url) -> CommandResult.Success()
            else -> CommandResult.Failure(CommandErrorCode.HandlerFailed, "No app opens $url")
        }
    }
    registry.register(
        spec(
            HelpCommandIds.SUPPORT_OPEN_LINK,
            TextKey("command_settings_support_open_link"),
            listOf(ParameterSpec("index", ParameterType.Integer, minimum = 0.0)),
        ),
    ) { _, arguments ->
        val link =
            supportLinks().getOrNull(arguments.getValue("index").jsonPrimitive.int)
                ?: return@register CommandResult.Failure(CommandErrorCode.Unavailable, "No such donation page")
        if (openUrl(
                link.url,
            )
        ) {
            CommandResult.Success()
        } else {
            CommandResult.Failure(CommandErrorCode.HandlerFailed, "No app opens ${link.url}")
        }
    }
    registry.register(spec(HelpCommandIds.SAMPLES_INSTALL, TextKey("command_gallery_samples_install"))) { _, _ ->
        val ids = installSamples()
        CommandResult.Success(JsonObject(mapOf("ids" to JsonArray(ids.map(::JsonPrimitive)))))
    }
    registry.register(
        spec(HelpCommandIds.AUTOMATIC_SET, TextKey("command_help_automatic_set"), listOf(ParameterSpec("enabled", ParameterType.Boolean))),
    ) { _, arguments ->
        help.automatic = arguments.getValue("enabled").jsonPrimitive.boolean
        CommandResult.Success()
    }
}

fun Help.stateJson() =
    kotlinx.serialization.json.buildJsonObject {
        put("welcomeVisible", JsonPrimitive(welcomeVisible))
        put("welcomeCard", JsonPrimitive(welcomeCard))
        put("welcomeSeen", JsonPrimitive(welcomeSeen))
        put("tourStep", tourStep?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("tip", tip?.name?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("automatic", JsonPrimitive(automatic))
    }
