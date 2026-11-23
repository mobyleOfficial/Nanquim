package com.mobyle.nanquim.presentation.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class TextKeysTest {
    /** Each `TextKey("name")` written in the domain: a key missing from the map would show its raw name in the UI. */
    private val domainKeys =
        File("src/main/kotlin/com/mobyle/nanquim/domain")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> KEY.findAll(file.readText()).map { it.groupValues[1] } }
            .toSortedSet()

    @Test
    fun `every key the domain uses has a string resource`() {
        assertEquals(emptySet<String>(), domainKeys - TEXT_RESOURCES.keys)
    }

    @Test
    fun `the map lists no key the domain stopped using`() {
        assertEquals(emptySet<String>(), TEXT_RESOURCES.keys - domainKeys)
    }

    private companion object {
        val KEY = Regex("""TextKey\("(\w+)"\)""")
    }
}
