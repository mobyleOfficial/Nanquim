package com.mobyle.nanquim.canvas.adjust

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.engine.AdjustmentFilter

class AdjustmentEditorTest {
    private fun editor() = AdjustmentEditor(AdjustmentFilter.HueSaturationBrightness)

    @Test
    fun startsAtTheDefaults() {
        val editor = editor()
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), editor.values)
        assertEquals(1f, editor.amount)
        assertFalse(editor.canUndo)
    }

    @Test
    fun aDragAcrossTheWholeCanvasCoversTheWholeRange() {
        val editor = editor()
        editor.selectMainParameter(1)
        editor.beginDrag()
        editor.dragBy(0.25f)
        assertEquals(0.5f, editor.values[1], 1e-6f)
        editor.dragBy(2f)
        assertEquals(1f, editor.values[1], 1e-6f)
        editor.finishChange()
        assertTrue(editor.canUndo)
        assertNotNull(editor.undo())
        assertEquals(0f, editor.values[1])
        assertNotNull(editor.redo())
        assertEquals(1f, editor.values[1])
    }

    @Test
    fun eachFinishedChangeIsOneStep() {
        val editor = editor()
        editor.preview(0, 0.1f)
        editor.preview(0, 0.2f)
        editor.finishChange()
        editor.set(2, -0.5f)
        editor.reset()
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), editor.values)
        assertNotNull(editor.undo())
        assertArrayEquals(floatArrayOf(0.2f, 0f, -0.5f), editor.values)
        assertNotNull(editor.undo())
        assertArrayEquals(floatArrayOf(0.2f, 0f, 0f), editor.values)
        assertNotNull(editor.undo())
        assertNull(editor.undo())
        editor.set(1, 0.3f)
        assertFalse(editor.canRedo)
    }

    @Test
    fun aCanceledDragLeavesNoStep() {
        val editor = editor()
        editor.beginDrag()
        editor.dragBy(0.1f)
        editor.cancelChange()
        assertEquals(0f, editor.values[0])
        assertFalse(editor.canUndo)
        editor.set(0, 0f)
        assertFalse(editor.canUndo)
    }

    @Test
    fun comparingShowsTheOriginal() {
        val editor = editor()
        editor.comparing = true
        assertEquals(0f, editor.amount)
    }

    @Test
    fun curvesAreUndoStepsAndTableData() {
        val editor = AdjustmentEditor(AdjustmentFilter.Curves)
        assertFalse(editor.hasParameters)
        val raised = listOf(CurvePoint(0f, 0.2f), CurvePoint(1f, 1f))
        editor.previewCurve(CurveChannel.Red, listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f)))
        editor.setCurve(CurveChannel.Red, raised)
        assertEquals(raised, editor.curve(CurveChannel.Red))
        assertEquals(25, editor.tableData().size)
        assertEquals(0.2f, editor.tableData()[7])
        editor.reset()
        assertEquals(CurveEditing.STRAIGHT, editor.curve(CurveChannel.Red))
        assertNotNull(editor.undo())
        assertEquals(raised, editor.curve(CurveChannel.Red))
        assertNotNull(editor.undo())
        assertEquals(CurveEditing.STRAIGHT, editor.curve(CurveChannel.Red))
        editor.beginDrag()
        editor.dragBy(0.5f)
        editor.finishChange()
        assertFalse(editor.canUndo)
    }

    @Test
    fun gradientChoicesAreUndoSteps() {
        val first = GradientLibrary.BUILT_INS[0]
        val second = GradientLibrary.BUILT_INS[1]
        val editor = AdjustmentEditor(AdjustmentFilter.GradientMap, gradient = first)
        assertEquals(1f, editor.values[0])
        assertArrayEquals(first.definition.tableData(), editor.tableData())
        editor.setGradient(second.id, second.definition)
        assertEquals(second.id, editor.state.gradientId)
        editor.set(0, 0.5f)
        editor.reset()
        assertEquals(1f, editor.values[0])
        assertEquals(second.id, editor.state.gradientId)
        assertNotNull(editor.undo())
        assertEquals(0.5f, editor.values[0])
        assertNotNull(editor.undo())
        assertNotNull(editor.undo())
        assertEquals(first.id, editor.state.gradientId)
        assertNull(editor.undo())
    }

    @Test
    fun pencilStrokesAreStepsWithTheirMaskEntry() {
        val editor = AdjustmentEditor(AdjustmentFilter.HueSaturationBrightness, AdjustmentMode.Pencil)
        editor.set(0, 0.5f)
        editor.recordMaskStroke(41)
        editor.recordMaskStroke(42)
        assertEquals(42L, editor.undo()?.maskEntry)
        assertEquals(41L, editor.undo()?.maskEntry)
        assertEquals(0.5f, editor.values[0])
        assertNull(editor.undo()?.maskEntry)
        assertEquals(0f, editor.values[0])
        assertNull(editor.undo())
        assertNull(editor.redo()?.maskEntry)
        assertEquals(41L, editor.redo()?.maskEntry)
        editor.reset(maskClearEntry = 43)
        assertEquals(0f, editor.values[0])
        assertNull(editor.redo())
        val step = editor.undo()
        assertEquals(43L, step?.maskEntry)
        assertEquals(0.5f, editor.values[0])
        val fresh = AdjustmentEditor(AdjustmentFilter.HueSaturationBrightness)
        fresh.reset()
        assertFalse(fresh.canUndo)
    }

    @Test
    fun motionBlurDragSetsLengthAndAngle() {
        val editor = AdjustmentEditor(AdjustmentFilter.MotionBlur)
        editor.beginDrag()
        editor.dragAlong(0.4f, Math.PI / 2)
        assertEquals(0.4f, editor.values[0], 1e-6f)
        assertEquals(0.5f, editor.values[1], 1e-6f)
        editor.dragAlong(1.7f, -Math.PI / 4)
        editor.finishChange()
        assertEquals(1f, editor.values[0])
        assertEquals(-0.25f, editor.values[1], 1e-6f)
        assertNotNull(editor.undo())
        assertEquals(0f, editor.values[0])
        assertTrue(editor.filter.isAngle(1))
        assertFalse(editor.filter.isAngle(0))
    }

    @Test
    fun perspectivePuckStartsCenteredAndMovesAsOneStep() {
        val editor = AdjustmentEditor(AdjustmentFilter.PerspectiveBlur)
        assertEquals(0.5f to 0.5f, editor.puck)
        editor.movePuck(0.2f, 0.3f)
        editor.movePuck(0.25f, 0.9f)
        editor.finishChange()
        assertEquals(0.25f to 0.9f, editor.puck)
        assertNotNull(editor.undo())
        assertEquals(0.5f to 0.5f, editor.puck)
        assertNull(editor.undo())
        assertNull(AdjustmentEditor(AdjustmentFilter.GaussianBlur).puck)
    }

    @Test
    fun everyFilterHasARangePerParameter() {
        for (filter in AdjustmentFilter.entries) {
            val ranges = filter.ranges()
            assertEquals(filter.parameterNames.size, ranges.size, filter.name)
            for (range in ranges) assertTrue(range.default in range.minimum..range.maximum, filter.name)
        }
        for (filter in listOf(AdjustmentFilter.PerspectiveBlur, AdjustmentFilter.ChromaticAberration)) {
            assertTrue(filter.hasPuck)
            assertEquals("x", filter.parameterNames[PerspectiveParameters.PUCK_X])
            assertEquals("y", filter.parameterNames[PerspectiveParameters.PUCK_Y])
            assertEquals("angle", filter.parameterNames[PerspectiveParameters.ANGLE])
        }
        assertEquals(0.5f to 0.5f, AdjustmentEditor(AdjustmentFilter.ChromaticAberration).puck)
    }

    @Test
    fun liquifyStepsInterleaveWithParameterChanges() {
        val editor = AdjustmentEditor(AdjustmentFilter.Liquify)
        assertEquals(1f, editor.values[4])
        editor.recordLiquifyStep()
        editor.set(0, 0.8f)
        editor.recordLiquifyStep()
        assertEquals(true, editor.undo()?.liquify)
        assertEquals(0.8f, editor.values[0])
        assertEquals(false, editor.undo()?.liquify)
        assertEquals(0.3f, editor.values[0])
        assertEquals(true, editor.undo()?.liquify)
        assertNull(editor.undo())
        assertEquals(true, editor.redo()?.liquify)
    }
}
