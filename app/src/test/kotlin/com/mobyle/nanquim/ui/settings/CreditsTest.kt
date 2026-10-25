package com.mobyle.nanquim.ui.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CreditsTest {
    @Test
    fun parsesTheProjectCredits() {
        val entries = parseCredits(File("../CREDITS.md").readText())
        assertTrue(entries.any { it.name.startsWith("LZ4") && it.license == "BSD-2-Clause" })
        assertTrue(entries.any { it.section == "Assets" })
        assertFalse(entries.any { it.name.startsWith("GoogleTest") || it.name.startsWith("JUnit") })
        assertTrue(entries.all { it.license.isNotBlank() })
    }
}
