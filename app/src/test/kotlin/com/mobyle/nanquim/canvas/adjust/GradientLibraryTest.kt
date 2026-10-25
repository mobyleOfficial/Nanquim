package com.mobyle.nanquim.canvas.adjust

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import com.mobyle.nanquim.formats.gradient.GradientDefinition
import com.mobyle.nanquim.formats.gradient.GradientStop
import java.io.File

class GradientLibraryTest {
    @TempDir
    lateinit var folder: File

    private val gradient = GradientDefinition("Mine", listOf(GradientStop(0f, 1f, 0f, 0f), GradientStop(1f, 0f, 0f, 1f)))

    @Test
    fun builtInsAreValidAndUnique() {
        val builtIns = GradientLibrary.BUILT_INS
        assertEquals(builtIns.size, builtIns.map { it.id }.toSet().size)
        assertTrue(builtIns.all { it.builtIn && it.nameRes != null && it.category != GradientCategory.Custom })
        assertTrue(
            GradientCategory.entries.filter { it != GradientCategory.Custom }.all { category ->
                builtIns.any {
                    it.category ==
                        category
                }
            },
        )
    }

    @Test
    fun savedGradientsComeBackAfterALoad() {
        val library = GradientLibrary(folder)
        val entry = library.write(gradient)
        library.add(entry)
        assertFalse(entry.builtIn)
        assertEquals(GradientCategory.Custom, entry.category)
        val reloaded = GradientLibrary(folder)
        reloaded.publish(reloaded.load())
        assertEquals(gradient, reloaded.entry(entry.id)?.definition)
        library.add(library.write(gradient.copy(name = "Renamed"), entry.id))
        assertEquals("Renamed", library.entry(entry.id)?.definition?.name)
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test
    fun deletesOnlyUserGradientsAndSkipsBrokenFiles() {
        val library = GradientLibrary(folder)
        val entry = library.write(gradient)
        library.add(entry)
        File(folder, "broken.nqgradient").writeText("{not json")
        assertEquals(GradientLibrary.BUILT_INS.size + 1, library.load().size)
        library.deleteFile(entry.id)
        library.remove(entry.id)
        assertNull(library.entry(entry.id))
        assertThrows<IllegalArgumentException> { library.deleteFile(GradientLibrary.BUILT_INS.first().id) }
    }
}
