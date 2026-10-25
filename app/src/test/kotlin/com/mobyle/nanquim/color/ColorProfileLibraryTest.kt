package com.mobyle.nanquim.color

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import com.mobyle.nanquim.engine.ColorProfileInfo
import com.mobyle.nanquim.engine.ColorProfileReadResult
import com.mobyle.nanquim.engine.ProfileColorSpace
import java.io.File

class ColorProfileLibraryTest {
    @TempDir
    lateinit var folder: File

    private fun read(bytes: ByteArray): ColorProfileReadResult =
        when (bytes.firstOrNull()) {
            RGB -> ColorProfileReadResult.Success(ColorProfileInfo("Fake RGB ${bytes.size}", ProfileColorSpace.Rgb, true, true, 4))
            CMYK -> ColorProfileReadResult.Success(ColorProfileInfo("Fake CMYK", ProfileColorSpace.Cmyk, true, false, 2))
            PRINT -> ColorProfileReadResult.Success(ColorProfileInfo("Fake print CMYK", ProfileColorSpace.Cmyk, true, true, 4))
            else -> ColorProfileReadResult.Invalid("not a profile")
        }

    private fun builtIn(file: String) =
        when {
            file.startsWith("CGATS") -> byteArrayOf(CMYK)
            file.startsWith("NanquimCMYK") -> byteArrayOf(PRINT)
            else -> byteArrayOf(RGB, file.length.toByte())
        }

    private fun library() = ColorProfileLibrary(File(folder, "profiles"), ::builtIn, ::read, Dispatchers.Unconfined)

    @Test
    fun bundledProfilesAndDocumentProfiles() =
        runBlocking {
            val library = library()
            library.load()
            assertEquals(ColorProfileLibrary.BUILT_INS.map { it.id }, library.entries.map { it.id })
            assertEquals(listOf("srgb", "display-p3", "adobe-rgb", "rec2020"), library.documentProfiles.map { it.id })
            assertEquals(listOf(ColorProfileLibrary.NANQUIM_CMYK_ID), library.printProfiles.map { it.id })
            assertNull(library.printProfile("cgats001"))
            assertEquals("Nanquim CMYK (CGATS TR 001)", library.printProfile(ColorProfileLibrary.NANQUIM_CMYK_ID)!!.name)
            assertNull(library.documentProfile(ColorProfileLibrary.SRGB_ID))
            val p3 = library.documentProfile("display-p3")!!
            assertEquals("Display P3", p3.name)
            assertTrue(p3.icc.contentEquals(builtIn("DisplayP3-v4.icc")))
        }

    @Test
    fun importsKeepsDeletesAndRefuses() =
        runBlocking {
            val library = library()
            library.load()
            val imported = library.import(byteArrayOf(RGB, 9, 9, 9), "file name")
            assertEquals("Fake RGB 4", imported.name)
            assertFalse(imported.builtIn)
            assertSame(imported, library.import(byteArrayOf(RGB, 9, 9, 9), "again"))
            assertThrows<ColorProfileException> { library.import(byteArrayOf(0), "junk") }
            val reloaded = library()
            reloaded.load()
            assertEquals(imported.id, reloaded.entries.last().id)
            assertEquals("Fake RGB 4", reloaded.entries.last().name)
            assertFalse(reloaded.delete(ColorProfileLibrary.SRGB_ID))
            assertTrue(reloaded.delete(imported.id))
            assertFalse(reloaded.entries.any { it.id == imported.id })
            assertNull(reloaded.bytes(imported.id))
        }

    private companion object {
        const val RGB: Byte = 1
        const val CMYK: Byte = 2
        const val PRINT: Byte = 3
    }
}
