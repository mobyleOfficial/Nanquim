package com.mobyle.nanquim.presentation.settings

import androidx.lifecycle.ViewModel
import com.mobyle.nanquim.core.navigation.AppNavigator
import com.mobyle.nanquim.data.feedback.AppDetails
import com.mobyle.nanquim.domain.command.CommandDispatcher
import com.mobyle.nanquim.domain.command.FeedbackCommandIds
import com.mobyle.nanquim.domain.command.HelpCommandIds
import com.mobyle.nanquim.domain.command.dispatchFeedback
import com.mobyle.nanquim.domain.feedback.FeedbackChannel
import com.mobyle.nanquim.domain.feedback.FeedbackKind
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
