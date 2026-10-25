package com.mobyle.nanquim.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.FingerInput
import com.mobyle.nanquim.canvas.input.PointerTool
import com.mobyle.nanquim.canvas.input.TouchAction
import com.mobyle.nanquim.canvas.input.TouchEvent
import com.mobyle.nanquim.canvas.input.TouchPointer
import com.mobyle.nanquim.canvas.input.toTouchEvent
import kotlin.math.max

@SuppressLint("ViewConstructor")
class TouchSessionView(
    context: Context,
    private val xdpi: Float,
    private val recording: () -> Boolean,
    private val onEvent: (TouchEvent) -> Unit,
) : View(context) {
    private val fingers = FingerInput()
    private var shown: List<TouchPointer> = emptyList()
    private var hover: TouchPointer? = null
    private var fingersIgnored = false
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_SP * resources.displayMetrics.scaledDensity }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(event)
        handle(event.toTouchEvent())
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        handle(event.toTouchEvent())
        return true
    }

    private fun handle(event: TouchEvent) {
        fingers.observe(event)
        fingersIgnored = !fingers.fingersAllowed(event.timeMillis)
        when (event.action) {
            TouchAction.HoverEnter, TouchAction.HoverMove -> hover = event.actionPointer
            TouchAction.HoverExit -> hover = null
            TouchAction.Up, TouchAction.Cancel -> shown = emptyList()
            TouchAction.PointerUp -> shown = event.pointers.filterIndexed { index, _ -> index != event.actionIndex }
            else -> shown = event.pointers
        }
        if (recording()) onEvent(event)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hover?.let { pen ->
            outline.color = STYLUS_COLOR
            outline.strokeWidth = density
            canvas.drawCircle(pen.x, pen.y, HOVER_RADIUS_DP * density, outline)
        }
        for (pointer in shown) {
            val (color, kind) =
                when {
                    pointer.tool == PointerTool.Stylus || pointer.tool == PointerTool.StylusEraser ->
                        STYLUS_COLOR to R.string.touch_probe_stylus
                    pointer.tool != PointerTool.Finger -> OTHER_COLOR to R.string.touch_probe_other
                    fingersIgnored -> IGNORED_COLOR to R.string.touch_probe_finger_ignored
                    else -> FINGER_COLOR to R.string.touch_probe_finger
                }
            val radius = max(MINIMUM_RADIUS_DP * density, pointer.touchMajor / 2)
            fill.color = color and TRANSLUCENT
            outline.color = color
            outline.strokeWidth = 2 * density
            canvas.drawCircle(pointer.x, pointer.y, radius, fill)
            canvas.drawCircle(pointer.x, pointer.y, radius, outline)
            text.color = color
            val label =
                context.getString(
                    R.string.touch_probe_pointer,
                    context.getString(kind),
                    millimetres(pointer.touchMajor),
                    millimetres(pointer.touchMinor),
                )
            canvas.drawText(label, pointer.x + radius + 8 * density, pointer.y - radius, text)
        }
    }

    private fun millimetres(pixels: Float) = pixels * MILLIMETRES_PER_INCH / xdpi

    private companion object {
        const val TEXT_SP = 14f
        const val HOVER_RADIUS_DP = 10f
        const val MINIMUM_RADIUS_DP = 6f
        const val MILLIMETRES_PER_INCH = 25.4f
        const val TRANSLUCENT = 0x40FFFFFF
        const val STYLUS_COLOR = 0xFF1E63D6.toInt()
        const val FINGER_COLOR = 0xFF2E9D46.toInt()
        const val IGNORED_COLOR = 0xFFE07B00.toInt()
        const val OTHER_COLOR = 0xFF777777.toInt()
    }
}
