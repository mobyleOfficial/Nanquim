package com.mobyle.nanquim.canvas.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.mobyle.nanquim.engine.AffineTransform
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI

class TwoFingerGestureTest {
    private val viewWidth = 1600.0
    private val viewHeight = 2560.0

    private fun assertPointEquals(
        expected: Point,
        actual: Point,
    ) {
        assertEquals(expected.x, actual.x, 1e-6, "x")
        assertEquals(expected.y, actual.y, 1e-6, "y")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `canvas points under both fingers stay under them`(flipped: Boolean) {
        val start = CameraState(centerX = 2048.0, centerY = 2048.0, zoom = 0.5, rotationRadians = 0.3, flippedHorizontally = flipped)
        val firstStart = Point(500.0, 900.0)
        val secondStart = Point(1100.0, 1400.0)
        val gesture = TwoFingerGesture(start, firstStart, secondStart, viewWidth, viewHeight, rotationEnabled = true, startTimeMillis = 0)
        val fingerMotion =
            AffineTransform
                .rotation(40 * PI / 180)
                .then(AffineTransform.scale(1.7))
                .then(AffineTransform.translation(-150.0, 220.0))
        val firstEnd = fingerMotion.apply(firstStart)
        val secondEnd = fingerMotion.apply(secondStart)

        val end = gesture.update(firstEnd, secondEnd)

        val firstCanvasPoint = start.screenToCanvas(firstStart, viewWidth, viewHeight)
        val secondCanvasPoint = start.screenToCanvas(secondStart, viewWidth, viewHeight)
        assertPointEquals(firstEnd, end.canvasToScreen(viewWidth, viewHeight).apply(firstCanvasPoint))
        assertPointEquals(secondEnd, end.canvasToScreen(viewWidth, viewHeight).apply(secondCanvasPoint))
        assertEquals(0.5 * 1.7, end.zoom, 1e-9)
        assertEquals(1.7, gesture.scaleRatio, 1e-9)
    }

    @Test
    fun `rotation can be disabled`() {
        val start = CameraState(centerX = 0.0, centerY = 0.0, zoom = 1.0)
        val gesture =
            TwoFingerGesture(start, Point(0.0, 0.0), Point(100.0, 0.0), viewWidth, viewHeight, rotationEnabled = false, startTimeMillis = 0)

        assertEquals(0.0, gesture.update(Point(0.0, 0.0), Point(0.0, 100.0)).rotationRadians)
    }

    @Test
    fun `zoom is clamped`() {
        val start = CameraState(centerX = 0.0, centerY = 0.0, zoom = 60.0)
        val gesture =
            TwoFingerGesture(start, Point(0.0, 0.0), Point(10.0, 0.0), viewWidth, viewHeight, rotationEnabled = true, startTimeMillis = 0)

        assertEquals(CameraState.MAX_ZOOM, gesture.update(Point(0.0, 0.0), Point(1000.0, 0.0)).zoom)
    }

    @Test
    fun `degenerate start does not divide by zero`() {
        val start = CameraState(centerX = 0.0, centerY = 0.0, zoom = 2.0)
        val gesture =
            TwoFingerGesture(start, Point(5.0, 5.0), Point(5.0, 5.0), viewWidth, viewHeight, rotationEnabled = true, startTimeMillis = 0)

        assertEquals(2.0, gesture.update(Point(5.0, 5.0), Point(50.0, 5.0)).zoom)
    }

    @Test
    fun `quick pinch needs speed and a real pinch`() {
        assertTrue(isQuickPinch(durationMillis = 180, scaleRatio = 0.6))
        assertFalse(isQuickPinch(durationMillis = 300, scaleRatio = 0.6))
        assertFalse(isQuickPinch(durationMillis = 180, scaleRatio = 0.85))
        assertFalse(isQuickPinch(durationMillis = 180, scaleRatio = 1.4))
    }
}
