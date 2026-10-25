package com.mobyle.nanquim.document.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.addRasterLayer
import kotlin.random.Random

class UndoHistoryTest {
    private fun pixels(id: Long) = HistoryEntry(HistoryLabel.BrushStroke, pixelEntryIds = listOf(id))

    @Test
    fun undoAndRedoMoveEntriesBetweenStacks() {
        val history = UndoHistory()
        val first = pixels(1)
        val second = pixels(2)
        history.record(first)
        history.record(second)
        assertSame(second, history.undo())
        assertSame(first, history.undo())
        assertNull(history.undo())
        assertSame(first, history.redo())
        assertEquals(1, history.undoCount)
        assertEquals(1, history.redoCount)
    }

    @Test
    fun aNewEntryDiscardsTheRedoBranch() {
        val dropped = mutableListOf<HistoryEntry>()
        val history = UndoHistory(onDropped = { dropped += it })
        history.record(pixels(1))
        history.record(pixels(2))
        history.undo()
        history.record(pixels(3))
        assertFalse(history.canRedo)
        assertEquals(listOf(2L), dropped.flatMap { it.pixelEntryIds })
    }

    @Test
    fun oldestEntriesGoBeyondTheStepAndSizeLimits() {
        val dropped = mutableListOf<Long>()
        val history = UndoHistory(maximumSteps = 10, maximumPixelBytes = 1000, onDropped = { entries -> entries.forEach { dropped += it.pixelEntryIds } })
        for (id in 1L..12L) history.record(pixels(id))
        assertEquals(listOf(1L, 2L), dropped)
        assertEquals(10, history.undoCount)
        for (id in 3L..12L) history.setPixelBytes(id, 300)
        assertEquals(3, history.undoCount)
        assertTrue(history.totalPixelBytes <= 1000)
        history.setPixelBytes(99, 5)
        assertEquals(900, history.totalPixelBytes)
    }

    @Test
    fun randomSequencesUndoAndRedoToTheEndStates() {
        val random = Random(20261014)
        repeat(50) {
            val history = UndoHistory()
            val initial = DocumentState(64, 64)
            var state = initial
            var nextPixelId = 1L
            repeat(random.nextInt(1, 40)) {
                if (random.nextBoolean()) {
                    val after = state.addRasterLayer("L").first
                    history.record(HistoryEntry(HistoryLabel.NewLayer, state, after))
                    state = after
                } else {
                    history.record(pixels(nextPixelId++))
                }
                if (random.nextInt(4) == 0) {
                    history.undo()?.before?.let { state = it }
                }
            }
            while (true) {
                val entry = history.redo() ?: break
                entry.after?.let { state = it }
            }
            val final = state
            while (true) {
                val entry = history.undo() ?: break
                entry.before?.let { state = it }
            }
            assertEquals(initial.layers, state.layers)
            while (true) {
                val entry = history.redo() ?: break
                entry.after?.let { state = it }
            }
            assertEquals(final.layers, state.layers)
        }
    }
}
