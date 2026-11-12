package com.mobyle.nanquim.feedback

import android.app.Activity
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Shows Google Play's review card. Play decides on its own whether to show it (there is a quota), and says nothing
 * either way, so this is never tied to a button: Settings › Rate opens the store listing instead.
 */
suspend fun requestInAppReview(activity: Activity) {
    runCatching {
        val manager = ReviewManagerFactory.create(activity)
        manager.launchReview(activity, manager.requestReview())
    }
}
