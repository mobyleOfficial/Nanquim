package com.mobyle.nanquim.canvas.camera

import com.mobyle.nanquim.engine.AffineTransform
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

data class CameraState(
    val centerX: Double,
    val centerY: Double,
    val zoom: Double,
    val rotationRadians: Double = 0.0,
    val flippedHorizontally: Boolean = false,
) {
    fun canvasToScreen(
        viewWidth: Double,
        viewHeight: Double,
    ): AffineTransform =
        AffineTransform
            .translation(-centerX, -centerY)
            .then(linearPart())
            .then(AffineTransform.translation(viewWidth / 2, viewHeight / 2))

    fun linearPart(): AffineTransform =
        AffineTransform
            .scale(zoom)
            .then(AffineTransform.rotation(rotationRadians))
            .then(AffineTransform.scale(if (flippedHorizontally) -1.0 else 1.0, 1.0))

    fun withClampedZoom(): CameraState = copy(zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))

    companion object {
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 64.0

        const val FIT_MARGIN = 0.06

        fun fit(
            canvasWidth: Double,
            canvasHeight: Double,
            viewWidth: Double,
            viewHeight: Double,
            flippedHorizontally: Boolean = false,
        ): CameraState =
            CameraState(
                centerX = canvasWidth / 2,
                centerY = canvasHeight / 2,
                zoom = (min(viewWidth / canvasWidth, viewHeight / canvasHeight) * (1 - FIT_MARGIN)),
                flippedHorizontally = flippedHorizontally,
            ).withClampedZoom()
    }
}

fun CameraState.screenToCanvas(
    screenPoint: Point,
    viewWidth: Double,
    viewHeight: Double,
): Point = checkNotNull(canvasToScreen(viewWidth, viewHeight).inverted()) { "Camera transform is singular" }.apply(screenPoint)

fun snapRotation(
    rotationRadians: Double,
    thresholdRadians: Double = SNAP_THRESHOLD_RADIANS,
): Double {
    val quarterTurn = PI / 2
    val nearest = (rotationRadians / quarterTurn).roundToLong() * quarterTurn
    return if (abs(rotationRadians - nearest) <= thresholdRadians) nearest else rotationRadians
}

const val SNAP_THRESHOLD_RADIANS = 5.0 * PI / 180.0
