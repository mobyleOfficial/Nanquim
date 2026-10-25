package com.mobyle.nanquim.canvas.transform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.abs

class TransformGeometryTest {
    private fun assertNear(
        expected: Point,
        actual: Point,
        tolerance: Double = 1e-6,
    ) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
    }

    @Test
    fun `homography maps the four corners`() {
        val source = listOf(Point(0.0, 0.0), Point(100.0, 0.0), Point(100.0, 50.0), Point(0.0, 50.0))
        val target = listOf(Point(10.0, 20.0), Point(150.0, 5.0), Point(130.0, 90.0), Point(20.0, 70.0))
        val h = TransformGeometry.homography(source, target)!!
        source.zip(target).forEach { (from, to) -> assertNear(to, TransformGeometry.project(h, from).first) }
    }

    @Test
    fun `quad mesh covers the margin and keeps weights positive`() {
        val source = listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0), Point(0.0, 10.0))
        val mesh = TransformGeometry.quadMesh(source, source)!!
        assertEquals(6 * 5, mesh.size)
        for (vertex in 0 until 6) {
            assertEquals(mesh[vertex * 5 + 3], mesh[vertex * 5], 1e-4f)
            assertEquals(1f, mesh[vertex * 5 + 2], 1e-6f)
        }
        assertEquals(-TransformGeometry.MARGIN_PX.toFloat(), mesh.filterIndexed { index, _ -> index % 5 == 3 }.min(), 1e-4f)
    }

    @Test
    fun `warp surface passes through the grid`() {
        val quad = listOf(Point(0.0, 0.0), Point(30.0, 0.0), Point(30.0, 30.0), Point(0.0, 30.0))
        val grid = TransformGeometry.warpGrid(quad, quad, 4).toMutableList()
        grid[5] = Point(grid[5].x + 4, grid[5].y - 3)
        assertNear(grid[5], TransformGeometry.surface(grid, 4, 1 / 3.0, 1 / 3.0))
        assertNear(Point(15.0, 0.0), TransformGeometry.surface(TransformGeometry.warpGrid(quad, quad, 4), 4, 0.5, 0.0))
        val mesh = TransformGeometry.warpMesh(quad, grid, 4)
        assertEquals(36 * 36 * 6 * 5, mesh.size)
    }
}

class TransformEditorTest {
    private val lifted = LiftedContent(100, 100, 200, 100, Point(200.0, 150.0), 0.0)

    private fun editor() = TransformEditor(1000.0, 800.0).apply { initialize(lifted) }

    @Test
    fun `moving snaps to the canvas center when slow`() {
        val editor = editor()
        assertTrue(editor.down(Point(200.0, 150.0), 1.0))
        editor.move(Point(498.0, 401.0), 1.0, velocity = 10.0)
        editor.up()
        val center = TransformGeometry.center(editor.state!!.targetQuad)
        assertEquals(500.0, center.x, 1e-9)
        assertEquals(400.0, center.y, 1e-9)
        assertTrue(editor.undo())
        assertEquals(200.0, TransformGeometry.center(editor.state!!.targetQuad).x, 1e-9)
        assertTrue(editor.redo())
    }

    @Test
    fun `fast moves do not snap`() {
        val editor = editor()
        editor.down(Point(200.0, 150.0), 1.0)
        editor.move(Point(498.0, 401.0), 1.0, velocity = 5000.0)
        assertEquals(498.0, TransformGeometry.center(editor.state!!.targetQuad).x, 1e-9)
    }

    @Test
    fun `corners scale freeform and uniform`() {
        val editor = editor()
        editor.magnetics = false
        assertTrue(editor.down(Point(300.0, 200.0), 1.0))
        editor.move(Point(400.0, 220.0), 1.0, 0.0)
        editor.up()
        var quad = editor.state!!.targetQuad
        assertEquals(Point(100.0, 100.0), quad[0])
        assertEquals(400.0, quad[2].x, 1e-9)
        assertEquals(220.0, quad[2].y, 1e-9)
        editor.setMode(TransformMode.Uniform)
        editor.reset()
        editor.down(Point(300.0, 200.0), 1.0)
        editor.move(Point(500.0, 220.0), 1.0, 0.0)
        quad = editor.state!!.targetQuad
        assertEquals(2.0, (quad[2].x - quad[0].x) / (quad[2].y - quad[0].y), 1e-9)
    }

    @Test
    fun `rotation snaps to 45 degrees and flips mirror`() {
        val editor = editor()
        val handle = editor.handles(1.0).filterIsInstance<TransformHandle.Rotation>().single()
        assertTrue(editor.down(handle.point, 1.0))
        val center = Point(200.0, 150.0)
        val target = TransformGeometry.rotate(handle.point, center, PI / 4 + 0.02)
        editor.move(target, 1.0, 0.0)
        editor.up()
        val quad = editor.state!!.targetQuad
        val angle = kotlin.math.atan2(quad[1].y - quad[0].y, quad[1].x - quad[0].x)
        assertEquals(PI / 4, angle, 1e-9)
        editor.flipHorizontal()
        val flipped = editor.state!!.targetQuad
        assertEquals(quad[1], flipped[0])
    }

    @Test
    fun `distort, warp and back`() {
        val editor = editor()
        editor.setMode(TransformMode.Distort)
        editor.down(Point(100.0, 100.0), 1.0)
        editor.move(Point(80.0, 60.0), 1.0, 1e6)
        editor.up()
        assertEquals(Point(80.0, 60.0), editor.state!!.targetQuad[0])
        editor.setMode(TransformMode.Warp)
        assertNotNull(editor.state!!.warpGrid)
        assertEquals(16, editor.state!!.warpGrid!!.size)
        editor.setWarpSize(6)
        assertEquals(36, editor.state!!.warpGrid!!.size)
        assertNotNull(editor.mesh())
        editor.setMode(TransformMode.Freeform)
        assertFalse(editor.state!!.warpGrid != null)
        val quad = editor.state!!.targetQuad
        assertTrue(abs((quad[1].x - quad[0].x) - (quad[2].x - quad[3].x)) < 1e-6)
    }

    @Test
    fun `numeric input and fit to canvas`() {
        val editor = editor()
        editor.setNumeric(500.0, 400.0, 300.0, 150.0, 90.0)
        val values = editor.numeric()!!
        assertEquals(500.0, values[0], 1e-6)
        assertEquals(300.0, values[2], 1e-6)
        assertEquals(90.0, values[4], 1e-6)
        editor.fitToCanvas()
        val fitted = editor.numeric()!!
        assertEquals(1000.0, fitted[2], 1e-6)
    }
}
