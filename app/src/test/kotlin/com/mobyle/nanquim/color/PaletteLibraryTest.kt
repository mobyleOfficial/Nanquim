package com.mobyle.nanquim.color

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.File

class PaletteLibraryTest {
    @TempDir
    lateinit var folder: File

    private val seeds = listOf(Palette("One", listOf(null, null)), Palette("Two", emptyList()))

    private fun library() = PaletteLibrary(folder, { seeds }, Dispatchers.Unconfined)

    private val red = PaletteSwatch(RgbaColor(1f, 0f, 0f, 1f), "Red")

    @Test
    fun firstLoadSeedsAndLaterLoadsKeepChanges() =
        runBlocking {
            val library = library()
            library.load()
            assertEquals(listOf("One", "Two"), library.entries.map { it.palette.name })
            assertEquals(library.entries.first().id, library.defaultId)
            val one = library.entries.first().id
            library.setSwatch(one, 1, red)
            library.rename(one, "First")
            val reloaded = library()
            reloaded.load()
            assertEquals(listOf("First", "Two"), reloaded.entries.map { it.palette.name })
            assertEquals(
                red,
                reloaded.entries
                    .first()
                    .palette.swatches[1],
            )
        }

    @Test
    fun deletingEverythingDoesNotSeedAgain() =
        runBlocking {
            val library = library()
            library.load()
            for (entry in library.entries) library.delete(entry.id)
            assertNull(library.defaultId)
            val reloaded = library()
            reloaded.load()
            assertTrue(reloaded.entries.isEmpty())
        }

    @Test
    fun createDuplicateMoveAndDefault() =
        runBlocking {
            val library = library()
            library.load()
            val created = library.create("New")
            assertEquals(PaletteLibrary.NEW_PALETTE_SLOTS, created.palette.swatches.size)
            val copy = library.duplicate(library.entries.first().id, "Copy")
            assertEquals(listOf("One", "Two", "New", "Copy"), library.entries.map { it.palette.name })
            library.move(copy.id, 0)
            library.setDefault(created.id)
            val reloaded = library()
            reloaded.load()
            assertEquals(listOf("Copy", "One", "Two", "New"), reloaded.entries.map { it.palette.name })
            assertEquals(created.id, reloaded.defaultId)
            reloaded.delete(created.id)
            assertEquals(copy.id, reloaded.defaultId)
        }

    @Test
    fun slotsCanBeAppendedButNotSkipped() =
        runBlocking {
            val library = library()
            library.load()
            val two = library.entries[1].id
            library.setSwatch(two, 0, red)
            assertEquals(listOf(red), library.entry(two)!!.palette.swatches)
            assertThrows<IllegalArgumentException> { runBlocking { library.setSwatch(two, 5, red) } }
            library.setSwatch(two, 0, null)
            assertEquals(listOf<PaletteSwatch?>(null), library.entry(two)!!.palette.swatches)
        }

    @Test
    fun unreadableFilesAreSkippedAndUnlistedFilesAppended() =
        runBlocking {
            val library = library()
            library.load()
            File(folder, "broken.${PaletteLibrary.EXTENSION}").writeText("not json")
            File(folder, "extra.${PaletteLibrary.EXTENSION}").writeText(Palette("Extra", emptyList()).toJson("extra"))
            val reloaded = library()
            reloaded.load()
            assertEquals(listOf("One", "Two", "Extra"), reloaded.entries.map { it.palette.name })
        }

    @Test
    fun builtInPalettesAreValid() {
        val palettes = builtInPalettes { "name" }
        assertEquals(3, palettes.size)
        assertTrue(palettes.all { it.swatches.size % Palette.DEFAULT_COLUMNS == 0 && it.swatches.first() != null })
    }
}
