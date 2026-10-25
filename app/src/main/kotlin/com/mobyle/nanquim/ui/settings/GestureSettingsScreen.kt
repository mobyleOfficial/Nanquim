package com.mobyle.nanquim.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.input.Gesture
import com.mobyle.nanquim.canvas.input.GestureAction
import com.mobyle.nanquim.canvas.input.GestureConfiguration
import com.mobyle.nanquim.canvas.input.PalmSupport
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.GestureCommandIds
import com.mobyle.nanquim.command.QuickShapeCommandIds
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.ui.common.ScreenHeader
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GestureSettingsScreen(
    inputSettings: InputSettings,
    onCommand: (String, JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    val configuration = inputSettings.gestures
    val conflicts = configuration.conflicts
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.gestures_title), onBack = onBack)
            HorizontalDivider()
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = CONTENT_WIDTH),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (conflicts.isNotEmpty()) ConflictBanner(conflicts)
                GeneralOptions(inputSettings, configuration, onCommand)
                for (action in GestureConfiguration.ACTIONS) {
                    HorizontalDivider()
                    Text(stringResource(actionLabel(action)), style = MaterialTheme.typography.titleMedium)
                    if (action == GestureAction.ShortcutBrush) ShortcutBrushRow(configuration.shortcutBrush, onCommand)
                    val triggers = configuration.triggersOf(action)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (gesture in Gesture.entries.filter { GestureConfiguration.allows(action, it) }) {
                            val selected = gesture in triggers
                            val conflicting = selected && gesture in conflicts
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = if (selected) triggers - gesture else triggers + gesture
                                    val ids = JsonArray(next.map { JsonPrimitive(it.id) })
                                    onCommand(
                                        GestureCommandIds.SET_TRIGGERS,
                                        JsonObject(
                                            mapOf(
                                                "action" to JsonPrimitive(action.id),
                                                "triggers" to ids,
                                            ),
                                        ),
                                    )
                                },
                                label = { Text(stringResource(triggerLabel(gesture))) },
                                leadingIcon = if (conflicting) ({ Text("⚠") }) else null,
                                colors =
                                    if (conflicting) {
                                        FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                    } else {
                                        FilterChipDefaults.filterChipColors()
                                    },
                            )
                        }
                    }
                    for (gesture in triggers.filter { it in conflicts }) {
                        val others = conflicts.getValue(gesture).filter { it != action }.map { stringResource(actionLabel(it)) }
                        Text(
                            stringResource(R.string.gestures_conflict_with, stringResource(triggerLabel(gesture)), others.joinToString()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { onCommand(GestureCommandIds.RESET, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.gestures_reset))
                }
            }
        }
    }
}

@Composable
private fun ShortcutBrushRow(
    brush: GestureConfiguration.ShortcutBrush?,
    onCommand: (String, JsonObject) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            brush?.let { stringResource(R.string.gestures_shortcut_brush, it.name) }
                ?: stringResource(R.string.gestures_shortcut_brush_none),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onCommand(BrushCommandIds.SHORTCUT_SET, JsonObject(emptyMap())) }) {
            Text(stringResource(R.string.gestures_shortcut_brush_use_current))
        }
    }
}

@Composable
private fun ConflictBanner(conflicts: Map<Gesture, List<GestureAction>>) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.gestures_conflicts_title), style = MaterialTheme.typography.titleSmall)
            for ((gesture, actions) in conflicts) {
                Text(
                    stringResource(
                        R.string.gestures_conflict_line,
                        stringResource(triggerLabel(gesture)),
                        actions
                            .map {
                                stringResource(actionLabel(it))
                            }.joinToString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(stringResource(R.string.gestures_conflicts_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneralOptions(
    inputSettings: InputSettings,
    configuration: GestureConfiguration,
    onCommand: (String, JsonObject) -> Unit,
) {
    fun set(
        name: String,
        value: JsonPrimitive,
    ) = onCommand(GestureCommandIds.SET, JsonObject(mapOf(name to value)))
    Text(stringResource(R.string.settings_palm_support_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.settings_palm_support_hint), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in PalmSupport.entries) {
            FilterChip(
                selected = inputSettings.palmSupport == level,
                onClick = { onCommand(QuickShapeCommandIds.PALM_SUPPORT, JsonObject(mapOf("level" to JsonPrimitive(level.id)))) },
                label = {
                    Text(
                        stringResource(
                            when (level) {
                                PalmSupport.Off -> R.string.settings_palm_support_off
                                PalmSupport.Low -> R.string.settings_palm_support_low
                                PalmSupport.Medium -> R.string.settings_palm_support_medium
                                PalmSupport.High -> R.string.settings_palm_support_high
                            },
                        ),
                    )
                },
            )
        }
    }
    SwitchRow(R.string.gestures_rotation, configuration.rotation) { set("rotation", JsonPrimitive(it)) }
    SwitchRow(R.string.gestures_stylus_disables_finger_strokes, configuration.stylusDisablesFingerStrokes) {
        set("stylusDisablesFingerStrokes", JsonPrimitive(it))
    }
    DelaySlider(R.string.gestures_long_press_delay, configuration.longPressMillis, GestureConfiguration.LONG_PRESS_DELAYS) {
        set("longPressDelay", JsonPrimitive(it))
    }
    DelaySlider(R.string.gestures_repeat_delay, configuration.repeatDelayMillis, GestureConfiguration.REPEAT_DELAYS) {
        set("repeatDelay", JsonPrimitive(it))
    }
}

@Composable
private fun SwitchRow(
    @StringRes labelRes: Int,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun DelaySlider(
    @StringRes labelRes: Int,
    millis: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val label = stringResource(labelRes)
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.settings_quick_shape_delay_value, millis))
    }
    Slider(
        value = millis.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / DELAY_STEP - 1,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@StringRes
private fun actionLabel(action: GestureAction): Int =
    when (action) {
        GestureAction.Paint -> R.string.gesture_action_paint
        GestureAction.Smudge -> R.string.gesture_action_smudge
        GestureAction.Erase -> R.string.gesture_action_erase
        GestureAction.AssistedDrawing -> R.string.gesture_action_assisted_drawing
        GestureAction.Eyedropper -> R.string.gesture_action_eyedropper
        GestureAction.QuickMenu -> R.string.gesture_action_quick_menu
        GestureAction.FullScreen -> R.string.gesture_action_full_screen
        GestureAction.ClearLayer -> R.string.gesture_action_clear_layer
        GestureAction.CopyPasteMenu -> R.string.gesture_action_copy_paste
        GestureAction.LayerSelect -> R.string.gesture_action_layer_select
        GestureAction.Undo -> R.string.gesture_action_undo
        GestureAction.Redo -> R.string.gesture_action_redo
        GestureAction.FitCanvas -> R.string.gesture_action_fit_canvas
        GestureAction.ToggleEraser -> R.string.gesture_action_toggle_eraser
        GestureAction.ShortcutBrush -> R.string.gesture_action_shortcut_brush
        GestureAction.None -> R.string.gesture_action_none
    }

@StringRes
private fun triggerLabel(gesture: Gesture): Int =
    when (gesture) {
        Gesture.OneFingerTap -> R.string.gesture_trigger_one_finger_tap
        Gesture.TwoFingerTap -> R.string.gesture_trigger_two_finger_tap
        Gesture.ThreeFingerTap -> R.string.gesture_trigger_three_finger_tap
        Gesture.FourFingerTap -> R.string.gesture_trigger_four_finger_tap
        Gesture.LongPress -> R.string.gesture_trigger_long_press
        Gesture.ThreeFingerScrub -> R.string.gesture_trigger_three_finger_scrub
        Gesture.ThreeFingerSwipeDown -> R.string.gesture_trigger_three_finger_swipe_down
        Gesture.QuickPinch -> R.string.gesture_trigger_quick_pinch
        Gesture.FingerDrag -> R.string.gesture_trigger_finger_drag
        Gesture.StylusButton -> R.string.gesture_trigger_stylus_button
        Gesture.StylusButtonDoublePress -> R.string.gesture_trigger_stylus_button_double
        Gesture.ModifierTap -> R.string.gesture_trigger_modifier_tap
        Gesture.ModifierTouch -> R.string.gesture_trigger_modifier_touch
    }

private const val DELAY_STEP = 50
private val CONTENT_WIDTH = 720.dp
