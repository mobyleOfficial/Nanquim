package com.mobyle.nanquim.settings

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.canvas.input.Gesture
import com.mobyle.nanquim.canvas.input.GestureAction
import com.mobyle.nanquim.canvas.input.GestureConfiguration
import com.mobyle.nanquim.canvas.input.PalmSupport
import com.mobyle.nanquim.engine.Engine

class InputSettings(
    private val preferences: SharedPreferences,
    private val engine: Engine,
) {
    var pressureCurve: List<Pair<Float, Float>> by mutableStateOf(load())
        private set

    var globalSmoothing: Float by mutableStateOf(preferences.getFloat(KEY_SMOOTHING, 0f))
        private set

    val isLinear: Boolean get() = pressureCurve == LINEAR

    var stylusRollReported: Boolean by mutableStateOf(preferences.getBoolean(KEY_ROLL, false))
        private set

    fun reportStylusRoll() {
        stylusRollReported = true
        preferences.edit().putBoolean(KEY_ROLL, true).apply()
        engine.setStylusRoll(true)
    }

    var palmSupport: PalmSupport by mutableStateOf(PalmSupport.fromId(preferences.getString(KEY_PALM_SUPPORT, null)) ?: PalmSupport.DEFAULT)
        private set

    fun updatePalmSupport(level: PalmSupport) {
        palmSupport = level
        preferences.edit { putString(KEY_PALM_SUPPORT, level.id) }
    }

    var gestures: GestureConfiguration by mutableStateOf(loadGestures())
        private set

    fun updateGestures(configuration: GestureConfiguration) {
        gestures = configuration
        preferences.edit { putString(KEY_GESTURES, configuration.toJson().toString()) }
    }

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

    private fun loadGestures(): GestureConfiguration {
        val stored =
            preferences.getString(KEY_GESTURES, null)?.let {
                runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
        return stored?.let(GestureConfiguration::fromJson)
            ?: GestureConfiguration.defaults(
                undoFingers = preferences.getInt(KEY_UNDO_TAP_FINGERS, DEFAULT_UNDO_TAP_FINGERS).coerceIn(UNDO_TAP_FINGERS),
                fingerPainting = preferences.getBoolean(KEY_FINGER_PAINTING, false),
            )
    }

    var quickShapeEnabled: Boolean by mutableStateOf(preferences.getBoolean(KEY_QUICK_SHAPE, true))
        private set

    var hapticsEnabled: Boolean by mutableStateOf(preferences.getBoolean(KEY_HAPTICS, true))
        private set

    fun updateHaptics(enabled: Boolean) {
        hapticsEnabled = enabled
        preferences.edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    var quickShapeDelayMillis: Int by mutableIntStateOf(
        preferences.getInt(KEY_QUICK_SHAPE_DELAY, QUICK_SHAPE_DEFAULT_DELAY).coerceIn(QUICK_SHAPE_DELAYS),
    )
        private set

    fun updateQuickShape(
        enabled: Boolean = quickShapeEnabled,
        delayMillis: Int = quickShapeDelayMillis,
    ) {
        quickShapeEnabled = enabled
        quickShapeDelayMillis = delayMillis.coerceIn(QUICK_SHAPE_DELAYS)
        preferences
            .edit()
            .putBoolean(KEY_QUICK_SHAPE, enabled)
            .putInt(KEY_QUICK_SHAPE_DELAY, quickShapeDelayMillis)
            .apply()
    }

    fun apply() {
        engine.setPressureCurve(pressureCurve.takeUnless { isLinear })
        engine.setGlobalSmoothing(globalSmoothing)
        engine.setStylusRoll(stylusRollReported)
    }

    fun updatePressureCurve(points: List<Pair<Float, Float>>) {
        val valid = normalized(points)
        pressureCurve = valid
        preferences.edit().putString(KEY_CURVE, valid.joinToString(";") { "${it.first},${it.second}" }).apply()
        engine.setPressureCurve(valid.takeUnless { it == LINEAR })
    }

    fun updateGlobalSmoothing(amount: Float) {
        globalSmoothing = amount.coerceIn(0f, 1f)
        preferences.edit().putFloat(KEY_SMOOTHING, globalSmoothing).apply()
        engine.setGlobalSmoothing(globalSmoothing)
    }

    private fun load(): List<Pair<Float, Float>> {
        val stored = preferences.getString(KEY_CURVE, null) ?: return LINEAR
        val points =
            stored.split(';').mapNotNull { pair ->
                val parts = pair.split(',')
                val x = parts.getOrNull(0)?.toFloatOrNull()
                val y = parts.getOrNull(1)?.toFloatOrNull()
                if (x != null && y != null) x to y else null
            }
        return normalized(points)
    }

    companion object {
        const val PREFERENCES_NAME = "input-settings"
        private const val KEY_CURVE = "pressureCurve"
        private const val KEY_SMOOTHING = "globalSmoothing"
        private const val KEY_ROLL = "stylusRollReported"
        private const val KEY_QUICK_SHAPE = "quickShape"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PALM_SUPPORT = "palmSupport"
        private const val KEY_UNDO_TAP_FINGERS = "undoTapFingers"
        private const val KEY_FINGER_PAINTING = "fingerPainting"
        private const val KEY_GESTURES = "gestures"

        const val DEFAULT_UNDO_TAP_FINGERS = 2
        val UNDO_TAP_FINGERS = 1..2
        private const val KEY_QUICK_SHAPE_DELAY = "quickShapeDelay"

        const val QUICK_SHAPE_DEFAULT_DELAY = 600
        val QUICK_SHAPE_DELAYS = 300..1500

        val LINEAR = listOf(0f to 0f, 1f to 1f)

        fun normalized(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val sorted =
                points
                    .map { (x, y) -> x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f) }
                    .sortedBy { it.first }
                    .distinctBy { it.first }
            if (sorted.size < 2) return LINEAR
            val first = 0f to sorted.first().second
            val last = 1f to sorted.last().second
            return listOf(first) + sorted.drop(1).dropLast(1) + listOf(last)
        }
    }
}
