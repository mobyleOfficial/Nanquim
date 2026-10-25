package com.mobyle.nanquim.canvas.camera

import com.mobyle.nanquim.engine.Point
import kotlin.math.atan2
import kotlin.math.hypot

class TwoFingerGesture(
    val startCamera: CameraState,
    firstPointer: Point,
    secondPointer: Point,
    private val viewWidth: Double,
    private val viewHeight: Double,
    private val rotationEnabled: Boolean,
    val startTimeMillis: Long,
) {
    private val anchorCanvasPoint = startCamera.screenToCanvas(centroid(firstPointer, secondPointer), viewWidth, viewHeight)
    private val startDistance = distance(firstPointer, secondPointer)
    private val startAngle = angle(firstPointer, secondPointer)

    var scaleRatio: Double = 1.0
        private set

    fun update(
        firstPointer: Point,
        secondPointer: Point,
    ): CameraState {
        scaleRatio = if (startDistance < MIN_POINTER_DISTANCE_PX) 1.0 else distance(firstPointer, secondPointer) / startDistance
        val zoom = (startCamera.zoom * scaleRatio).coerceIn(CameraState.MIN_ZOOM, CameraState.MAX_ZOOM)
        val twist = if (rotationEnabled) angle(firstPointer, secondPointer) - startAngle else 0.0
        val rotation = startCamera.rotationRadians + if (startCamera.flippedHorizontally) -twist else twist
        val withoutCenter = startCamera.copy(zoom = zoom, rotationRadians = rotation)

        val currentCentroid = centroid(firstPointer, secondPointer)
        val inverseLinear = checkNotNull(withoutCenter.linearPart().inverted())
        val offset = inverseLinear.apply(Point(currentCentroid.x - viewWidth / 2, currentCentroid.y - viewHeight / 2))
        return withoutCenter.copy(centerX = anchorCanvasPoint.x - offset.x, centerY = anchorCanvasPoint.y - offset.y)
    }

    private companion object {
        const val MIN_POINTER_DISTANCE_PX = 1.0

        fun centroid(
            first: Point,
            second: Point,
        ) = Point((first.x + second.x) / 2, (first.y + second.y) / 2)

        fun distance(
            first: Point,
            second: Point,
        ) = hypot(second.x - first.x, second.y - first.y)

        fun angle(
            first: Point,
            second: Point,
        ) = atan2(second.y - first.y, second.x - first.x)
    }
}

fun isQuickPinch(
    durationMillis: Long,
    scaleRatio: Double,
): Boolean = durationMillis < QUICK_PINCH_MAX_DURATION_MILLIS && scaleRatio < QUICK_PINCH_MAX_SCALE_RATIO

const val QUICK_PINCH_MAX_DURATION_MILLIS = 250L
const val QUICK_PINCH_MAX_SCALE_RATIO = 0.8
