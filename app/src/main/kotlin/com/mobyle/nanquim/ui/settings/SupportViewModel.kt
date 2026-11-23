package com.mobyle.nanquim.ui.settings

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.command.CommandDispatcher
import com.mobyle.nanquim.command.FeedbackCommandIds
import com.mobyle.nanquim.command.HelpCommandIds
import com.mobyle.nanquim.feedback.AppDetails
import com.mobyle.nanquim.feedback.FeedbackChannel
import com.mobyle.nanquim.feedback.FeedbackKind
import com.mobyle.nanquim.feedback.dispatchFeedback
import com.mobyle.nanquim.navigation.AppNavigator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

@HiltViewModel
class SupportViewModel
    @Inject
    constructor(
        appDetails: AppDetails,
        private val dispatcher: CommandDispatcher,
        private val navigator: AppNavigator,
    ) : ViewModel() {
        val links: List<SupportLink> = appDetails.supportLinks()

        fun openLink(index: Int) = dispatcher.dispatch(HelpCommandIds.SUPPORT_OPEN_LINK, buildJsonObject { put("index", index) })

        fun shareApp(onOpenFailed: () -> Unit) = dispatcher.dispatchFeedback(FeedbackCommandIds.SHARE_APP, buildJsonObject {}, onOpenFailed)

        fun report(
            channel: FeedbackChannel,
            onOpenFailed: () -> Unit,
        ) = dispatcher.dispatchFeedback(
            FeedbackCommandIds.REPORT,
            buildJsonObject {
                put("kind", FeedbackKind.Bug.id)
                put("channel", channel.id)
            },
            onOpenFailed,
        )

        fun back() = navigator.back()
    }
