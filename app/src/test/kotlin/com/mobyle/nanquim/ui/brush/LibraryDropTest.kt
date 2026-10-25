package com.mobyle.nanquim.ui.brush

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LibraryDropTest {
    private val groupIds = listOf("inking", "sketching", "mine")
    private val groupRows = groupIds.withIndex().associate { (index, id) -> id to Rect(0f, 40f + index * 40f, 100f, 80f + index * 40f) }
    private val brushIds = listOf("a", "b", "c", "d")
    private val brushRows = brushIds.withIndex().associate { (index, id) -> id to Rect(110f, index * 100f, 400f, 96f + index * 100f) }
    private val brushListArea = Rect(110f, 0f, 400f, 560f)

    private fun target(
        item: LibraryDragItem,
        pointer: Offset,
        shownGroupId: String? = "inking",
        rows: Map<String, Rect> = brushRows,
    ) = LibraryDrop.target(item, pointer, groupRows, groupIds, rows, brushListArea, shownGroupId, brushIds)

    @Test
    fun brushMovesAmongTheShownBrushes() {
        val b = LibraryDragItem.Brush("b", "inking", "B")
        assertEquals(LibraryDropTarget.BrushPosition("inking", 2, "d", false), target(b, Offset(200f, 310f)))
        assertEquals(LibraryDropTarget.BrushPosition("inking", 3, "d", true), target(b, Offset(200f, 390f)))
        assertEquals(LibraryDropTarget.BrushPosition("inking", 0, "a", false), target(b, Offset(200f, 10f)))
        assertEquals(LibraryDropTarget.BrushPosition("inking", 3, "d", true), target(b, Offset(200f, 500f)))
    }

    @Test
    fun brushGoesIntoAnotherGroupRow() {
        val b = LibraryDragItem.Brush("b", "inking", "B")
        assertEquals(LibraryDropTarget.IntoGroup("mine"), target(b, Offset(50f, 130f)))
        assertNull(target(b, Offset(50f, 50f)))
        assertNull(target(b, Offset(105f, 50f)))
    }

    @Test
    fun recentIsNotReordered() {
        val b = LibraryDragItem.Brush("b", "inking", "B")
        assertNull(target(b, Offset(200f, 310f), shownGroupId = null))
        assertEquals(LibraryDropTarget.IntoGroup("sketching"), target(b, Offset(50f, 90f), shownGroupId = null))
    }

    @Test
    fun emptyOrScrolledAwayRowsAppend() {
        val b = LibraryDragItem.Brush("b", "mine", "B")
        assertEquals(LibraryDropTarget.BrushPosition("inking", 3, null, true), target(b, Offset(200f, 200f), rows = emptyMap()))
    }

    @Test
    fun groupMovesAmongTheGroups() {
        val mine = LibraryDragItem.Group("mine", "Mine")
        assertEquals(LibraryDropTarget.GroupPosition(0, "inking", false), target(mine, Offset(50f, 45f)))
        assertEquals(LibraryDropTarget.GroupPosition(1, "inking", true), target(mine, Offset(50f, 75f)))
        assertNull(target(mine, Offset(200f, 75f)))
    }

    @Test
    fun groupTabsOrderLeftToRight() {
        val tabs = groupIds.withIndex().associate { (index, id) -> id to Rect(index * 80f, 0f, 76f + index * 80f, 40f) }
        val below = Rect(0f, 48f, 400f, 440f)

        fun tabTarget(
            item: LibraryDragItem,
            pointer: Offset,
        ) = LibraryDrop.target(item, pointer, tabs, groupIds, emptyMap(), below, "inking", brushIds, groupsInRow = true)
        val inking = LibraryDragItem.Group("inking", "Inking")
        assertEquals(LibraryDropTarget.GroupPosition(2, "mine", true), tabTarget(inking, Offset(220f, 20f)))
        assertEquals(LibraryDropTarget.GroupPosition(0, "sketching", false), tabTarget(inking, Offset(85f, 20f)))
        assertNull(tabTarget(inking, Offset(85f, 100f)))
        assertEquals(LibraryDropTarget.IntoGroup("mine"), tabTarget(LibraryDragItem.Brush("b", "inking", "B"), Offset(200f, 20f)))
    }
}
