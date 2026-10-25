package com.mobyle.nanquim.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import androidx.input.motionprediction.MotionEventPredictor
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.canvas.input.FingerEffect
import com.mobyle.nanquim.canvas.input.FingerInput
import com.mobyle.nanquim.canvas.input.Gesture
import com.mobyle.nanquim.canvas.input.GestureAction
import com.mobyle.nanquim.canvas.input.GestureArbiter
import com.mobyle.nanquim.canvas.input.PointerRoute
import com.mobyle.nanquim.canvas.input.PointerTool
import com.mobyle.nanquim.canvas.input.StrokeInput
import com.mobyle.nanquim.canvas.input.StylusButtonGestures
import com.mobyle.nanquim.canvas.input.TouchAction
import com.mobyle.nanquim.canvas.input.TouchEvent
import com.mobyle.nanquim.canvas.input.routePointer
import com.mobyle.nanquim.canvas.input.toTouchEvent
import com.mobyle.nanquim.canvas.selection.SelectionEditor
import com.mobyle.nanquim.canvas.selection.SelectionMode
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.CanvasCommandIds
import com.mobyle.nanquim.command.QuickMenuCommandIds
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.GuideCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.ui.canvas.EngineSurfaceView
import com.mobyle.nanquim.document.guide.GuideType
import com.mobyle.nanquim.document.guide.PerspectiveGuide
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.engine.AdjustmentFilter
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.Point
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

@SuppressLint("ViewConstructor")
class CanvasSurfaceView(
    context: Context,
    engine: Engine,
    private val session: CanvasSession,
    private val onCommand: (String, Map<String, Any?>) -> Unit,
) : EngineSurfaceView(context, engine) {
    private val viewport = session.viewport

    private fun snapFeedback() {
        if (session.hapticsEnabled()) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    init {
        session.onSnapped = { snapFeedback() }
    }
    private val stroke =
        StrokeInput(
            engine,
            session,
            viewport::screenToCanvas,
            MotionEventPredictor.newInstance(this),
            quickShapeDelay = { session.quickShapeDelay() },
            onShapeSnapped = { snapFeedback() },
        )
    private val fingers =
        FingerInput(
            support = { session.palmSupport() },
            millimetresPerPixel = MILLIMETRES_PER_INCH / resources.displayMetrics.xdpi,
            bindings = { session.gestureBindings() },
        )
    private val gestureTimer = Runnable { onGestureTimer() }
    private val stylusButton = StylusButtonGestures()

    private var fingerDownEvent: MotionEvent? = null
    private var handlingEvent: MotionEvent? = null

    private var eyedropperPointer: Int? = null

    private var selectionPointer: Int? = null
    private var constrainPointer: Int? = null

    private var thresholdPointer: Int? = null

    private var fillTap: Triple<Int, Float, Float>? = null

    private var guideTap: Triple<Int, Float, Float>? = null
    private var textTap: Triple<Int, Float, Float>? = null

    private var cloneMarkerPointer: Int? = null

    private var transformPointer: Int? = null
    private var transformLast: Triple<Float, Float, Long>? = null
    private var transformTwist = false

    private var adjustPointer: Int? = null
    private var adjustStart: Pair<Float, Float>? = null
    private var adjustDragging = false

    private var adjustPuck = false

    private var liquifyPointer: Int? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    init {
        contentDescription = context.getString(R.string.canvas_content_description)
    }

    private val modifierTap: () -> Unit = { onModifierTap() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session.onModifierTap = modifierTap
    }

    override fun onDetachedFromWindow() {
        if (session.onModifierTap === modifierTap) session.onModifierTap = null
        super.onDetachedFromWindow()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        super.surfaceChanged(holder, format, width, height)
        viewport.onViewSizeChanged(width, height)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        val tool = PointerTool.fromToolType(event.getToolType(0))
        if (tool == PointerTool.Mouse) {
            val hovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            session.updateHover(if (hovering) HoverPoint(event.x, event.y, 0f) else null)
        }
        if (isStylus(tool)) {
            keepGpuAwake(event.eventTime)
            observeStylusButton(event)
            val hovering = event.actionMasked != MotionEvent.ACTION_HOVER_EXIT
            fingers.observe(touchEvent(event)).forEach(::apply)
            session.updateHover(
                if (hovering) HoverPoint(event.x, event.y, event.getAxisValue(MotionEvent.AXIS_ORIENTATION)) else null,
            )
        }
        return super.onHoverEvent(event)
    }

    private var gpuAwakeRequestedAt = Long.MIN_VALUE

    private fun keepGpuAwake(eventTime: Long) {
        if (eventTime - gpuAwakeRequestedAt < GPU_AWAKE_REFRESH_MILLIS) return
        gpuAwakeRequestedAt = eventTime
        engine.keepGpuAwake(GPU_AWAKE_MILLIS)
    }

    private var ignoringGesture = false

    private var panPointer: Int? = null

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (scroll != 0f) {
                viewport.zoomAround(Point(event.x.toDouble(), event.y.toDouble()), WHEEL_ZOOM_STEP.pow(scroll.toDouble()))
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun handlePanEvent(
        event: MotionEvent,
        pointer: PointerTool,
    ): Boolean {
        val panning = panPointer
        if (panning == null) {
            val middleButton = pointer == PointerTool.Mouse && event.buttonState and MotionEvent.BUTTON_TERTIARY != 0
            if (event.actionMasked != MotionEvent.ACTION_DOWN || !(session.spacePanning || middleButton)) return false
            panPointer = event.getPointerId(0)
            viewport.beginPan(Point(event.x.toDouble(), event.y.toDouble()), event.eventTime)
            return true
        }
        val index = event.findPointerIndex(panning)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) viewport.updatePan(Point(event.getX(index).toDouble(), event.getY(index).toDouble()))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                viewport.endTwoFingerGesture(event.eventTime)
                panPointer = null
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoringGesture = session.loading
        if (ignoringGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoringGesture = false
            return true
        }
        val actionIndex = event.actionIndex
        val pointer = PointerTool.fromToolType(event.getToolType(actionIndex))
        if (!stroke.isActive && handlePanEvent(event, pointer)) return true
        val touch = touchEvent(event)
        trackStylusContact(event, touch, pointer)
        countFingers(event)
        if (thresholdPointer != null) {
            handleThresholdEvent(event)
            return true
        }
        if (session.canvasTool == CanvasTool.Transform && handleTransformEvent(event, pointer)) return true
        if (session.canvasTool == CanvasTool.Adjust && handleAdjustmentEvent(event, pointer)) return true
        if (session.canvasTool == CanvasTool.Clone && handleCloneMarkerEvent(event, pointer)) return true
        if (session.canvasTool == CanvasTool.Guide) watchGuideTap(event)
        if (session.canvasTool == CanvasTool.Text) watchTextTap(event)
        if (session.continueFilling && handleFillTap(event)) return true
        if (stroke.isActive) {
            handleStrokeEvent(event, actionIndex)
            return true
        }
        if (eyedropperPointer != null) {
            handleEyedropperEvent(event)
            return true
        }
        if (selectionPointer != null) {
            handleSelectionEvent(event)
            return true
        }
        if (session.modifierHeld && handleModifierTouch(event)) return true
        rememberFingerDown(event, pointer)
        handlingEvent = event
        fingers.onTouch(touch).forEach(::apply)
        handlingEvent = null
        if (stroke.isActive) return true
        if (touch.action == TouchAction.Down || touch.action == TouchAction.PointerDown) scheduleGestureTimer()
        if (eyedropperPointer != null) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (session.eyedropperArmed) {
                startEyedropper(event.getPointerId(0), event.getX(0), event.getY(0))
            } else {
                beginStrokeIfDrawingPointer(event, pointer)
            }
        } else if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && isStylus(pointer)) {
            val route = routePointer(pointer, session.canvasTool, session.paintsSelection, session.adjustmentPencil)
            if (route == PointerRoute.Paint || route == PointerRoute.Erase || route == PointerRoute.Smudge) {
                beginStrokeIfDrawingPointer(event, pointer)
            }
        }
        return true
    }

    private fun apply(effect: FingerEffect) {
        when (effect) {
            is FingerEffect.BeginNavigation -> {
                viewport.rotationGestureEnabled = session.gestureConfiguration().rotation
                viewport.beginTwoFingerGesture(effect.first, effect.second, effect.timeMillis)
            }
            is FingerEffect.UpdateNavigation -> viewport.updateTwoFingerGesture(effect.first, effect.second)
            is FingerEffect.EndNavigation -> viewport.endTwoFingerGesture(effect.timeMillis)
            FingerEffect.CancelNavigation -> viewport.cancelTwoFingerGesture()
            FingerEffect.Undo -> onCommand(DocumentCommandIds.UNDO, emptyMap())
            FingerEffect.Redo -> onCommand(DocumentCommandIds.REDO, emptyMap())
            is FingerEffect.EyedropperStart ->
                if (session.canvasTool == CanvasTool.Clone) {
                    viewport.screenToCanvas()?.apply(Point(effect.x.toDouble(), effect.y.toDouble()))?.let(session::setCloneSource)
                    fingers.cancelTaps()
                } else {
                    startEyedropper(effect.pointerId, effect.x, effect.y)
                }
            is FingerEffect.Gesture -> perform(effect.action, effect.x, effect.y)
            is FingerEffect.BeginFingerStroke -> beginFingerStroke(effect.pointerId, effect.action)
        }
    }

    private fun perform(
        action: GestureAction,
        x: Float,
        y: Float,
    ) {
        val canvas = { viewport.screenToCanvas()?.apply(Point(x.toDouble(), y.toDouble())) }
        when (action) {
            GestureAction.Undo -> onCommand(DocumentCommandIds.UNDO, emptyMap())
            GestureAction.Redo -> onCommand(DocumentCommandIds.REDO, emptyMap())
            GestureAction.Eyedropper -> canvas()?.let { onCommand(DocumentCommandIds.EYEDROPPER, mapOf("x" to it.x, "y" to it.y)) }
            GestureAction.FullScreen -> onCommand(CanvasCommandIds.VIEW_FULL_SCREEN, emptyMap())
            GestureAction.ClearLayer -> {
                if (session.hapticsEnabled()) performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onCommand(DocumentCommandIds.LAYER_CLEAR, emptyMap())
            }
            GestureAction.CopyPasteMenu -> onCommand(CanvasCommandIds.VIEW_COPY_PASTE_MENU, mapOf("x" to x.toDouble(), "y" to y.toDouble()))
            GestureAction.FitCanvas -> onCommand(CanvasCommandIds.VIEW_FIT, emptyMap())
            GestureAction.LayerSelect -> canvas()?.let { onCommand(DocumentCommandIds.LAYER_SELECT_AT, mapOf("x" to it.x, "y" to it.y)) }
            GestureAction.ToggleEraser ->
                onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to if (session.canvasTool == CanvasTool.Eraser) "brush" else "eraser"))
            GestureAction.AssistedDrawing ->
                (session.documentSession?.state?.activeLayer as? RasterLayer)?.let {
                    onCommand(DocumentCommandIds.LAYER_SET_ASSISTED, mapOf("layerId" to it.id, "enabled" to !it.assisted))
                }
            GestureAction.QuickMenu -> onCommand(QuickMenuCommandIds.SHOW, mapOf("x" to x.toDouble(), "y" to y.toDouble()))
            GestureAction.ShortcutBrush -> onCommand(BrushCommandIds.SHORTCUT_TOGGLE, emptyMap())
            GestureAction.None, GestureAction.Paint, GestureAction.Smudge, GestureAction.Erase -> Unit
        }
    }

    private fun rememberFingerDown(
        event: MotionEvent,
        pointer: PointerTool,
    ) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        fingerDownEvent?.recycle()
        fingerDownEvent = if (pointer == PointerTool.Finger) MotionEvent.obtain(event) else null
    }

    private fun beginFingerStroke(
        pointerId: Int,
        action: GestureAction,
    ) {
        val down = fingerDownEvent ?: return
        if (down.getPointerId(0) != pointerId || stroke.isActive) return
        val route = strokeRoute(action) ?: return
        fingers.cancelTaps()
        cancelNavigation()
        handlingEvent?.let(::requestUnbufferedDispatch)
        if (!stroke.begin(down, 0, route)) return
        handlingEvent?.takeIf { it.actionMasked == MotionEvent.ACTION_MOVE }?.let(stroke::move)
    }

    private fun strokeRoute(action: GestureAction): PointerRoute? {
        val route =
            when (action) {
                GestureAction.Paint ->
                    routePointer(PointerTool.Stylus, session.canvasTool, session.paintsSelection, session.adjustmentPencil)
                GestureAction.Smudge -> PointerRoute.Smudge
                GestureAction.Erase -> PointerRoute.Erase
                else -> return null
            }
        return route.takeIf { it == PointerRoute.Paint || it == PointerRoute.Erase || it == PointerRoute.Smudge }
    }

    private fun observeStylusButton(event: MotionEvent) {
        val pressed = event.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY) != 0
        val bindings = session.gestureBindings()
        when (stylusButton.update(pressed, event.eventTime)) {
            StylusButtonGestures.Change.Pressed -> {
                val action = bindings.actionOf(Gesture.StylusButton)
                if (!action.stroke && action != GestureAction.Eyedropper) perform(action, event.x, event.y)
            }
            StylusButtonGestures.Change.DoublePressed -> perform(bindings.actionOf(Gesture.StylusButtonDoublePress), event.x, event.y)
            StylusButtonGestures.Change.Released, null -> Unit
        }
    }

    private fun onModifierTap() {
        when (val action = session.gestureBindings().actionOf(Gesture.ModifierTap)) {
            GestureAction.Eyedropper -> onCommand(DocumentCommandIds.EYEDROPPER_ARM, emptyMap())
            else -> perform(action, width / 2f, height / 2f)
        }
    }

    private fun handleModifierTouch(event: MotionEvent): Boolean {
        val masked = event.actionMasked
        if (masked != MotionEvent.ACTION_DOWN && masked != MotionEvent.ACTION_POINTER_DOWN) return false
        val action = session.gestureBindings().actionOf(Gesture.ModifierTouch)
        if (action == GestureAction.None) return false
        session.useModifier()
        fingers.cancelTaps()
        cancelNavigation()
        val index = event.actionIndex
        when {
            action == GestureAction.Eyedropper -> startEyedropper(event.getPointerId(index), event.getX(index), event.getY(index))
            action.stroke ->
                strokeRoute(action)?.let { route ->
                    requestUnbufferedDispatch(event)
                    stroke.begin(event, index, route)
                }
            else -> perform(action, event.getX(index), event.getY(index))
        }
        if (masked == MotionEvent.ACTION_DOWN && !stroke.isActive && eyedropperPointer == null) ignoringGesture = true
        return true
    }

    private fun scheduleGestureTimer() {
        removeCallbacks(gestureTimer)
        val at = fingers.nextCheckMillis() ?: return
        postDelayed(gestureTimer, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun onGestureTimer() {
        fingers.onTimer(SystemClock.uptimeMillis()).forEach(::apply)
        scheduleGestureTimer()
    }

    private fun cancelNavigation() {
        fingers.cancelNavigation()?.let(::apply)
    }

    private fun startEyedropper(
        pointerId: Int,
        x: Float,
        y: Float,
    ) {
        eyedropperPointer = pointerId
        cancelNavigation()
        session.eyedropperAt(x, y)
    }

    private fun handleEyedropperEvent(event: MotionEvent) {
        val pointerId = eyedropperPointer ?: return
        val index = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) session.eyedropperAt(event.getX(index), event.getY(index))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    finishEyedropper(pick = true)
                    fingers.cancelTaps()
                }
            MotionEvent.ACTION_CANCEL -> {
                finishEyedropper(pick = false)
                fingers.cancelTaps()
            }
        }
    }

    private fun finishEyedropper(pick: Boolean) {
        eyedropperPointer = null
        session.disarmEyedropper()
        val point = session.endEyedropper(pick) ?: return
        onCommand(DocumentCommandIds.EYEDROPPER, mapOf("x" to point.x, "y" to point.y))
    }

    private fun beginStrokeIfDrawingPointer(
        event: MotionEvent,
        pointer: PointerTool,
    ) {
        val route = routePointer(pointer, session.canvasTool, session.paintsSelection, session.adjustmentPencil)
        if (route == PointerRoute.Select && session.selectionMode == SelectionMode.Automatic) {
            val index = event.actionIndex
            if (session.beginThresholdDrag(event.getX(index), event.getY(index), automaticSelection = true)) {
                thresholdPointer = event.getPointerId(index)
                fingers.cancelTaps()
            }
            return
        }
        if (route == PointerRoute.Select) {
            beginSelectionGesture(event, event.actionIndex)
            return
        }
        if (route != PointerRoute.Paint && route != PointerRoute.Erase && route != PointerRoute.Smudge) return
        if (isStylus(pointer) && stylusButton.pressed) {
            val action = session.gestureBindings().actionOf(Gesture.StylusButton)
            val index = event.actionIndex
            if (action == GestureAction.Eyedropper) {
                fingers.cancelTaps()
                startEyedropper(event.getPointerId(index), event.getX(index), event.getY(index))
                return
            }
            strokeRoute(action)?.let { buttonRoute ->
                requestUnbufferedDispatch(event)
                stroke.begin(event, index, buttonRoute)
                return
            }
        }
        if (pointer == PointerTool.Mouse && event.buttonState != 0 && (event.buttonState and MotionEvent.BUTTON_PRIMARY) == 0) return
        requestUnbufferedDispatch(event)
        stroke.begin(event, event.actionIndex, route)
    }

    private fun handleCloneMarkerEvent(
        event: MotionEvent,
        pointer: PointerTool,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pointer != PointerTool.Finger) return false
                val marker = session.cloneSourcePoint ?: return false
                val screen = viewport.canvasToScreen()?.apply(marker) ?: return false
                if (hypot(screen.x - event.getX(0), screen.y - event.getY(0)) > CLONE_MARKER_RADIUS_PX) return false
                cloneMarkerPointer = event.getPointerId(0)
                fingers.cancelTaps()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = cloneMarkerPointer ?: return false
                val index = event.findPointerIndex(id).takeIf { it >= 0 } ?: return true
                canvasPoint(event, index)?.let(session::setCloneSource)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (cloneMarkerPointer == null) return false
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP || event.getPointerId(event.actionIndex) == cloneMarkerPointer) {
                    cloneMarkerPointer = null
                }
                return true
            }
        }
        return cloneMarkerPointer != null
    }

    private fun handleTransformEvent(
        event: MotionEvent,
        pointer: PointerTool,
    ): Boolean {
        val editor = session.transformEditor ?: return false
        if (!session.transformReady) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pointer == PointerTool.Unknown) return false
                val point = canvasPoint(event, 0) ?: return false
                if (!editor.down(point, zoom())) return pointer != PointerTool.Finger
                transformPointer = event.getPointerId(0)
                transformLast = Triple(event.getX(0), event.getY(0), event.eventTime)
                requestUnbufferedDispatch(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount != 2 || !allFingers(event)) return transformPointer != null || transformTwist
                val first = canvasPoint(event, 0) ?: return false
                val second = canvasPoint(event, 1) ?: return false
                if (editor.inside(first) && editor.inside(second)) {
                    if (transformPointer != null) editor.cancelGesture()
                    transformPointer = null
                    transformTwist = true
                    editor.twistStart(first, second)
                    return true
                }
                if (transformPointer != null) {
                    editor.cancelGesture()
                    transformPointer = null
                    session.transformChanged()
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (transformTwist && event.pointerCount >= 2) {
                    val first = canvasPoint(event, 0) ?: return true
                    val second = canvasPoint(event, 1) ?: return true
                    editor.twistUpdate(first, second)
                    session.transformChanged()
                    return true
                }
                val id = transformPointer ?: return false
                val index = event.findPointerIndex(id).takeIf { it >= 0 } ?: return true
                val point = canvasPoint(event, index) ?: return true
                val (lastX, lastY, lastTime) = transformLast ?: Triple(event.getX(index), event.getY(index), event.eventTime)
                val elapsed = (event.eventTime - lastTime).coerceAtLeast(1L)
                val velocity = hypot(event.getX(index) - lastX, event.getY(index) - lastY) * MILLIS_PER_SECOND / elapsed
                transformLast = Triple(event.getX(index), event.getY(index), event.eventTime)
                editor.move(point, zoom(), velocity.toDouble())
                session.transformChanged()
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (transformTwist) {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.pointerCount <= 2) {
                        transformTwist = false
                        editor.twistEnd()
                        session.transformChanged()
                    }
                    return true
                }
                if (transformPointer == event.getPointerId(event.actionIndex)) {
                    transformPointer = null
                    editor.up()
                    session.transformChanged()
                    return true
                }
                return transformPointer != null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (transformTwist || transformPointer != null) editor.cancelGesture()
                transformTwist = false
                transformPointer = null
                session.transformChanged()
                return false
            }
        }
        return false
    }

    private fun handleAdjustmentEvent(
        event: MotionEvent,
        pointer: PointerTool,
    ): Boolean {
        val editor = session.adjustmentEditor ?: return false
        if (editor.filter == AdjustmentFilter.Liquify) return handleLiquifyEvent(event, pointer)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pointer == PointerTool.Unknown) return false
                adjustPuck =
                    session.adjustmentPuck?.let { puck ->
                        val screen = viewport.canvasToScreen()?.apply(puck)
                        screen != null && hypot(screen.x - event.getX(0), screen.y - event.getY(0)) <= PUCK_RADIUS_PX
                    } ?: false
                if (session.adjustmentPencil && pointer != PointerTool.Finger && !adjustPuck) return false
                if (pointer == PointerTool.Finger && !fingers.fingersAllowed(event.eventTime)) return true
                adjustPointer = event.getPointerId(0)
                adjustStart = event.getX(0) to event.getY(0)
                adjustDragging = false
                requestUnbufferedDispatch(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (adjustPointer == null) return false
                if (adjustDragging) {
                    editor.cancelChange()
                    session.adjustmentChanged()
                }
                adjustPointer = null
                adjustPuck = false
                return !(event.pointerCount == 2 && allFingers(event))
            }
            MotionEvent.ACTION_MOVE -> {
                val id = adjustPointer ?: return false
                val index = event.findPointerIndex(id).takeIf { it >= 0 } ?: return true
                val (startX, startY) = adjustStart ?: return true
                val deltaX = event.getX(index) - startX
                val deltaY = event.getY(index) - startY
                if (adjustPuck) {
                    adjustDragging = true
                    session.showAdjustmentMenu(null)
                    canvasPoint(event, index)?.let(session::moveAdjustmentPuck)
                    return true
                }
                if (!adjustDragging && hypot(deltaX, deltaY) < touchSlop) return true
                if (!adjustDragging) {
                    adjustDragging = true
                    session.showAdjustmentMenu(null)
                    editor.beginDrag()
                }
                if (editor.filter == AdjustmentFilter.MotionBlur) {
                    val transform = viewport.screenToCanvas()
                    val start = transform?.apply(Point(startX.toDouble(), startY.toDouble()))
                    val end = transform?.apply(pointer(event, index))
                    if (start != null && end != null) {
                        editor.dragAlong(hypot(deltaX, deltaY) / width.coerceAtLeast(1), atan2(end.y - start.y, end.x - start.x))
                    }
                } else {
                    editor.dragBy(deltaX / width.coerceAtLeast(1))
                }
                session.adjustmentChanged()
                return true
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (adjustPointer != event.getPointerId(event.actionIndex)) return adjustPointer != null
                adjustPointer = null
                adjustPuck = false
                if (adjustDragging) {
                    editor.finishChange()
                    session.adjustmentChanged()
                } else {
                    val (x, y) = adjustStart ?: return true
                    session.showAdjustmentMenu(if (session.adjustmentMenu == null) Point(x.toDouble(), y.toDouble()) else null)
                }
                adjustDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (adjustDragging) {
                    editor.cancelChange()
                    session.adjustmentChanged()
                }
                adjustPointer = null
                adjustDragging = false
                adjustPuck = false
                return false
            }
        }
        return false
    }

    private fun handleLiquifyEvent(
        event: MotionEvent,
        pointer: PointerTool,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pointer == PointerTool.Unknown) return false
                if (pointer == PointerTool.Finger && !fingers.fingersAllowed(event.eventTime)) return true
                val sample = liquifySamples(event, 0, history = false) ?: return false
                if (!session.beginLiquifyStroke(sample, usePressure = pointer == PointerTool.Stylus)) return false
                liquifyPointer = event.getPointerId(0)
                requestUnbufferedDispatch(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = liquifyPointer ?: return false
                val index = event.findPointerIndex(id).takeIf { it >= 0 } ?: return true
                liquifySamples(event, index, history = true)?.let(session::addLiquifySamples)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (liquifyPointer == null) return false
                session.endLiquifyStroke(cancel = true)
                liquifyPointer = null
                return !(event.pointerCount == 2 && allFingers(event))
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                if (liquifyPointer != event.getPointerId(event.actionIndex)) return liquifyPointer != null
                liquifySamples(event, event.actionIndex, history = false)?.let(session::addLiquifySamples)
                session.endLiquifyStroke(cancel = false)
                liquifyPointer = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (liquifyPointer != null) session.endLiquifyStroke(cancel = true)
                liquifyPointer = null
                return false
            }
        }
        return liquifyPointer != null
    }

    private fun liquifySamples(
        event: MotionEvent,
        index: Int,
        history: Boolean,
    ): FloatArray? {
        val transform = viewport.screenToCanvas() ?: return null
        val stylus = PointerTool.fromToolType(event.getToolType(index)) == PointerTool.Stylus
        val values = ArrayList<Float>()

        fun add(
            x: Float,
            y: Float,
            pressure: Float,
            time: Long,
        ) {
            val point = transform.apply(Point(x.toDouble(), y.toDouble()))
            values += point.x.toFloat()
            values += point.y.toFloat()
            values += if (stylus) pressure.coerceIn(0f, 1f) else 1f
            values += time.toFloat()
        }
        if (history) {
            for (step in 0 until event.historySize) {
                add(
                    event.getHistoricalX(index, step),
                    event.getHistoricalY(index, step),
                    event.getHistoricalPressure(index, step),
                    event.getHistoricalEventTime(step),
                )
            }
        }
        add(event.getX(index), event.getY(index), event.getPressure(index), event.eventTime)
        return values.toFloatArray()
    }

    private fun countFingers(event: MotionEvent) {
        session.canvasFingersDown =
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0
                MotionEvent.ACTION_POINTER_UP -> (0 until event.pointerCount).count { it != event.actionIndex && isFinger(event, it) }
                else -> (0 until event.pointerCount).count { isFinger(event, it) }
            }
    }

    private fun isFinger(
        event: MotionEvent,
        index: Int,
    ) = PointerTool.fromToolType(event.getToolType(index)) == PointerTool.Finger

    private fun handleThresholdEvent(event: MotionEvent) {
        val pointerId = thresholdPointer ?: return
        val index = event.findPointerIndex(pointerId)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) session.updateThresholdDrag(event.getX(index), event.getY(index))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (event.getPointerId(event.actionIndex) == pointerId) {
                    thresholdPointer = null
                    session.endThresholdDrag(commit = true)
                }
            MotionEvent.ACTION_CANCEL -> {
                thresholdPointer = null
                session.endThresholdDrag(commit = false)
            }
        }
    }

    private fun watchGuideTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> guideTap = Triple(event.getPointerId(0), event.getX(0), event.getY(0))
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> guideTap = null
            MotionEvent.ACTION_MOVE -> {
                val (id, x, y) = guideTap ?: return
                val index = event.findPointerIndex(id)
                if (index >= 0 && hypot(event.getX(index) - x, event.getY(index) - y) > GestureArbiter.TAP_SLOP_PX) guideTap = null
            }
            MotionEvent.ACTION_UP -> {
                val (_, x, y) = guideTap ?: return
                guideTap = null
                if (session.guides.type != GuideType.Perspective) return
                val point = viewport.screenToCanvas()?.apply(Point(x.toDouble(), y.toDouble())) ?: return
                if (session.guides.perspective.points.size < PerspectiveGuide.MAXIMUM_POINTS) {
                    onCommand(GuideCommandIds.PERSPECTIVE_ADD, mapOf("x" to point.x, "y" to point.y))
                } else {
                    session.selectVanishingPoint(null)
                }
            }
        }
    }

    private fun watchTextTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> textTap = Triple(event.getPointerId(0), event.getX(0), event.getY(0))
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> textTap = null
            MotionEvent.ACTION_MOVE -> {
                val (id, x, y) = textTap ?: return
                val index = event.findPointerIndex(id)
                if (index >= 0 && hypot(event.getX(index) - x, event.getY(index) - y) > GestureArbiter.TAP_SLOP_PX) textTap = null
            }
            MotionEvent.ACTION_UP -> {
                val (_, x, y) = textTap ?: return
                textTap = null
                val point = viewport.screenToCanvas()?.apply(Point(x.toDouble(), y.toDouble())) ?: return
                session.tapText(point.x, point.y)
            }
        }
    }

    private fun handleFillTap(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fillTap = Triple(event.getPointerId(0), event.getX(0), event.getY(0))
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> fillTap = null
            MotionEvent.ACTION_MOVE -> {
                val (id, x, y) = fillTap ?: return false
                val index = event.findPointerIndex(id)
                if (index >= 0 && hypot(event.getX(index) - x, event.getY(index) - y) > GestureArbiter.TAP_SLOP_PX) fillTap = null
                return fillTap != null
            }
            MotionEvent.ACTION_UP -> {
                val (_, x, y) = fillTap ?: return false
                fillTap = null
                session.fillAt(x, y)
                return true
            }
            MotionEvent.ACTION_CANCEL -> fillTap = null
        }
        return false
    }

    private fun zoom(): Double = viewport.camera?.zoom ?: 1.0

    private fun canvasPoint(
        event: MotionEvent,
        index: Int,
    ): Point? = viewport.screenToCanvas()?.apply(pointer(event, index))

    private fun beginSelectionGesture(
        event: MotionEvent,
        index: Int,
    ) {
        if (session.documentSession == null) return
        val point = canvasPoint(event, index) ?: return
        selectionPointer = event.getPointerId(index)
        fingers.cancelTaps()
        val crop = session.canvasTool == CanvasTool.Crop
        if (session.boxEditor.down(point, zoom()) == SelectionEditor.Result.ApplyPrevious && !crop) {
            runCommandSafely { session.applyReplacedSelection() }
        }
        session.pendingSelectionChanged()
    }

    private fun handleSelectionEvent(event: MotionEvent) {
        val pointerId = selectionPointer ?: return
        val index = event.findPointerIndex(pointerId)
        val editor = session.boxEditor
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val boxMode =
                    session.canvasTool == CanvasTool.Crop ||
                        session.selectionMode == SelectionMode.Rectangle ||
                        session.selectionMode == SelectionMode.Ellipse
                if (boxMode && constrainPointer == null) {
                    constrainPointer = event.getPointerId(event.actionIndex)
                } else if (event.pointerCount == 2 && allFingers(event) && fingers.fingersAllowed(event.eventTime)) {
                    editor.cancelGesture()
                    session.pendingSelectionChanged()
                    selectionPointer = null
                    constrainPointer = null
                    apply(fingers.beginNavigation(touchEvent(event)))
                    return
                }
                moveSelection(event, index)
            }
            MotionEvent.ACTION_MOVE -> moveSelection(event, index)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val lifted = event.getPointerId(event.actionIndex)
                if (lifted == constrainPointer) {
                    constrainPointer = null
                    moveSelection(event, index)
                }
                if (lifted != pointerId) return
                val point = canvasPoint(event, index)
                selectionPointer = null
                constrainPointer = null
                if (point != null && editor.up(point, zoom()) == SelectionEditor.Result.Apply) {
                    runCommandSafely { session.applyPendingSelection() }
                }
                session.pendingSelectionChanged()
            }
            MotionEvent.ACTION_CANCEL -> {
                editor.cancelGesture()
                selectionPointer = null
                constrainPointer = null
                session.pendingSelectionChanged()
            }
        }
    }

    private fun moveSelection(
        event: MotionEvent,
        index: Int,
    ) {
        if (index < 0) return
        val point = canvasPoint(event, index) ?: return
        session.boxEditor.move(point, zoom(), constrain = constrainPointer != null)
        session.pendingSelectionChanged()
    }

    private fun runCommandSafely(action: () -> Unit) {
        try {
            action()
        } catch (exception: LayerOperationException) {
            Log.w(TAG, "Selection shape not applied: ${exception.message}")
        }
    }

    private fun handleStrokeEvent(
        event: MotionEvent,
        actionIndex: Int,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> stroke.move(event)
            MotionEvent.ACTION_UP -> stroke.end(event)
            MotionEvent.ACTION_POINTER_UP -> if (stroke.isStrokePointer(event, actionIndex)) stroke.end(event)
            MotionEvent.ACTION_CANCEL -> stroke.cancel()
        }
        if (stroke.isActive) stroke.setFingerDown(fingerBesideStroke(event))
    }

    private fun fingerBesideStroke(event: MotionEvent): Boolean =
        (0 until event.pointerCount).any { index ->
            val lifting = event.actionMasked == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex
            !lifting && !stroke.isStrokePointer(event, index) && isFinger(event, index)
        }

    private fun trackStylusContact(
        event: MotionEvent,
        touch: TouchEvent,
        pointer: PointerTool,
    ) {
        if (!isStylus(pointer)) return
        observeStylusButton(event)
        fingers.observe(touch).forEach(::apply)
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            session.updateHover(null)
        }
        val index = event.actionIndex
        session.observeStylus(
            event.getAxisValue(MotionEvent.AXIS_TILT, index),
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, index),
            ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL,
        )
    }

    private fun isStylus(pointer: PointerTool) = pointer == PointerTool.Stylus || pointer == PointerTool.StylusEraser

    private fun allFingers(event: MotionEvent) =
        (0 until event.pointerCount).all {
            PointerTool.fromToolType(event.getToolType(it)) ==
                PointerTool.Finger
        }

    private fun pointer(
        event: MotionEvent,
        index: Int,
    ) = Point(event.getX(index).toDouble(), event.getY(index).toDouble())

    private fun touchEvent(event: MotionEvent): TouchEvent = event.toTouchEvent()

    private companion object {
        const val TAG = "CanvasSurfaceView"

        const val WHEEL_ZOOM_STEP = 1.15
        const val MILLIS_PER_SECOND = 1000f
        const val MILLIMETRES_PER_INCH = 25.4f
        const val GPU_AWAKE_MILLIS = 400
        const val GPU_AWAKE_REFRESH_MILLIS = 100L
        const val CLONE_MARKER_RADIUS_PX = 48.0

        const val PUCK_RADIUS_PX = 56.0
    }
}
