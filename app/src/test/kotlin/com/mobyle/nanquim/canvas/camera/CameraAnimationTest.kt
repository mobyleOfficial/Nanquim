package com.mobyle.nanquim.canvas.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs

class CameraAnimationTest {
    private val frameSeconds = 1.0 / 60

    @Test
    fun `reaches the target without overshoot`() {
        val from = CameraState(centerX = 0.0, centerY = 0.0, zoom = 1.0)
        val target = CameraState(centerX = 1000.0, centerY = -500.0, zoom = 0.25)
        val animation = CameraAnimation(from, target)

        var previousX = from.centerX
        var current = from
        var frames = 0
        while (!animation.isFinished && frames < 600) {
            current = animation.step(frameSeconds)
            assertTrue(current.centerX >= previousX - 1e-9, "center moves monotonically (critically damped)")
            assertTrue(current.centerX <= target.centerX + 1e-9, "no overshoot")
            previousX = current.centerX
            frames++
        }

        assertTrue(animation.isFinished)
        assertEquals(target, current)
    }

    @Test
    fun `is mostly there after 200 milliseconds`() {
        val from = CameraState(centerX = 0.0, centerY = 0.0, zoom = 1.0)
        val target = from.copy(centerX = 1000.0)
        val animation = CameraAnimation(from, target)

        var current = from
        repeat(12) { current = animation.step(frameSeconds) }

        assertTrue(abs(current.centerX - target.centerX) < 20.0, "remaining offset ${target.centerX - current.centerX}")
        assertFalse(animation.isFinished)
    }

    @Test
    fun `rotation takes the shortest way round`() {
        val from = CameraState(centerX = 0.0, centerY = 0.0, zoom = 1.0, rotationRadians = 350 * PI / 180)
        val target = from.copy(rotationRadians = 10 * PI / 180)
        val animation = CameraAnimation(from, target)

        val firstStep = animation.step(frameSeconds)

        assertTrue(firstStep.rotationRadians > from.rotationRadians, "turns forward past 360°, not back 340°")
    }

    @Test
    fun `zoom interpolates geometrically`() {
        val from = CameraState(centerX = 0.0, centerY = 0.0, zoom = 0.1)
        val target = from.copy(zoom = 10.0)
        val animation = CameraAnimation(from, target)

        var current = from
        repeat(400) { if (!animation.isFinished) current = animation.step(frameSeconds) }

        assertEquals(10.0, current.zoom, 1e-9)
    }
}
