package com.mobyle.nanquim.domain.settings.repositories

import com.mobyle.nanquim.domain.canvas.input.Gesture
import com.mobyle.nanquim.domain.canvas.input.GestureAction
import com.mobyle.nanquim.domain.canvas.input.GestureConfiguration
import com.mobyle.nanquim.domain.canvas.input.PalmSupport

/**
 * How stylus and touch input behave. Reads are snapshot state: whatever reads them in composition
 * recomposes when they change.
 */
interface InputSettingsRepository {
    /** Points from (0, input) to (1, output), sorted, the first at x 0 and the last at x 1. */
    val pressureCurve: List<Pair<Float, Float>>

    val isLinear: Boolean get() = pressureCurve == LINEAR

    fun updatePressureCurve(points: List<Pair<Float, Float>>)

    val globalSmoothing: Float

    fun updateGlobalSmoothing(amount: Float)

    /** Whether this device's stylus has ever reported a roll angle. */
    val stylusRollReported: Boolean

    fun reportStylusRoll()

    val palmSupport: PalmSupport

    fun updatePalmSupport(level: PalmSupport)

    val gestures: GestureConfiguration

    fun updateGestures(configuration: GestureConfiguration)

    val undoTapFingers: Int
        get() = if (Gesture.OneFingerTap in gestures.triggersOf(GestureAction.Undo)) 1 else DEFAULT_UNDO_TAP_FINGERS

    fun updateUndoTapFingers(fingers: Int) {
        val (tap, other) = if (fingers == 1) Gesture.OneFingerTap to Gesture.TwoFingerTap else Gesture.TwoFingerTap to Gesture.OneFingerTap
        updateGestures(gestures.withTriggers(GestureAction.Undo, gestures.triggersOf(GestureAction.Undo) - other + tap))
    }

    val fingerPainting: Boolean get() = Gesture.FingerDrag in gestures.triggersOf(GestureAction.Paint)

    fun updateFingerPainting(enabled: Boolean) {
        val triggers = gestures.triggersOf(GestureAction.Paint)
        val next = if (enabled) triggers + Gesture.FingerDrag else triggers - Gesture.FingerDrag
        updateGestures(gestures.withTriggers(GestureAction.Paint, next))
    }

    val quickShapeEnabled: Boolean

    val quickShapeDelayMillis: Int

    fun updateQuickShape(
        enabled: Boolean = quickShapeEnabled,
        delayMillis: Int = quickShapeDelayMillis,
    )

    val hapticsEnabled: Boolean

    fun updateHaptics(enabled: Boolean)

    companion object {
        const val DEFAULT_UNDO_TAP_FINGERS = 2
        val UNDO_TAP_FINGERS = 1..2

        const val QUICK_SHAPE_DEFAULT_DELAY = 600
        val QUICK_SHAPE_DELAYS = 300..1500

        val LINEAR = listOf(0f to 0f, 1f to 1f)
    }
}
