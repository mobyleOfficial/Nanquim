package com.mobyle.nanquim.canvas.selection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.abs

class SelectionGeometryTest {
    private fun area(points: List<Point>): Double =
        abs(points.indices.sumOf { index -> val a = points[index]; val b = points[(index + 1) % points.size]; a.x * b.y - b.x * a.y }) / 2

    @Test
    fun `ellipse stays within the tolerance`() {
        val points = SelectionGeometry.ellipse(0.0, 0.0, 400.0, 200.0)
        assertEquals(PI * 200 * 100, area(points), PI * 200 * 100 * 0.001)
        for (point in points) {
            val normalized = ((point.x - 200) / 200).let { it * it } + ((point.y - 100) / 100).let { it * it }
            assertEquals(1.0, normalized, 1e-9)
        }
    }

    @Test
    fun `smooth nodes give a curve through them, corners straight edges`() {
        val square = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 100.0), Point(0.0, 100.0))
        val polygon = SelectionGeometry.closedPath(square.map { PathNode(it, corner = true) })
        assertEquals(square, polygon)
        val smooth = SelectionGeometry.closedPath(square.map { PathNode(it) })
        assertTrue(smooth.size > 40)
        for (node in square) assertTrue(smooth.contains(node))
        assertTrue(area(smooth) > 100.0 * 100.0)
        assertTrue(SelectionGeometry.closedPath(square.take(2).map { PathNode(it) }).isEmpty())
    }
}

class SelectionEditorTest {
    private val zoom = 1.0

    @Test
    fun `rectangle drag, corner adjust and replacement`() {
        val editor = SelectionEditor().apply { mode = SelectionMode.Rectangle }
        assertEquals(SelectionEditor.Result.None, editor.down(Point(10.0, 10.0), zoom))
        editor.move(Point(110.0, 60.0), zoom, constrain = false)
        editor.up(Point(110.0, 60.0), zoom)
        val box = editor.pending as PendingSelection.Box
        assertEquals(4, box.polygon()!!.size)
        editor.down(Point(108.0, 62.0), zoom)
        editor.move(Point(150.0, 90.0), zoom, constrain = false)
        editor.up(Point(150.0, 90.0), zoom)
        assertEquals(150.0, (editor.pending as PendingSelection.Box).right)
        assertEquals(SelectionEditor.Result.ApplyPrevious, editor.down(Point(300.0, 300.0), zoom))
        assertEquals(150.0, (editor.replaced as PendingSelection.Box).right)
        editor.move(Point(340.0, 310.0), zoom, constrain = true)
        val square = editor.pending as PendingSelection.Box
        assertEquals(square.right - square.left, square.bottom - square.top)
    }

    @Test
    fun `freehand drags, taps add corners, tapping the first node finishes`() {
        val editor = SelectionEditor()
        editor.down(Point(0.0, 0.0), zoom)
        for (step in 1..20) editor.move(Point(step * 5.0, 0.0), zoom, constrain = false)
        editor.up(Point(100.0, 0.0), zoom)
        val drawn = (editor.pending as PendingSelection.Path).nodes
        assertTrue(drawn.size in 10..14)
        assertTrue(drawn.none { it.corner })
        editor.down(Point(50.0, 80.0), zoom)
        assertEquals(SelectionEditor.Result.None, editor.up(Point(50.0, 80.0), zoom))
        assertTrue((editor.pending as PendingSelection.Path).nodes.last().corner)
        editor.down(Point(51.0, 79.0), zoom)
        editor.move(Point(50.0, 120.0), zoom, constrain = false)
        editor.up(Point(50.0, 120.0), zoom)
        assertEquals(120.0, (editor.pending as PendingSelection.Path).nodes.last().point.y)
        editor.down(Point(1.0, 1.0), zoom)
        assertEquals(SelectionEditor.Result.Apply, editor.up(Point(1.0, 1.0), zoom))
        assertNotNull(editor.takePending()?.polygon())
        assertNull(editor.pending)
    }

    @Test
    fun `switching modes drops the pending shape`() {
        val editor = SelectionEditor().apply { mode = SelectionMode.Ellipse }
        editor.down(Point(0.0, 0.0), zoom)
        editor.move(Point(50.0, 50.0), zoom, constrain = false)
        editor.up(Point(50.0, 50.0), zoom)
        assertNotNull(editor.pending)
        editor.mode = SelectionMode.Freehand
        assertNull(editor.pending)
    }
}
