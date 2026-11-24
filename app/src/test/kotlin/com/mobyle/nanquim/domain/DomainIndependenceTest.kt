package com.mobyle.nanquim.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The domain must build for any platform the app runs on (a desktop app is planned), so it names no Android type.
 * Compose's runtime is allowed: its state holders are multiplatform. What the domain needs from a platform it asks
 * for through the interfaces in `domain.platform`.
 */
class DomainIndependenceTest {
    @Test
    fun `the domain names no Android type`() {
        val offences =
            File("src/main/kotlin/com/mobyle/nanquim/domain")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        "${file.name}:${index + 1}: ${line.trim()}".takeIf {
                            FORBIDDEN.containsMatchIn(line) &&
                                !ALLOWED.containsMatchIn(line)
                        }
                    }
                }.toList()
        assertEquals(emptyList<String>(), offences)
    }

    private companion object {
        val FORBIDDEN =
            Regex("""\bandroidx?\.|\bcom\.mobyle\.nanquim\.(R|BuildConfig)\b|\bR\.(string|plurals|drawable|array|raw)\.|\bBuildConfig\.""")
        val ALLOWED = Regex("""\bandroidx\.compose\.runtime\.""")
    }
}
