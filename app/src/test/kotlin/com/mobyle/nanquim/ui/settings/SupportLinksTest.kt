package com.mobyle.nanquim.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupportLinksTest {
    @Test
    fun onlyCompleteHttpsLinksShow() {
        val links =
            parseSupportLinks(
                listOf(
                    "Open Collective|https://opencollective.com/example",
                    " GitHub Sponsors | https://github.com/sponsors/example ",
                    "No address|",
                    "|https://example.org",
                    "Plain http|http://example.org",
                    "Spaces|https://example.org/a b",
                    "no separator",
                ),
            )
        assertEquals(
            listOf(
                SupportLink("Open Collective", "https://opencollective.com/example"),
                SupportLink("GitHub Sponsors", "https://github.com/sponsors/example"),
            ),
            links,
        )
    }
}
