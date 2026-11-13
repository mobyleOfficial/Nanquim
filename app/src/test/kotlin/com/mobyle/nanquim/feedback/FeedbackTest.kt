package com.mobyle.nanquim.feedback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeedbackTest {
    private val info = AppInfo(version = "0.3.0 (3)", device = "samsung SM-T733", android = "14 (API 34)", renderer = "vulkan")

    @Test
    fun aBugReportPrefillsTheIssueFormFields() {
        val url = FeedbackLinks.gitHubIssue(FeedbackKind.Bug, info)
        assertEquals(
            "https://github.com/mobyleOfficial/Nanquim/issues/new?template=bug_report.yml" +
                "&version=0.3.0%20%283%29&device=samsung%20SM-T733&android=14%20%28API%2034%29&renderer=vulkan",
            url,
        )
    }

    @Test
    fun aSuggestionOnlyCarriesTheVersion() {
        val url = FeedbackLinks.gitHubIssue(FeedbackKind.Feature, info)
        assertEquals("https://github.com/mobyleOfficial/Nanquim/issues/new?template=feature_request.yml&version=0.3.0%20%283%29", url)
    }

    @Test
    fun theEmailKeepsLineBreaksAndAccents() {
        val uri = FeedbackLinks.emailUri("Nanquim: sugestão", "a\nb")
        assertEquals("mailto:support@mobyleapps.com?subject=Nanquim%3A%20sugest%C3%A3o&body=a%0Ab", uri)
    }

    @Test
    fun theReviewWaitsForThreeExportsAndThenNinetyDays() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(ReviewPolicy.shouldAsk(exportsSinceAsked = 2, lastAskedMillis = null, nowMillis = 0))
        assertTrue(ReviewPolicy.shouldAsk(exportsSinceAsked = 3, lastAskedMillis = null, nowMillis = 0))
        assertFalse(ReviewPolicy.shouldAsk(exportsSinceAsked = 10, lastAskedMillis = 0, nowMillis = 89 * day))
        assertTrue(ReviewPolicy.shouldAsk(exportsSinceAsked = 3, lastAskedMillis = 0, nowMillis = 90 * day))
    }
}
