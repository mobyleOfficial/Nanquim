package com.mobyle.nanquim.canvas.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI

class CameraStateTest {
    private fun assertPointEquals(
        expected: Point,
        actual: Point,
        tolerance: Double = 1e-6,
    ) {
        assertEquals(expected.x, actual.x, tolerance, "x")
        assertEquals(expected.y, actual.y, tolerance, "y")
    }

    @Test
    fun `center of the canvas view maps to the view center`() {
        val camera = CameraState(centerX = 500.0, centerY = 300.0, zoom = 2.0, rotationRadians = 0.7)

        assertPointEquals(Point(400.0, 250.0), camera.canvasToScreen(800.0, 500.0).apply(Point(500.0, 300.0)))
    }

    @Test
    fun `zoom scales distances from the center`() {
        val camera = CameraState(centerX = 0.0, centerY = 0.0, zoom = 3.0)

        assertPointEquals(Point(30.0 + 50, 50.0), camera.canvasToScreen(100.0, 100.0).apply(Point(10.0, 0.0)))
    }

    @Test
    fun `flip mirrors left and right on screen even when rotated`() {
        val rotated = CameraState(centerX = 0.0, centerY = 0.0, zoom = 1.0, rotationRadians = PI / 2)
        val flipped = rotated.copy(flippedHorizontally = true)
        val canvasPoint = Point(10.0, 0.0)

        val normal = rotated.canvasToScreen(0.0, 0.0).apply(canvasPoint)
        val mirrored = flipped.canvasToScreen(0.0, 0.0).apply(canvasPoint)

        assertPointEquals(Point(-normal.x, normal.y), mirrored)
    }

    @Test
    fun `screenToCanvas inverts canvasToScreen`() {
        val camera = CameraState(centerX = 120.0, centerY = -40.0, zoom = 0.37, rotationRadians = -1.1, flippedHorizontally = true)
        val canvasPoint = Point(33.0, 77.0)
        val screenPoint = camera.canvasToScreen(1600.0, 2560.0).apply(canvasPoint)

        assertPointEquals(canvasPoint, camera.screenToCanvas(screenPoint, 1600.0, 2560.0))
    }

    @Test
    fun `fit shows the whole canvas with a margin`() {
        val camera = CameraState.fit(canvasWidth = 4096.0, canvasHeight = 4096.0, viewWidth = 1600.0, viewHeight = 2560.0)

        assertEquals(1600.0 / 4096.0 * (1 - CameraState.FIT_MARGIN), camera.zoom, 1e-9)
        assertEquals(2048.0, camera.centerX)
        assertEquals(0.0, camera.rotationRadians)
    }

    @Test
    fun `fit clamps the zoom to the allowed range`() {
        assertEquals(CameraState.MAX_ZOOM, CameraState.fit(4.0, 4.0, 2560.0, 1600.0).zoom)
    }

    @Test
    fun `rotation snaps to quarter turns within five degrees`() {
        val degree = PI / 180

        assertEquals(PI / 2, snapRotation(PI / 2 + 4 * degree), 1e-12)
        assertEquals(-PI, snapRotation(-PI - 4.9 * degree), 1e-12)
        assertEquals(0.0, snapRotation(-3 * degree), 1e-12)
        assertEquals(PI / 2 + 6 * degree, snapRotation(PI / 2 + 6 * degree), 1e-12)
    }
}
