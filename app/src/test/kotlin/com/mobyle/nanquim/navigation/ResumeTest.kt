package com.mobyle.nanquim.navigation

import com.mobyle.nanquim.canvas.SavedView
import com.mobyle.nanquim.canvas.camera.CameraState
import com.mobyle.nanquim.canvas.input.CanvasTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ResumeTest {
    @Test
    fun theScreensComeBackWithoutTheBrushEditor() {
        val navigator = AppNavigator()
        navigator.restore(listOf("Gallery", "Canvas", "BrushEditor"), artworkOpen = true)
        assertEquals(listOf("Gallery", "Canvas"), navigator.snapshot())
    }

    @Test
    fun theCanvasOnlyComesBackWithItsArtwork() {
        val navigator = AppNavigator()
        navigator.restore(listOf("Gallery", "Canvas", "Settings"), artworkOpen = false)
        assertEquals(listOf("Gallery", "Settings"), navigator.snapshot())
    }

    @Test
    fun aStackThatDoesNotStartAtTheGalleryIsIgnored() {
        val navigator = AppNavigator()
        navigator.restore(listOf("Settings", "Nonsense"), artworkOpen = false)
        assertEquals(listOf("Gallery"), navigator.snapshot())
    }

    @Test
    fun theViewRoundTripsThroughJson() {
        val view = SavedView("artwork-1", CameraState(120.5, 80.0, 2.5, 0.3, flippedHorizontally = true), CanvasTool.Eraser)
        assertEquals(view, SavedView.fromJson(view.toJson()))
    }

    @Test
    fun aToolThatStartsAnEditComesBackAsTheBrush() {
        val view = SavedView("artwork-1", null, CanvasTool.Transform)
        assertEquals(CanvasTool.Brush, SavedView.fromJson(view.toJson())?.tool)
    }

    @Test
    fun brokenJsonIsIgnored() {
        assertNull(SavedView.fromJson("{"))
        assertNull(
            SavedView.fromJson("""{"artwork":"a","tool":"Brush","camera":{"x":1,"y":1,"zoom":0,"rotation":0,"flipped":false}}""")?.camera,
        )
    }
}
