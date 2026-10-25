package com.mobyle.nanquim.diagnostics

import kotlinx.serialization.Serializable

data class StylusSample(
    val toolTypeName: String,
    val xPx: Float,
    val yPx: Float,
    val pressure: Float,
    val tiltRadians: Float,
    val orientationRadians: Float,
    val distance: Float,
    val buttonState: Int,
    val eventTimeNanos: Long,
    val isHover: Boolean,
)

@Serializable
data class StylusObservation(
    val samplesRecorded: Int,
    val toolTypesSeen: List<String>,
    val pressureVaries: Boolean,
    val tiltReported: Boolean,
    val orientationVaries: Boolean,
    val hoverReported: Boolean,
    val distanceReported: Boolean,
    val averageContactSampleRateHz: Double?,
)

class StylusObservationRecorder {
    private var samplesRecorded = 0
    private val toolTypesSeen = sortedSetOf<String>()
    private var firstPressure: Float? = null
    private var pressureVaries = false
    private var tiltReported = false
    private var firstOrientation: Float? = null
    private var orientationVaries = false
    private var hoverReported = false
    private var distanceReported = false

    private var contactSampleCount = 0
    private var contactFirstTimeNanos = 0L
    private var contactLastTimeNanos = 0L
    private var totalContactIntervals = 0L
    private var totalContactDurationNanos = 0L

    @Synchronized
    fun record(sample: StylusSample) {
        samplesRecorded++
        toolTypesSeen += sample.toolTypeName
        if (sample.tiltRadians != 0f) tiltReported = true
        if (sample.distance != 0f) distanceReported = true

        val orientationSeenFirst = firstOrientation
        if (orientationSeenFirst == null) {
            firstOrientation = sample.orientationRadians
        } else if (orientationSeenFirst != sample.orientationRadians) {
            orientationVaries = true
        }

        if (sample.isHover) {
            hoverReported = true
            return
        }

        val pressureSeenFirst = firstPressure
        if (pressureSeenFirst == null) {
            firstPressure = sample.pressure
        } else if (pressureSeenFirst != sample.pressure) {
            pressureVaries = true
        }

        if (contactSampleCount == 0) contactFirstTimeNanos = sample.eventTimeNanos
        contactLastTimeNanos = sample.eventTimeNanos
        contactSampleCount++
    }

    @Synchronized
    fun endContact() {
        if (contactSampleCount >= 2 && contactLastTimeNanos > contactFirstTimeNanos) {
            totalContactIntervals += contactSampleCount - 1
            totalContactDurationNanos += contactLastTimeNanos - contactFirstTimeNanos
        }
        contactSampleCount = 0
    }

    @Synchronized
    fun snapshot(): StylusObservation =
        StylusObservation(
            samplesRecorded = samplesRecorded,
            toolTypesSeen = toolTypesSeen.toList(),
            pressureVaries = pressureVaries,
            tiltReported = tiltReported,
            orientationVaries = orientationVaries,
            hoverReported = hoverReported,
            distanceReported = distanceReported,
            averageContactSampleRateHz =
                if (totalContactDurationNanos > 0) totalContactIntervals * NANOS_PER_SECOND / totalContactDurationNanos else null,
        )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
