package com.mobyle.nanquim.feedback

import java.net.URLEncoder

object AppLinks {
    /** The Play Store package, the same for every flavor so a dev build still points at the published app. */
    const val STORE_PACKAGE = "com.mobyle.nanquim"
    const val PLAY_STORE_WEB = "https://play.google.com/store/apps/details?id=$STORE_PACKAGE"
    const val PLAY_STORE_MARKET = "market://details?id=$STORE_PACKAGE"
    const val PRIVACY_POLICY = "https://mobyleapps.com/nanquim/privacy-policy/"
    const val TERMS = "https://mobyleapps.com/nanquim/terms/"
    const val REPOSITORY = "https://github.com/mobyleOfficial/Nanquim"
    const val SUPPORT_EMAIL = "support@mobyleapps.com"
}

enum class FeedbackKind(
    val id: String,
    val template: String,
) {
    Bug("bug", "bug_report.yml"),
    Feature("feature", "feature_request.yml"),
    ;

    companion object {
        fun fromId(id: String?): FeedbackKind? = entries.firstOrNull { it.id == id }
    }
}

enum class FeedbackChannel(
    val id: String,
) {
    GitHub("github"),
    Email("email"),
    ;

    companion object {
        fun fromId(id: String?): FeedbackChannel? = entries.firstOrNull { it.id == id }
    }
}

/** What a report says about the app and the device; the field names match the GitHub issue forms' ids. */
data class AppInfo(
    val version: String,
    val device: String,
    val android: String,
    val renderer: String,
) {
    fun fields(): List<Pair<String, String>> =
        listOf("version" to version, "device" to device, "android" to android, "renderer" to renderer)

    fun summary(): String = fields().joinToString("\n") { (name, value) -> "$name: $value" }
}

object FeedbackLinks {
    fun gitHubIssue(
        kind: FeedbackKind,
        info: AppInfo,
    ): String {
        val parameters =
            listOf("template" to kind.template) + if (kind == FeedbackKind.Bug) info.fields() else listOf("version" to info.version)
        return "${AppLinks.REPOSITORY}/issues/new?" + parameters.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
    }

    fun emailUri(
        subject: String,
        body: String,
    ): String = "mailto:${AppLinks.SUPPORT_EMAIL}?subject=${encode(subject)}&body=${encode(body)}"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** When to ask for a Play review on its own: after a few artworks were shared, and at most once per [COOLDOWN_MILLIS]. */
object ReviewPolicy {
    const val EXPORTS_BEFORE_ASKING = 3
    const val COOLDOWN_MILLIS = 90L * 24 * 60 * 60 * 1000

    fun shouldAsk(
        exportsSinceAsked: Int,
        lastAskedMillis: Long?,
        nowMillis: Long,
    ): Boolean {
        if (exportsSinceAsked < EXPORTS_BEFORE_ASKING) return false
        return lastAskedMillis == null || nowMillis - lastAskedMillis >= COOLDOWN_MILLIS
    }
}
