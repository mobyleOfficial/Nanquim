package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.R
import com.mobyle.nanquim.color.PaletteLibrary
import com.mobyle.nanquim.command.PaletteCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.formats.palette.Palette
import com.mobyle.nanquim.formats.palette.PaletteSwatch
import java.io.File

@RunWith(AndroidJUnit4::class)
class PaletteViewsTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val commands = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val current = ColorValue.fromRgb(0.2f, 0.4f, 0.6f, alpha = 1f)
    private lateinit var library: PaletteLibrary

    @Before
    fun setUp() {
        val folder = File(context.cacheDir, "palette-views-test").apply { deleteRecursively() }
        val seed = Palette("Test", listOf(PaletteSwatch(RgbaColor(1f, 0f, 0f, 1f), "Red"), null))
        library = PaletteLibrary(folder, { listOf(seed) }, Dispatchers.Unconfined)
        runBlocking { library.load() }
    }

    private fun show() =
        compose.setContent {
            PalettesMode(library, current, onCommand = { id, arguments -> commands += id to arguments }, modifier = Modifier.width(340.dp))
        }

    @Test
    fun tapOnAColorMakesItThePrimary() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.palette_slot, 1, "Red")).performClick()
        assertEquals(ToolCommandIds.COLOR_SET_PRIMARY, commands.last().first)
        assertEquals(1f, commands.last().second["red"])
        assertEquals(0f, commands.last().second["green"])
    }

    @Test
    fun tapOnAnEmptySlotStoresTheCurrentColor() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.palette_empty_slot, 2)).performClick()
        val (id, arguments) = commands.last()
        assertEquals(PaletteCommandIds.SET_SWATCH, id)
        assertEquals(1, arguments["index"])
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 1f), arguments["color"])
    }

    @Test
    fun longPressOffersReplaceAndRemove() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.palette_slot, 1, "Red")).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.palette_remove_swatch)).performClick()
        assertEquals(PaletteCommandIds.SET_SWATCH, commands.last().first)
        assertEquals(0, commands.last().second["index"])
        assertEquals(null, commands.last().second["color"])
    }

    @Test
    fun plusCreatesAPaletteAndTheMenuActsOnIt() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.palette_add)).performClick()
        compose.onNodeWithText(context.getString(R.string.palette_new)).performClick()
        assertEquals(PaletteCommandIds.CREATE, commands.last().first)
        assertEquals(context.getString(R.string.palette_new_name), commands.last().second["name"])
        compose.onNodeWithContentDescription(context.getString(R.string.palette_menu, "Test")).performClick()
        compose.onNodeWithText(context.getString(R.string.palette_duplicate)).performClick()
        assertEquals(PaletteCommandIds.DUPLICATE, commands.last().first)
        assertEquals(context.getString(R.string.palette_copy_name, "Test"), commands.last().second["name"])
    }

    @Test
    fun shareAsksForTheFormat() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.palette_menu, "Test")).performClick()
        compose.onNodeWithText(context.getString(R.string.palette_share)).performClick()
        compose.onNodeWithText(context.getString(R.string.palette_format_gpl)).performClick()
        assertEquals(PaletteCommandIds.EXPORT, commands.last().first)
        assertEquals("gpl", commands.last().second["format"])
    }
}
