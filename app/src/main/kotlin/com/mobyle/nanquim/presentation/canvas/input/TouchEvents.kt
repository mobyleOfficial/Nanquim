package com.mobyle.nanquim.presentation.canvas.input

import android.os.Build
import android.view.MotionEvent
import com.mobyle.nanquim.domain.canvas.input.PointerTool
import com.mobyle.nanquim.domain.canvas.input.TouchAction
import com.mobyle.nanquim.domain.canvas.input.TouchEvent
import com.mobyle.nanquim.domain.canvas.input.TouchPointer

fun MotionEvent.toTouchEvent(): TouchEvent =
    TouchEvent(
        action =
            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchAction.Down
                MotionEvent.ACTION_POINTER_DOWN -> TouchAction.PointerDown
                MotionEvent.ACTION_POINTER_UP -> TouchAction.PointerUp
                MotionEvent.ACTION_UP -> TouchAction.Up
                MotionEvent.ACTION_CANCEL -> TouchAction.Cancel
                MotionEvent.ACTION_HOVER_ENTER -> TouchAction.HoverEnter
                MotionEvent.ACTION_HOVER_EXIT -> TouchAction.HoverExit
                MotionEvent.ACTION_HOVER_MOVE -> TouchAction.HoverMove
                else -> TouchAction.Move
            },
        actionIndex = actionIndex,
        timeMillis = eventTime,
        canceled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (flags and MotionEvent.FLAG_CANCELED) != 0,
        pointers =
            (0 until pointerCount).map { index ->
                TouchPointer(
                    id = getPointerId(index),
                    tool = PointerTool.fromToolType(getToolType(index)),
                    x = getX(index),
                    y = getY(index),
                    touchMajor = getTouchMajor(index),
                    touchMinor = getTouchMinor(index),
                )
            },
    )
