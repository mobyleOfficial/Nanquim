package com.mobyle.nanquim.feedback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The intents behind the feedback commands. Each returns false when no app on the device can handle it. */
class FeedbackActions(
    private val context: Context,
) {
    fun openUrl(url: String): Boolean = start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun openStoreListing(): Boolean {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.PLAY_STORE_MARKET)).setPackage(PLAY_STORE_PACKAGE)
        return start(market) || openUrl(AppLinks.PLAY_STORE_WEB)
    }

    fun shareText(text: String): Boolean {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return start(Intent.createChooser(send, null))
    }

    fun email(
        subject: String,
        body: String,
    ): Boolean = start(Intent(Intent.ACTION_SENDTO, Uri.parse(FeedbackLinks.emailUri(subject, body))))

    private fun start(intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
