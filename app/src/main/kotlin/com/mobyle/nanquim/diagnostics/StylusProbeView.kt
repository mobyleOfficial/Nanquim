package com.mobyle.nanquim.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class StylusProbeView(
    context: Context,
    private val recorder: StylusObservationRecorder,
    private val onSample: (StylusSample) -> Unit,
) : View(context) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event)
        recordAll(event, isHover = false)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            recorder.endContact()
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        recordAll(event, isHover = true)
        return true
    }

    private fun recordAll(
        event: MotionEvent,
        isHover: Boolean,
    ) {
        val pointerIndex = 0
        val toolTypeName = toolTypeName(event.getToolType(pointerIndex))
        for (historyIndex in 0 until event.historySize) {
            recorder.record(
                StylusSample(
                    toolTypeName = toolTypeName,
                    xPx = event.getHistoricalX(pointerIndex, historyIndex),
                    yPx = event.getHistoricalY(pointerIndex, historyIndex),
                    pressure = event.getHistoricalPressure(pointerIndex, historyIndex),
                    tiltRadians = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, pointerIndex, historyIndex),
                    orientationRadians = event.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex, historyIndex),
                    distance = event.getHistoricalAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex, historyIndex),
                    buttonState = event.buttonState,
                    eventTimeNanos = historicalEventTimeNanos(event, historyIndex),
                    isHover = isHover,
                ),
            )
        }
        val currentSample =
            StylusSample(
                toolTypeName = toolTypeName,
                xPx = event.getX(pointerIndex),
                yPx = event.getY(pointerIndex),
                pressure = event.getPressure(pointerIndex),
                tiltRadians = event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex),
                orientationRadians = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex),
                distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex),
                buttonState = event.buttonState,
                eventTimeNanos = eventTimeNanos(event),
                isHover = isHover,
            )
        recorder.record(currentSample)
        onSample(currentSample)
    }

    private fun eventTimeNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.eventTimeNanos
        } else {
            event.eventTime * NANOS_PER_MILLISECOND
        }

    private fun historicalEventTimeNanos(
        event: MotionEvent,
        historyIndex: Int,
    ): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.getHistoricalEventTimeNanos(historyIndex)
        } else {
            event.getHistoricalEventTime(historyIndex) * NANOS_PER_MILLISECOND
        }

    private fun toolTypeName(toolType: Int): String =
        when (toolType) {
            MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
            MotionEvent.TOOL_TYPE_ERASER -> "eraser"
            MotionEvent.TOOL_TYPE_FINGER -> "finger"
            MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
            else -> "unknown"
        }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
