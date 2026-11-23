package com.mobyle.nanquim.data.settings.repositories

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mobyle.nanquim.domain.canvas.input.GestureConfiguration
import com.mobyle.nanquim.domain.canvas.input.PalmSupport
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository.Companion.DEFAULT_UNDO_TAP_FINGERS
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository.Companion.LINEAR
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository.Companion.QUICK_SHAPE_DEFAULT_DELAY
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository.Companion.QUICK_SHAPE_DELAYS
import com.mobyle.nanquim.domain.settings.repositories.InputSettingsRepository.Companion.UNDO_TAP_FINGERS
import com.mobyle.nanquim.engine.Engine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Saves the input settings and hands the ones the engine applies to it, now and on every change. */
class InputSettingsRepositoryImpl(
    private val preferences: SharedPreferences,
    private val engine: Engine,
) : InputSettingsRepository {
    override var pressureCurve: List<Pair<Float, Float>> by mutableStateOf(load())
        private set

    override var globalSmoothing: Float by mutableStateOf(preferences.getFloat(KEY_SMOOTHING, 0f))
        private set

    override var stylusRollReported: Boolean by mutableStateOf(preferences.getBoolean(KEY_ROLL, false))
        private set

    override fun reportStylusRoll() {
        stylusRollReported = true
        preferences.edit().putBoolean(KEY_ROLL, true).apply()
        engine.setStylusRoll(true)
    }

    override var palmSupport: PalmSupport by mutableStateOf(
        PalmSupport.fromId(preferences.getString(KEY_PALM_SUPPORT, null)) ?: PalmSupport.DEFAULT,
    )
        private set

    override fun updatePalmSupport(level: PalmSupport) {
        palmSupport = level
        preferences.edit { putString(KEY_PALM_SUPPORT, level.id) }
    }

    override var gestures: GestureConfiguration by mutableStateOf(loadGestures())
        private set

    override fun updateGestures(configuration: GestureConfiguration) {
        gestures = configuration
        preferences.edit { putString(KEY_GESTURES, configuration.toJson().toString()) }
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

    override var quickShapeEnabled: Boolean by mutableStateOf(preferences.getBoolean(KEY_QUICK_SHAPE, true))
        private set

    override var hapticsEnabled: Boolean by mutableStateOf(preferences.getBoolean(KEY_HAPTICS, true))
        private set

    override fun updateHaptics(enabled: Boolean) {
        hapticsEnabled = enabled
        preferences.edit().putBoolean(KEY_HAPTICS, enabled).apply()
    }

    override var quickShapeDelayMillis: Int by mutableIntStateOf(
        preferences.getInt(KEY_QUICK_SHAPE_DELAY, QUICK_SHAPE_DEFAULT_DELAY).coerceIn(QUICK_SHAPE_DELAYS),
    )
        private set

    override fun updateQuickShape(
        enabled: Boolean,
        delayMillis: Int,
    ) {
        quickShapeEnabled = enabled
        quickShapeDelayMillis = delayMillis.coerceIn(QUICK_SHAPE_DELAYS)
        preferences
            .edit()
            .putBoolean(KEY_QUICK_SHAPE, enabled)
            .putInt(KEY_QUICK_SHAPE_DELAY, quickShapeDelayMillis)
            .apply()
    }

    init {
        engine.setPressureCurve(pressureCurve.takeUnless { isLinear })
        engine.setGlobalSmoothing(globalSmoothing)
        engine.setStylusRoll(stylusRollReported)
    }

    override fun updatePressureCurve(points: List<Pair<Float, Float>>) {
        val valid = normalized(points)
        pressureCurve = valid
        preferences.edit().putString(KEY_CURVE, valid.joinToString(";") { "${it.first},${it.second}" }).apply()
        engine.setPressureCurve(valid.takeUnless { it == LINEAR })
    }

    override fun updateGlobalSmoothing(amount: Float) {
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
        private const val KEY_QUICK_SHAPE_DELAY = "quickShapeDelay"

        private fun normalized(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
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
