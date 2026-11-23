package com.mobyle.nanquim.domain.command

import com.mobyle.nanquim.R
import com.mobyle.nanquim.data.feedback.FeedbackActions
import com.mobyle.nanquim.document.command.CommandCategory
import com.mobyle.nanquim.document.command.CommandErrorCode
import com.mobyle.nanquim.document.command.CommandRegistry
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.document.command.CommandSpec
import com.mobyle.nanquim.document.command.ParameterSpec
import com.mobyle.nanquim.document.command.ParameterType
import com.mobyle.nanquim.domain.feedback.AppInfo
import com.mobyle.nanquim.domain.feedback.AppLinks
import com.mobyle.nanquim.domain.feedback.FeedbackChannel
import com.mobyle.nanquim.domain.feedback.FeedbackKind
import com.mobyle.nanquim.domain.feedback.FeedbackLinks
import com.mobyle.nanquim.domain.settings.repositories.AppSettingsRepository
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object FeedbackCommandIds {
    const val RATE = "settings.rate"
    const val SHARE_APP = "settings.shareApp"
    const val REPORT = "settings.feedback.report"
    const val PRIVACY_POLICY_OPEN = "settings.privacyPolicy.open"
    const val TERMS_OPEN = "settings.terms.open"
    const val USAGE_STATISTICS = "settings.usageStatistics.set"
    const val GALLERY_ADS = "settings.galleryAds.set"
}

/** Texts the commands need from the app's resources, so the commands stay free of Android contexts. */
class FeedbackTexts(
    val shareApp: () -> String,
    val emailSubject: (FeedbackKind) -> String,
    val emailBody: (FeedbackKind, AppInfo) -> String,
)

fun registerFeedbackCommands(
    registry: CommandRegistry,
    actions: FeedbackActions,
    appSettings: AppSettingsRepository,
    appInfo: suspend () -> AppInfo,
    texts: FeedbackTexts,
    onUsageStatisticsChanged: (Boolean) -> Unit,
) {
    fun spec(
        id: String,
        title: Int,
        parameters: List<ParameterSpec> = emptyList(),
    ) = CommandSpec(id, title, CommandCategory.Settings, parameters, undoable = false)

    fun result(
        opened: Boolean,
        what: String,
    ): CommandResult = if (opened) CommandResult.Success() else CommandResult.Failure(CommandErrorCode.HandlerFailed, "No app opens $what")

    registry.register(spec(FeedbackCommandIds.RATE, R.string.command_settings_rate)) { _, _ ->
        result(actions.openStoreListing(), "the Play Store")
    }
    registry.register(spec(FeedbackCommandIds.SHARE_APP, R.string.command_settings_share_app)) { _, _ ->
        result(actions.shareText(texts.shareApp()), "a share sheet")
    }
    registry.register(spec(FeedbackCommandIds.PRIVACY_POLICY_OPEN, R.string.command_settings_privacy_policy)) { _, _ ->
        result(actions.openUrl(AppLinks.PRIVACY_POLICY), AppLinks.PRIVACY_POLICY)
    }
    registry.register(spec(FeedbackCommandIds.TERMS_OPEN, R.string.command_settings_terms)) { _, _ ->
        result(actions.openUrl(AppLinks.TERMS), AppLinks.TERMS)
    }
    registry.register(
        spec(
            FeedbackCommandIds.REPORT,
            R.string.command_settings_feedback_report,
            listOf(
                ParameterSpec("kind", ParameterType.Enumeration, allowedValues = FeedbackKind.entries.map { it.id }),
                ParameterSpec("channel", ParameterType.Enumeration, allowedValues = FeedbackChannel.entries.map { it.id }),
            ),
        ),
    ) { _, arguments ->
        val kind = FeedbackKind.fromId(arguments.getValue("kind").jsonPrimitive.content) ?: FeedbackKind.Bug
        val channel = FeedbackChannel.fromId(arguments.getValue("channel").jsonPrimitive.content) ?: FeedbackChannel.GitHub
        val info = appInfo()
        when (channel) {
            FeedbackChannel.GitHub -> result(actions.openUrl(FeedbackLinks.gitHubIssue(kind, info)), "GitHub")
            FeedbackChannel.Email -> result(actions.email(texts.emailSubject(kind), texts.emailBody(kind, info)), "email")
        }
    }
    registry.register(
        spec(
            FeedbackCommandIds.USAGE_STATISTICS,
            R.string.command_settings_usage_statistics,
            listOf(ParameterSpec("enabled", ParameterType.Boolean)),
        ),
    ) { _, arguments ->
        val enabled = arguments.getValue("enabled").jsonPrimitive.boolean
        appSettings.changeUsageStatistics(enabled)
        onUsageStatisticsChanged(enabled)
        CommandResult.Success(buildJsonObject { put("enabled", enabled) })
    }
    registry.register(
        spec(
            FeedbackCommandIds.GALLERY_ADS,
            R.string.command_settings_gallery_ads,
            listOf(ParameterSpec("enabled", ParameterType.Boolean)),
        ),
    ) { _, arguments ->
        // Applies from the next launch: the banner already showing stays until then.
        val enabled = arguments.getValue("enabled").jsonPrimitive.boolean
        appSettings.changeGalleryAds(enabled)
        CommandResult.Success(buildJsonObject { put("enabled", enabled) })
    }
}
