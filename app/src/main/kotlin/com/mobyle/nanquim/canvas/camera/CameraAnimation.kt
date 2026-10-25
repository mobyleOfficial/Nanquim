package com.mobyle.nanquim.canvas.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong

class CameraAnimation(
    from: CameraState,
    val target: CameraState,
    private val angularFrequency: Double = DEFAULT_ANGULAR_FREQUENCY,
) {
    private val targetLogZoom = ln(target.zoom)

    private val targetRotation: Double =
        target.rotationRadians + (((from.rotationRadians - target.rotationRadians) / FULL_TURN).roundToLong() * FULL_TURN)

    private val centerX = Spring(from.centerX, target.centerX)
    private val centerY = Spring(from.centerY, target.centerY)
    private val logZoom = Spring(ln(from.zoom), targetLogZoom)
    private val rotation = Spring(from.rotationRadians, targetRotation)

    var isFinished: Boolean = false
        private set

    fun step(elapsedSeconds: Double): CameraState {
        if (isFinished) return target
        listOf(centerX, centerY, logZoom, rotation).forEach { it.step(elapsedSeconds, angularFrequency) }
        val settled =
            abs(centerX.offset) < CENTER_TOLERANCE_PX &&
                abs(centerY.offset) < CENTER_TOLERANCE_PX &&
                abs(logZoom.offset) < LOG_ZOOM_TOLERANCE &&
                abs(rotation.offset) < ROTATION_TOLERANCE_RADIANS
        if (settled) {
            isFinished = true
            return target
        }
        return target.copy(
            centerX = centerX.value,
            centerY = centerY.value,
            zoom = exp(logZoom.value),
            rotationRadians = rotation.value,
        )
    }

    private class Spring(
        var value: Double,
        private val target: Double,
    ) {
        private var velocity = 0.0

        val offset: Double get() = value - target

        fun step(
            elapsedSeconds: Double,
            angularFrequency: Double,
        ) {
            val initialOffset = value - target
            val coefficient = velocity + angularFrequency * initialOffset
            val decay = exp(-angularFrequency * elapsedSeconds)
            value = target + (initialOffset + coefficient * elapsedSeconds) * decay
            velocity = (velocity - angularFrequency * coefficient * elapsedSeconds) * decay
        }
    }

    companion object {
        const val DEFAULT_ANGULAR_FREQUENCY = 32.0
        private const val FULL_TURN = 2 * PI
        private const val CENTER_TOLERANCE_PX = 0.05
        private const val LOG_ZOOM_TOLERANCE = 1e-4
        private const val ROTATION_TOLERANCE_RADIANS = 1e-4
    }
}
