package com.mobyle.nanquim.canvas

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.mobyle.nanquim.canvas.camera.CameraAnimation
import com.mobyle.nanquim.canvas.camera.CameraState
import com.mobyle.nanquim.canvas.camera.TwoFingerGesture
import com.mobyle.nanquim.canvas.camera.isQuickPinch
import com.mobyle.nanquim.canvas.camera.snapRotation
import com.mobyle.nanquim.engine.AffineTransform
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.Point
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

class CanvasViewportController(
    private val engine: Engine,
) {
    private var viewWidth = 0.0
    private var viewHeight = 0.0
    private var canvasWidth = 0.0
    private var canvasHeight = 0.0
    private var gesture: TwoFingerGesture? = null
    private var animation: CameraAnimation? = null
    private var lastFrameTimeNanos = 0L

    private var cameraBeforeQuickFit: CameraState? = null

    var camera: CameraState? = null
        private set

    var rotationGestureEnabled: Boolean = true

    private val frameCallback =
        Choreographer.FrameCallback { frameTimeNanos -> onAnimationFrame(frameTimeNanos) }

    fun onViewSizeChanged(
        widthPx: Int,
        heightPx: Int,
    ) {
        val firstSize = viewWidth == 0.0
        viewWidth = widthPx.toDouble()
        viewHeight = heightPx.toDouble()
        if (firstSize || camera == null) fitIfPossible(animate = false) else publish()
    }

    fun onCanvasChanged(
        widthPx: Int,
        heightPx: Int,
    ) {
        canvasWidth = widthPx.toDouble()
        canvasHeight = heightPx.toDouble()
        cameraBeforeQuickFit = null
        fitIfPossible(animate = false)
    }

    fun fit(animate: Boolean = true) = fitIfPossible(animate)

    fun showActualPixels(animate: Boolean = true) = camera?.let { setCamera(it.copy(zoom = 1.0), animate) }

    fun zoomTo(
        zoom: Double,
        animate: Boolean = true,
    ) = camera?.let { setCamera(it.copy(zoom = zoom).withClampedZoom(), animate) }

    fun rotateTo(
        radians: Double,
        animate: Boolean = true,
    ) = camera?.let { setCamera(it.copy(rotationRadians = radians), animate) }

    fun toggleFlip() = camera?.let { setCamera(it.copy(flippedHorizontally = !it.flippedHorizontally), animate = false) }

    fun beginTwoFingerGesture(
        first: Point,
        second: Point,
        timeMillis: Long,
    ) {
        val current = camera ?: return
        cancelAnimation()
        gesture = TwoFingerGesture(current, first, second, viewWidth, viewHeight, rotationGestureEnabled, timeMillis)
    }

    fun updateTwoFingerGesture(
        first: Point,
        second: Point,
    ) {
        val active = gesture ?: return
        camera = active.update(first, second)
        publish()
    }

    fun endTwoFingerGesture(timeMillis: Long) {
        val finished = gesture ?: return
        gesture = null
        val current = camera ?: return
        if (isQuickPinch(timeMillis - finished.startTimeMillis, finished.scaleRatio)) {
            val restore = cameraBeforeQuickFit
            if (restore != null) {
                cameraBeforeQuickFit = null
                setCamera(restore, animate = true)
            } else {
                cameraBeforeQuickFit = current
                fitIfPossible(animate = true)
            }
            return
        }
        cameraBeforeQuickFit = null
        val snapped = snapRotation(current.rotationRadians)
        if (snapped != current.rotationRadians) setCamera(current.copy(rotationRadians = snapped), animate = true)
    }

    fun zoomAround(
        point: Point,
        factor: Double,
    ) {
        val current = camera ?: return
        cancelAnimation()

        fun beside(spread: Double) = Point(point.x - spread, point.y) to Point(point.x + spread, point.y)
        val (left, right) = beside(SYNTHETIC_SPREAD_PX)
        val zoom = TwoFingerGesture(current, left, right, viewWidth, viewHeight, rotationEnabled = false, startTimeMillis = 0)
        val (newLeft, newRight) = beside(SYNTHETIC_SPREAD_PX * factor)
        camera = zoom.update(newLeft, newRight)
        publish()
    }

    fun beginPan(
        point: Point,
        timeMillis: Long,
    ) {
        val current = camera ?: return
        cancelAnimation()
        gesture =
            TwoFingerGesture(current, point, besidePan(point), viewWidth, viewHeight, rotationEnabled = false, startTimeMillis = timeMillis)
    }

    fun updatePan(point: Point) = updateTwoFingerGesture(point, besidePan(point))

    private fun besidePan(point: Point) = Point(point.x + SYNTHETIC_SPREAD_PX, point.y)

    fun cancelTwoFingerGesture() {
        val canceled = gesture ?: return
        gesture = null
        if (camera != canceled.startCamera) {
            camera = canceled.startCamera
            publish()
        }
    }

    fun startCameraTour(durationSeconds: Double = CAMERA_TOUR_SECONDS) {
        if (camera == null || canvasWidth <= 0) return
        cancelAnimation()
        gesture = null
        tourStartNanos = 0L
        tourDurationSeconds = durationSeconds
        Choreographer.getInstance().postFrameCallback(tourCallback)
    }

    private var tourStartNanos = 0L
    private var tourDurationSeconds = 0.0
    private val tourCallback = Choreographer.FrameCallback { frameTimeNanos -> onTourFrame(frameTimeNanos) }

    private fun onTourFrame(frameTimeNanos: Long) {
        if (tourStartNanos == 0L) tourStartNanos = frameTimeNanos
        val progress = ((frameTimeNanos - tourStartNanos) / NANOS_PER_SECOND / tourDurationSeconds).coerceIn(0.0, 1.0)
        val angle = progress * 2 * PI
        val zoom = exp(ln(TOUR_MIN_ZOOM) + (ln(TOUR_MAX_ZOOM) - ln(TOUR_MIN_ZOOM)) * (0.5 - 0.5 * cos(angle * 2)))
        camera =
            CameraState(
                centerX = canvasWidth * (0.5 + 0.35 * cos(angle)),
                centerY = canvasHeight * (0.5 + 0.35 * sin(angle)),
                zoom = zoom,
                rotationRadians = angle,
                flippedHorizontally = camera?.flippedHorizontally ?: false,
            )
        publish()
        if (progress < 1.0) Choreographer.getInstance().postFrameCallback(tourCallback) else fitIfPossible(animate = true)
    }

    private fun fitIfPossible(animate: Boolean) {
        if (viewWidth <= 0 || viewHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return
        val flipped = camera?.flippedHorizontally ?: false
        setCamera(CameraState.fit(canvasWidth, canvasHeight, viewWidth, viewHeight, flipped), animate)
    }

    private fun setCamera(
        target: CameraState,
        animate: Boolean,
    ) {
        val current = camera
        cancelAnimation()
        if (!animate || current == null) {
            camera = target
            publish()
            return
        }
        animation = CameraAnimation(current, target)
        lastFrameTimeNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun cancelAnimation() {
        if (animation != null) {
            animation = null
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        Choreographer.getInstance().removeFrameCallback(tourCallback)
    }

    private fun onAnimationFrame(frameTimeNanos: Long) {
        val running = animation ?: return
        val elapsedSeconds =
            if (lastFrameTimeNanos ==
                0L
            ) {
                DEFAULT_FRAME_SECONDS
            } else {
                (frameTimeNanos - lastFrameTimeNanos) / NANOS_PER_SECOND
            }
        lastFrameTimeNanos = frameTimeNanos
        camera = running.step(elapsedSeconds)
        publish()
        if (running.isFinished) {
            animation = null
        } else {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    var cameraVersion: Int by mutableIntStateOf(0)
        private set

    fun canvasToScreen(): AffineTransform? {
        val current = camera ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return current.canvasToScreen(viewWidth, viewHeight)
    }

    fun screenToCanvas(): AffineTransform? {
        val current = camera ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return current.canvasToScreen(viewWidth, viewHeight).inverted()
    }

    private fun publish() {
        val current = camera ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        engine.setCamera(current.canvasToScreen(viewWidth, viewHeight))
        cameraVersion++
    }

    private companion object {
        const val DEFAULT_FRAME_SECONDS = 1.0 / 60
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val CAMERA_TOUR_SECONDS = 10.0
        const val SYNTHETIC_SPREAD_PX = 100.0
        const val TOUR_MIN_ZOOM = 0.1
        const val TOUR_MAX_ZOOM = 4.0
    }
}
