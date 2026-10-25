package com.mobyle.nanquim.canvas.input

import com.mobyle.nanquim.engine.Point

enum class TouchAction {
    Down,
    PointerDown,
    Move,
    PointerUp,
    Up,
    Cancel,
    HoverEnter,
    HoverMove,
    HoverExit,
}

data class TouchPointer(
    val id: Int,
    val tool: PointerTool,
    val x: Float,
    val y: Float,
    val touchMajor: Float = 0f,
    val touchMinor: Float = 0f,
)

data class TouchEvent(
    val action: TouchAction,
    val actionIndex: Int,
    val timeMillis: Long,
    val pointers: List<TouchPointer>,
    val canceled: Boolean = false,
) {
    val actionPointer: TouchPointer get() = pointers[actionIndex]

    val allFingers: Boolean get() = pointers.all { it.tool == PointerTool.Finger }
}

sealed interface FingerEffect {
    data class BeginNavigation(
        val first: Point,
        val second: Point,
        val timeMillis: Long,
    ) : FingerEffect

    data class UpdateNavigation(
        val first: Point,
        val second: Point,
    ) : FingerEffect

    data class EndNavigation(
        val timeMillis: Long,
    ) : FingerEffect

    data object CancelNavigation : FingerEffect

    data object Undo : FingerEffect

    data object Redo : FingerEffect

    data class EyedropperStart(
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : FingerEffect

    data class Gesture(
        val action: GestureAction,
        val x: Float,
        val y: Float,
        val pointerId: Int,
        val repeat: Boolean = false,
    ) : FingerEffect

    data class BeginFingerStroke(
        val pointerId: Int,
        val action: GestureAction,
    ) : FingerEffect
}

class FingerInput(
    val palmRejection: PalmRejection = PalmRejection(),
    private val undoFingers: () -> Int = { GestureArbiter.DEFAULT_UNDO_FINGERS },
    private val support: () -> PalmSupport = { PalmSupport.DEFAULT },
    private val millimetresPerPixel: Float = 0f,
    private val bindings: () -> GestureBindings = { GestureBindings.defaults(undoFingers()) },
) {
    private val gestures =
        GestureArbiter(
            repeats = { gesture -> bindings().actionOf(gesture).repeats },
            enabled = { gesture ->
                bindings().actionOf(gesture) != GestureAction.None && !(gesture == Gesture.FingerDrag && fingerStrokesBlocked())
            },
            longPressMillis = { bindings().longPressMillis },
            repeatDelayMillis = { bindings().repeatDelayMillis },
        )

    private fun fingerStrokesBlocked(): Boolean = bindings().stylusDisablesFingerStrokes && palmRejection.stylusUsed

    private var navigationStartDistance = 0.0
    private var navigationDistance = 0.0

    var navigating: Boolean = false
        private set

    private var navigationStartMillis = 0L
    private var navigationPointers: Set<Int> = emptySet()

    private var lastFinger: Triple<Int, Float, Float>? = null

    private val palms = mutableSetOf<Int>()
    private val fingerDownMillis = mutableMapOf<Int, Long>()
    private var sizesReported = false

    fun fingersAllowed(timeMillis: Long): Boolean = palmRejection.fingersAllowed(timeMillis)

    fun isPalm(pointerId: Int): Boolean = pointerId in palms

    fun observe(event: TouchEvent): List<FingerEffect> {
        val pointer = event.actionPointer
        if (pointer.tool != PointerTool.Stylus && pointer.tool != PointerTool.StylusEraser) return emptyList()
        val wasPresent = palmRejection.stylusPresent
        when (event.action) {
            TouchAction.HoverEnter, TouchAction.HoverMove -> palmRejection.onStylusHover(true, event.timeMillis)
            TouchAction.HoverExit -> palmRejection.onStylusHover(false, event.timeMillis)
            TouchAction.Down, TouchAction.PointerDown -> palmRejection.onStylusTouch(true, event.timeMillis)
            TouchAction.Up, TouchAction.PointerUp, TouchAction.Cancel -> palmRejection.onStylusTouch(false, event.timeMillis)
            TouchAction.Move -> Unit
        }
        if (wasPresent || !palmRejection.stylusPresent || support() == PalmSupport.Off) return emptyList()
        gestures.cancel()
        val recent = navigating && event.timeMillis - navigationStartMillis <= LATE_STYLUS_MILLIS
        return if (recent) listOfNotNull(cancelNavigation()) else emptyList()
    }

    fun onTouch(event: TouchEvent): List<FingerEffect> {
        val effects = mutableListOf<FingerEffect>()
        classify(event, effects)
        val actionPointer = event.actionPointer
        if (actionPointer.id in palms) {
            when (event.action) {
                TouchAction.PointerUp -> forget(actionPointer.id)
                TouchAction.Up, TouchAction.Cancel -> forgetAll()
                else -> Unit
            }
            return effects
        }
        val fingersOnly = event.pointers.filter { it.id !in palms }
        val filtered = event.copy(pointers = fingersOnly, actionIndex = fingersOnly.indexOfFirst { it.id == actionPointer.id })
        trackTaps(filtered, effects)
        when (filtered.action) {
            TouchAction.PointerDown ->
                if (filtered.pointers.size == 2 && filtered.allFingers && fingersAllowed(filtered.timeMillis)) {
                    effects += beginNavigation(filtered)
                } else {
                    cancelNavigation()?.let(effects::add)
                }
            TouchAction.Move ->
                if (navigating && filtered.pointers.size >= 2) {
                    navigationDistance = point(filtered, 0).distance(point(filtered, 1))
                    effects += FingerEffect.UpdateNavigation(point(filtered, 0), point(filtered, 1))
                }
            TouchAction.PointerUp, TouchAction.Up ->
                if (navigating) {
                    navigating = false
                    val quickPinch =
                        !event.canceled && event.timeMillis - navigationStartMillis <= QUICK_PINCH_MILLIS &&
                            navigationStartDistance > 0 && navigationDistance / navigationStartDistance <= QUICK_PINCH_RATIO &&
                            bindings().actionOf(Gesture.QuickPinch) != GestureAction.None
                    effects +=
                        when {
                            event.canceled -> FingerEffect.CancelNavigation
                            quickPinch -> FingerEffect.CancelNavigation
                            else -> FingerEffect.EndNavigation(event.timeMillis)
                        }
                    if (quickPinch) {
                        effects +=
                            FingerEffect.Gesture(
                                bindings().actionOf(Gesture.QuickPinch),
                                event.actionPointer.x,
                                event.actionPointer.y,
                                event.actionPointer.id,
                            )
                    }
                }
            TouchAction.Cancel -> cancelNavigation()?.let(effects::add)
            else -> Unit
        }
        when (event.action) {
            TouchAction.PointerUp -> forget(actionPointer.id)
            TouchAction.Up, TouchAction.Cancel -> forgetAll()
            else -> Unit
        }
        return effects
    }

    private fun classify(
        event: TouchEvent,
        effects: MutableList<FingerEffect>,
    ) {
        if (event.action == TouchAction.Down || event.action == TouchAction.PointerDown) {
            val pointer = event.actionPointer
            if (pointer.tool == PointerTool.Finger) fingerDownMillis[pointer.id] = event.timeMillis
        }
        val threshold = support().thresholdMillimetres
        for (pointer in event.pointers) {
            if (pointer.id in palms) continue
            if (pointer.tool == PointerTool.Finger && pointer.touchMajor > 0f) sizesReported = true
            val downAt = fingerDownMillis[pointer.id]
            val largeContact =
                threshold != null && sizesReported && millimetresPerPixel > 0f && pointer.tool == PointerTool.Finger &&
                    downAt != null && event.timeMillis - downAt <= CLASSIFY_MILLIS &&
                    pointer.touchMajor * millimetresPerPixel >= threshold
            if (pointer.tool != PointerTool.Palm && !largeContact) continue
            palms += pointer.id
            gestures.cancel()
            if (navigating && pointer.id in navigationPointers) cancelNavigation()?.let(effects::add)
        }
        if (event.canceled && (event.action == TouchAction.PointerUp || event.action == TouchAction.Up)) {
            gestures.cancel()
            if (navigating && event.actionPointer.id in navigationPointers) cancelNavigation()?.let(effects::add)
        }
    }

    private fun forget(pointerId: Int) {
        palms -= pointerId
        fingerDownMillis -= pointerId
    }

    private fun forgetAll() {
        palms.clear()
        fingerDownMillis.clear()
    }

    fun beginNavigation(event: TouchEvent): FingerEffect {
        navigating = true
        navigationStartMillis = event.timeMillis
        navigationPointers =
            event.pointers
                .take(2)
                .map { it.id }
                .toSet()
        navigationStartDistance = point(event, 0).distance(point(event, 1))
        navigationDistance = navigationStartDistance
        return FingerEffect.BeginNavigation(point(event, 0), point(event, 1), event.timeMillis)
    }

    fun cancelNavigation(): FingerEffect? {
        if (!navigating) return null
        navigating = false
        return FingerEffect.CancelNavigation
    }

    fun cancelTaps() = gestures.cancel()

    fun nextCheckMillis(): Long? = gestures.nextCheckMillis()

    fun onTimer(timeMillis: Long): List<FingerEffect> {
        val recognized = gestures.onTimer(timeMillis) ?: return emptyList()
        return listOfNotNull(cancelNavigation()) + effectsOf(recognized)
    }

    private fun effectsOf(recognized: GestureArbiter.Recognized): List<FingerEffect> {
        val pointerId = lastFinger?.first ?: -1
        return when (val action = bindings().actionOf(recognized.gesture)) {
            GestureAction.None -> emptyList()
            GestureAction.Undo -> listOf(FingerEffect.Undo)
            GestureAction.Redo -> listOf(FingerEffect.Redo)
            GestureAction.Paint, GestureAction.Smudge, GestureAction.Erase ->
                if (recognized.gesture == Gesture.FingerDrag &&
                    pointerId >= 0
                ) {
                    listOf(FingerEffect.BeginFingerStroke(pointerId, action))
                } else {
                    emptyList()
                }
            GestureAction.Eyedropper ->
                if (recognized.gesture == Gesture.LongPress) {
                    lastFinger?.let { (id, x, y) -> listOf(FingerEffect.EyedropperStart(id, x, y)) }.orEmpty()
                } else {
                    listOf(FingerEffect.Gesture(action, recognized.x, recognized.y, pointerId))
                }
            else -> listOf(FingerEffect.Gesture(action, recognized.x, recognized.y, pointerId, recognized.repeat))
        }
    }

    private fun trackTaps(
        event: TouchEvent,
        effects: MutableList<FingerEffect>,
    ) {
        when (event.action) {
            TouchAction.Down, TouchAction.PointerDown -> {
                val pointer = event.actionPointer
                if (pointer.tool == PointerTool.Finger && fingersAllowed(event.timeMillis)) {
                    lastFinger = Triple(pointer.id, pointer.x, pointer.y)
                    gestures.fingerDown(pointer.id, pointer.x, pointer.y, event.timeMillis)?.let { recognized(it, effects) }
                } else {
                    gestures.cancel()
                }
            }
            TouchAction.Move ->
                for (pointer in event.pointers) {
                    if (lastFinger?.first == pointer.id) lastFinger = Triple(pointer.id, pointer.x, pointer.y)
                    gestures.fingerMoved(pointer.id, pointer.x, pointer.y, event.timeMillis)?.let { recognized(it, effects) }
                }
            TouchAction.PointerUp, TouchAction.Up -> {
                val recognized = gestures.fingerUp(event.actionPointer.id, event.timeMillis)
                val stylusThere = support() != PalmSupport.Off && palmRejection.stylusPresent
                if (recognized != null && !stylusThere && !event.canceled) effects += effectsOf(recognized)
            }
            TouchAction.Cancel -> gestures.cancel()
            else -> Unit
        }
    }

    private fun recognized(
        recognized: GestureArbiter.Recognized,
        effects: MutableList<FingerEffect>,
    ) {
        cancelNavigation()?.let(effects::add)
        effects += effectsOf(recognized)
    }

    private fun point(
        event: TouchEvent,
        index: Int,
    ) = Point(event.pointers[index].x.toDouble(), event.pointers[index].y.toDouble())

    private fun Point.distance(other: Point) = kotlin.math.hypot(x - other.x, y - other.y)

    private companion object {
        const val LATE_STYLUS_MILLIS = 300L

        const val CLASSIFY_MILLIS = 100L

        const val QUICK_PINCH_MILLIS = 350L
        const val QUICK_PINCH_RATIO = 0.6
    }
}

enum class PalmSupport(
    val id: String,
    val thresholdMillimetres: Float?,
) {
    Off("off", null),
    Low("low", 20f),
    Medium("medium", 15f),
    High("high", 11f),
    ;

    companion object {
        val DEFAULT = Medium

        fun fromId(id: String?): PalmSupport? = entries.firstOrNull { it.id == id }
    }
}
