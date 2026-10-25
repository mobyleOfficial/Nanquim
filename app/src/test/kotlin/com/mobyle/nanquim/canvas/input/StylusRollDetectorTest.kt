package com.mobyle.nanquim.canvas.input

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StylusRollDetectorTest {
    @Test
    fun `orientation following the tilt direction is not a roll`() {
        val detector = StylusRollDetector()
        for (step in 0 until 20) assertFalse(detector.observe(0.6f, step * 0.3f))
    }

    @Test
    fun `orientation turning while the pen is upright is a roll`() {
        val detector = StylusRollDetector()
        assertFalse(detector.observe(0f, 0f))
        assertFalse(detector.observe(0f, 0.05f))
        assertTrue(detector.observe(0f, 0.4f))
    }

    @Test
    fun `a new contact starts over and turns wrap around`() {
        val detector = StylusRollDetector()
        detector.observe(0f, 0f)
        detector.reset()
        assertFalse(detector.observe(0f, 1f))
        val wrapping = StylusRollDetector()
        wrapping.observe(0f, 3.1f)
        assertFalse(wrapping.observe(0f, -3.1f))
    }
}
