package com.mobyle.nanquim.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StylusObservationRecorderTest {
    private fun sample(
        timeMilliseconds: Long,
        pressure: Float = 0.5f,
        tilt: Float = 0f,
        orientation: Float = 0f,
        distance: Float = 0f,
        isHover: Boolean = false,
        toolTypeName: String = "stylus",
    ) = StylusSample(
        toolTypeName = toolTypeName,
        xPx = 0f,
        yPx = 0f,
        pressure = pressure,
        tiltRadians = tilt,
        orientationRadians = orientation,
        distance = distance,
        buttonState = 0,
        eventTimeNanos = timeMilliseconds * 1_000_000,
        isHover = isHover,
    )

    @Test
    fun `starts with nothing observed`() {
        val observation = StylusObservationRecorder().snapshot()

        assertEquals(0, observation.samplesRecorded)
        assertFalse(observation.pressureVaries)
        assertNull(observation.averageContactSampleRateHz)
    }

    @Test
    fun `averages the sampling rate over contacts`() {
        val recorder = StylusObservationRecorder()
        (0L..240L).forEach { time -> recorder.record(sample(time)) }
        recorder.endContact()
        listOf(1000L, 1004L, 1008L, 1012L).forEach { time -> recorder.record(sample(time)) }
        recorder.endContact()

        assertEquals(243 * 1000.0 / 252, recorder.snapshot().averageContactSampleRateHz!!, 1e-9)
    }

    @Test
    fun `ignores single-sample contacts and hover samples for the rate`() {
        val recorder = StylusObservationRecorder()
        recorder.record(sample(0))
        recorder.endContact()
        recorder.record(sample(10, isHover = true))
        recorder.record(sample(20, isHover = true))

        val observation = recorder.snapshot()
        assertNull(observation.averageContactSampleRateHz)
        assertTrue(observation.hoverReported)
        assertEquals(3, observation.samplesRecorded)
    }

    @Test
    fun `detects which axes the stylus reports`() {
        val recorder = StylusObservationRecorder()
        recorder.record(sample(0, pressure = 0.2f, orientation = 0.1f))
        recorder.record(sample(1, pressure = 0.7f, tilt = 0.4f, orientation = 0.3f))
        recorder.record(sample(2, distance = 3f, isHover = true, toolTypeName = "eraser"))

        val observation = recorder.snapshot()
        assertTrue(observation.pressureVaries)
        assertTrue(observation.tiltReported)
        assertTrue(observation.orientationVaries)
        assertTrue(observation.distanceReported)
        assertEquals(listOf("eraser", "stylus"), observation.toolTypesSeen)
    }

    @Test
    fun `constant pressure is not reported as varying`() {
        val recorder = StylusObservationRecorder()
        (0L..10L).forEach { time -> recorder.record(sample(time, pressure = 1f)) }

        assertFalse(recorder.snapshot().pressureVaries)
    }
}
