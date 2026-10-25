package com.mobyle.nanquim.ui.transition

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ArtworkTransitionsTest {
    @Test
    fun aPictureIsFittedAndCenteredInItsCell() {
        val cell = Rect(Offset(100f, 200f), Size(300f, 300f))
        assertEquals(Rect(Offset(100f, 237.5f), Size(300f, 225f)), ArtworkTransitions.fitted(2000f, 1500f, cell))
        assertSame(cell, ArtworkTransitions.fitted(0f, 1500f, cell))
    }

    @Test
    fun openingStartsFromTheCellAndClosingReplacesOpening() {
        val transitions = ArtworkTransitions()
        val cell = Rect(Offset(0f, 0f), Size(200f, 100f))
        transitions.cellPlaced("a", cell)
        transitions.prepareOpen("a")
        assertEquals(cell, transitions.opening?.from)
        transitions.prepareOpen("b")
        assertNull(transitions.opening?.from)
        val canvas = Rect(Offset(50f, 60f), Size(800f, 600f))
        transitions.prepareClose("b", canvas, null)
        assertNull(transitions.opening)
        val closing = transitions.closing!!
        assertEquals(canvas, closing.from)
        transitions.finish(closing)
        assertNull(transitions.closing)
    }
}
