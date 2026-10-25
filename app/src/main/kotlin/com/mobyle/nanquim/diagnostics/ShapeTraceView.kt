package com.mobyle.nanquim.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.mobyle.nanquim.document.shape.ShapePoint

@SuppressLint("ViewConstructor")
class ShapeTraceView(
    context: Context,
    inkColor: Int,
    shapeColor: Int,
    private val onStroke: (List<DoubleArray>) -> List<ShapePoint>?,
) : View(context) {
    private val samples = mutableListOf<DoubleArray>()
    private var startTime = 0L
    private val ink = Path()
    private val shape = Path()
    private val inkPaint = strokePaint(inkColor, 3f * resources.displayMetrics.density)
    private val shapePaint = strokePaint(shapeColor, 2f * resources.displayMetrics.density)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(event)
                samples.clear()
                ink.reset()
                shape.reset()
                startTime = event.eventTime
                add(event)
            }
            MotionEvent.ACTION_MOVE -> add(event)
            MotionEvent.ACTION_UP -> {
                add(event)
                val outline = if (samples.size >= 2) onStroke(samples.toList()) else null
                outline?.firstOrNull()?.let { shape.moveTo(it.x.toFloat(), it.y.toFloat()) }
                outline?.drop(1)?.forEach { shape.lineTo(it.x.toFloat(), it.y.toFloat()) }
            }
            MotionEvent.ACTION_CANCEL -> {
                samples.clear()
                ink.reset()
            }
            else -> return true
        }
        invalidate()
        return true
    }

    private fun add(event: MotionEvent) {
        for (index in 0 until event.historySize) {
            add(
                event.getHistoricalX(0, index),
                event.getHistoricalY(0, index),
                event.getHistoricalPressure(0, index),
                event.getHistoricalEventTime(index),
            )
        }
        add(event.x, event.y, event.pressure, event.eventTime)
    }

    private fun add(
        x: Float,
        y: Float,
        pressure: Float,
        time: Long,
    ) {
        if (samples.isEmpty()) ink.moveTo(x, y) else ink.lineTo(x, y)
        samples += doubleArrayOf(x.toDouble(), y.toDouble(), pressure.toDouble(), (time - startTime).toDouble())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(ink, inkPaint)
        canvas.drawPath(shape, shapePaint)
    }

    private fun strokePaint(
        color: Int,
        width: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
}
