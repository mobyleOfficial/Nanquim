package com.mobyle.nanquim.canvas.input

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import androidx.input.motionprediction.MotionEventPredictor
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.StrokeStart
import com.mobyle.nanquim.document.guide.StrokeAssist
import com.mobyle.nanquim.engine.AffineTransform
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.StrokeSample
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import com.mobyle.nanquim.engine.StrokeSampleFlags
import com.mobyle.nanquim.engine.StrokeTool
import java.util.concurrent.Executors
import kotlin.math.hypot

class StrokeInput(
    private val engine: Engine,
    private val session: CanvasSession,
    private val screenToCanvas: () -> AffineTransform?,
    private val predictor: MotionEventPredictor?,
    private val quickShapeDelay: () -> Int? = { null },
    private val onShapeSnapped: () -> Unit = {},
) {
    private val samples = StrokeSampleBuffer()
    private var pointerId: Int? = null
    private var start: StrokeStart? = null
    private var tool = StrokeTool.Paint
    private var constantPressure = false

    private var assist: StrokeAssist? = null
    private val held = mutableListOf<StrokeSample>()

    private val recorded = mutableListOf<StrokeSample>()
    private var stillX = 0f
    private var stillY = 0f
    private var snap: QuickShapeSnap? = null
    private var morph: QuickShapeMorph? = null
    private var framesSent = 0
    private var morphStartNanos = 0L
    private var fingerDown = false
    private var holdGeneration = 0
    private var strokeSerial = 0
    private val handler = Handler(Looper.getMainLooper())
    private val holdTimer = Runnable { onHold() }
    private val morphFrame = Choreographer.FrameCallback { advanceMorph(it) }

    val isActive: Boolean get() = pointerId != null

    fun isStrokePointer(
        event: MotionEvent,
        pointerIndex: Int,
    ): Boolean = pointerId == event.getPointerId(pointerIndex)

    fun begin(
        event: MotionEvent,
        pointerIndex: Int,
        route: PointerRoute,
    ): Boolean {
        val transform = screenToCanvas() ?: return false
        tool =
            when (route) {
                PointerRoute.Erase -> StrokeTool.Erase
                PointerRoute.Smudge -> StrokeTool.Smudge
                else -> StrokeTool.Paint
            }
        val first = transform.apply(Point(event.getX(pointerIndex).toDouble(), event.getY(pointerIndex).toDouble()))
        val stroke = session.beginStroke(tool, first) ?: return false
        start = stroke
        assist = session.strokeAssist(stroke, first)
        pointerId = event.getPointerId(pointerIndex)
        constantPressure = PointerTool.fromToolType(event.getToolType(pointerIndex)) in setOf(PointerTool.Mouse, PointerTool.Finger)
        engine.beginStroke(tool, stroke.layerId, stroke.style)
        predictor?.record(event)
        appendCurrent(event, pointerIndex, transform, extraFlags = 0)
        flush()
        stillX = event.getX(pointerIndex)
        stillY = event.getY(pointerIndex)
        armHold()
        return true
    }

    fun move(event: MotionEvent) {
        val index = strokePointerIndex(event) ?: return
        if (snap != null) return
        val transform = screenToCanvas() ?: return
        predictor?.record(event)
        for (history in 0 until event.historySize) {
            watchStillness(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
            samples.add(
                x = event.getHistoricalX(index, history),
                y = event.getHistoricalY(index, history),
                transform = transform,
                pressure = pressure(event.getHistoricalPressure(index, history)),
                tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, history),
                orientation = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, history),
                eventTimeNanos = historicalTimeNanos(event, history),
                event = event,
                index = index,
                flags = StrokeSampleFlags.HISTORICAL,
            )
        }
        watchStillness(event.getX(index), event.getY(index))
        appendCurrent(event, index, transform, extraFlags = canceledFlag(event))
        appendPrediction(transform)
        flush()
    }

    fun end(event: MotionEvent) {
        val shown = morph
        if (shown != null && canceledFlag(event) == 0) {
            if (framesSent < shown.frames.size) engine.replaceStroke(shown.frames.last())
            engine.endStroke()
            start?.let { session.endStroke(it, tool, Point(shown.end.x, shown.end.y), recorded.toList(), shown.shape) }
            reset()
            return
        }
        val index = strokePointerIndex(event)
        val transform = screenToCanvas()
        if (index != null && transform != null) {
            appendCurrent(event, index, transform, extraFlags = canceledFlag(event))
        }
        releaseHeld()
        flush()
        if (canceledFlag(event) != 0) {
            engine.cancelStroke()
            start?.let(session::cancelStroke)
        } else {
            engine.endStroke()
            start?.let { session.endStroke(it, tool, lastPoint, recorded.toList()) }
        }
        reset()
    }

    fun cancel() {
        if (!isActive) return
        engine.cancelStroke()
        start?.let(session::cancelStroke)
        reset()
    }

    fun setFingerDown(down: Boolean) {
        if (down == fingerDown) return
        fingerDown = down
        val recognized = snap ?: return
        val serial = strokeSerial
        RECOGNITION.execute {
            val constrained = recognized.morph(down)
            handler.post { if (constrained != null && serial == strokeSerial) show(constrained) }
        }
    }

    private fun reset() {
        samples.clear()
        held.clear()
        recorded.clear()
        handler.removeCallbacks(holdTimer)
        Choreographer.getInstance().removeFrameCallback(morphFrame)
        snap = null
        morph = null
        fingerDown = false
        holdGeneration++
        strokeSerial++
        assist = null
        pointerId = null
        start = null
    }

    private fun watchStillness(
        x: Float,
        y: Float,
    ) {
        if (hypot((x - stillX).toDouble(), (y - stillY).toDouble()) < QuickShapeSnap.STILL_DISTANCE_PX) return
        stillX = x
        stillY = y
        armHold()
    }

    private fun armHold() {
        holdGeneration++
        handler.removeCallbacks(holdTimer)
        val delay = quickShapeDelay() ?: return
        handler.postDelayed(holdTimer, delay.toLong())
    }

    private fun onHold() {
        if (!isActive || snap != null || recorded.size < 2) return
        val canvasPerScreenPixel = screenToCanvas()?.scaleFactor ?: return
        val generation = holdGeneration
        val stroke = recorded.toList()
        val constrain = fingerDown
        RECOGNITION.execute {
            val started = System.nanoTime()
            val recognized = QuickShapeSnap.recognize(stroke, canvasPerScreenPixel)
            val first = recognized?.morph(constrain)
            val micros = (System.nanoTime() - started) / NANOS_PER_MICRO
            handler.post {
                Log.d(TAG, "QuickShape: ${recognized?.shape?.kind ?: "no shape"} from ${stroke.size} samples in $micros us")
                if (recognized == null || first == null || generation != holdGeneration || !isActive || snap != null) return@post
                snap = recognized
                samples.clear()
                held.clear()
                onShapeSnapped()
                show(first)
                val finger = fingerDown
                if (finger != constrain) {
                    fingerDown = constrain
                    setFingerDown(finger)
                }
            }
        }
    }

    private fun show(next: QuickShapeMorph) {
        morph = next
        framesSent = 0
        morphStartNanos = System.nanoTime()
        val choreographer = Choreographer.getInstance()
        choreographer.removeFrameCallback(morphFrame)
        choreographer.postFrameCallback(morphFrame)
    }

    private fun advanceMorph(frameNanos: Long) {
        val shown = morph ?: return
        val count = shown.frames.size
        val frameLength = QuickShapeSnap.MORPH_MILLIS * NANOS_PER_MILLI / count
        val elapsed = (frameNanos - morphStartNanos).coerceAtLeast(0L) + FRAME_NANOS
        val reached = (elapsed / frameLength).toInt().coerceIn(1, count)
        if (reached > framesSent) {
            engine.replaceStroke(shown.frames[reached - 1])
            framesSent = reached
        }
        if (framesSent < count) Choreographer.getInstance().postFrameCallback(morphFrame)
    }

    private fun strokePointerIndex(event: MotionEvent): Int? {
        val id = pointerId ?: return null
        return event.findPointerIndex(id).takeIf { it >= 0 }
    }

    private var lastPoint: Point? = null

    private fun appendCurrent(
        event: MotionEvent,
        index: Int,
        transform: AffineTransform,
        extraFlags: Int,
    ) {
        if (extraFlags and StrokeSampleFlags.PREDICTED == 0) {
            lastPoint = transform.apply(Point(event.getX(index).toDouble(), event.getY(index).toDouble()))
        }
        appendSample(event, index, transform, extraFlags)
    }

    private fun appendSample(
        event: MotionEvent,
        index: Int,
        transform: AffineTransform,
        extraFlags: Int,
    ) = samples.add(
        x = event.getX(index),
        y = event.getY(index),
        transform = transform,
        pressure = pressure(event.getPressure(index)),
        tilt = event.getAxisValue(MotionEvent.AXIS_TILT, index),
        orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index),
        eventTimeNanos = currentTimeNanos(event),
        event = event,
        index = index,
        flags = extraFlags,
    )

    private fun appendPrediction(transform: AffineTransform) {
        val predicted = predictor?.predict() ?: return
        try {
            val id = pointerId ?: return
            val index = predicted.findPointerIndex(id).takeIf { it >= 0 } ?: return
            for (history in 0 until predicted.historySize) {
                samples.add(
                    x = predicted.getHistoricalX(index, history),
                    y = predicted.getHistoricalY(index, history),
                    transform = transform,
                    pressure = pressure(predicted.getHistoricalPressure(index, history)),
                    tilt = predicted.getHistoricalAxisValue(MotionEvent.AXIS_TILT, index, history),
                    orientation = predicted.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, index, history),
                    eventTimeNanos = historicalTimeNanos(predicted, history),
                    event = predicted,
                    index = index,
                    flags = StrokeSampleFlags.PREDICTED,
                )
            }
            appendCurrent(predicted, index, transform, extraFlags = StrokeSampleFlags.PREDICTED)
        } finally {
            predicted.recycle()
        }
    }

    private fun flush() {
        if (samples.count == 0) return
        engine.addStrokeSamples(samples)
        samples.clear()
    }

    private fun releaseHeld() {
        val assist = assist ?: return
        for (sample in held) {
            val (x, y) = assist.release(sample.x, sample.y)
            samples.record(sample.copy(x = x, y = y))
        }
        held.clear()
    }

    private fun StrokeSampleBuffer.record(sample: StrokeSample) {
        add(sample)
        if (sample.flags and (StrokeSampleFlags.PREDICTED or StrokeSampleFlags.CANCELED) == 0) recorded += sample
    }

    private fun pressure(raw: Float): Float = if (constantPressure) 1f else raw.coerceIn(0f, 1f)

    private fun StrokeSampleBuffer.add(
        x: Float,
        y: Float,
        transform: AffineTransform,
        pressure: Float,
        tilt: Float,
        orientation: Float,
        eventTimeNanos: Long,
        event: MotionEvent,
        index: Int,
        flags: Int,
    ) {
        val canvas = transform.apply(Point(x.toDouble(), y.toDouble()))
        val sample =
            StrokeSample(
                canvas.x,
                canvas.y,
                pressure,
                tilt,
                orientation,
                eventTimeNanos,
                event.getToolType(index),
                event.buttonState,
                flags,
                event.getPointerId(index),
            )
        val assist = assist
        if (assist == null) {
            record(sample)
            return
        }
        val open = assist.line == null && !assist.free
        if (open && flags and StrokeSampleFlags.PREDICTED != 0) return
        val placed = assist.place(sample.x, sample.y)
        if (placed == null) {
            held += sample
            return
        }
        releaseHeld()
        record(sample.copy(x = placed.first, y = placed.second))
    }

    companion object {
        private const val TAG = "StrokeInput"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
        private const val FRAME_NANOS = 16_666_667L

        private val RECOGNITION =
            Executors.newSingleThreadExecutor { task -> Thread(task, "QuickShape").apply { isDaemon = true } }

        fun currentTimeNanos(event: MotionEvent): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                event.eventTimeNanos
            } else {
                event.eventTime * NANOS_PER_MILLI
            }

        fun historicalTimeNanos(
            event: MotionEvent,
            history: Int,
        ): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                event.getHistoricalEventTimeNanos(history)
            } else {
                event.getHistoricalEventTime(history) * NANOS_PER_MILLI
            }

        fun canceledFlag(event: MotionEvent): Int {
            val canceledByFlag =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (event.flags and MotionEvent.FLAG_CANCELED) != 0
            return if (event.actionMasked == MotionEvent.ACTION_CANCEL || canceledByFlag) StrokeSampleFlags.CANCELED else 0
        }
    }
}

internal fun StrokeSampleBuffer.assist(assist: StrokeAssist) {
    for (index in 0 until count) {
        val point = pointAt(index)
        if (assist.place(point.x, point.y) != null) break
    }
    for (index in 0 until count) {
        val point = pointAt(index)
        val (x, y) = assist.release(point.x, point.y)
        setPoint(index, x.toFloat(), y.toFloat())
    }
}
