package com.mobyle.nanquim.ui.settings

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.AppCommandIds
import com.mobyle.nanquim.command.GestureCommandIds
import com.mobyle.nanquim.command.KeyboardCommandIds
import com.mobyle.nanquim.command.QuickShapeCommandIds
import com.mobyle.nanquim.settings.AppSettings
import com.mobyle.nanquim.settings.InputSettings
import com.mobyle.nanquim.settings.StorageCleaner
import com.mobyle.nanquim.settings.StorageUsage
import com.mobyle.nanquim.settings.ThemeMode
import android.text.format.Formatter
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.command.HelpCommandIds
import com.mobyle.nanquim.ui.common.CurveEditor
import com.mobyle.nanquim.ui.common.ScreenHeader
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    inputSettings: InputSettings,
    appSettings: AppSettings,
    storage: StorageCleaner,
    onCommand: (String, JsonObject) -> Unit,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var storageRefresh by remember { mutableIntStateOf(0) }
    val usage by produceState<StorageUsage?>(null, storageRefresh) { value = withContext(Dispatchers.IO) { storage.usage() } }
    var testPressure by remember { mutableStateOf<Float?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)
            HorizontalDivider()
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = CONTENT_WIDTH),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ThemeMode.System to R.string.settings_theme_system,
                        ThemeMode.Light to R.string.settings_theme_light,
                        ThemeMode.Dark to R.string.settings_theme_dark,
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = appSettings.themeMode == mode,
                            onClick = { onCommand(AppCommandIds.SETTINGS_THEME, JsonObject(mapOf("mode" to JsonPrimitive(mode.id)))) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.settings_pressure_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_pressure_help), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                    val curveLabel = stringResource(R.string.settings_pressure_curve)
                    CurveEditor(
                        points = inputSettings.pressureCurve.map { it.first.toDouble() to it.second.toDouble() },
                        description = curveLabel,
                        indicator = testPressure,
                        onChange = { points ->
                            val array = JsonArray(points.map { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) })
                            onCommand(BrushCommandIds.SETTINGS_PRESSURE_CURVE, JsonObject(mapOf("points" to array)))
                        },
                        modifier = Modifier.size(CURVE_SIZE),
                    )
                    val testLabel = stringResource(R.string.settings_pressure_test)
                    Box(
                        Modifier
                            .size(CURVE_SIZE)
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .semantics { contentDescription = testLabel }
                            .pointerInteropFilter { event ->
                                testPressure =
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> null
                                        else -> event.pressure.coerceIn(0f, 1f)
                                    }
                                true
                            },
                    ) {
                        Text(
                            testPressure?.let { stringResource(R.string.settings_pressure_value, (it * 100).roundToInt()) } ?: testLabel,
                            modifier = Modifier.align(Alignment.Center).padding(12.dp),
                        )
                    }
                }
                TextButton(
                    onClick = {
                        val linear = JsonArray(InputSettings.LINEAR.map { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) })
                        onCommand(BrushCommandIds.SETTINGS_PRESSURE_CURVE, JsonObject(mapOf("points" to linear)))
                    },
                    enabled = !inputSettings.isLinear,
                ) { Text(stringResource(R.string.settings_pressure_reset)) }
                val smoothingLabel = stringResource(R.string.settings_smoothing)
                Row {
                    Text(smoothingLabel, modifier = Modifier.weight(1f))
                    Text("${(inputSettings.globalSmoothing * 100).roundToInt()}%")
                }
                Slider(
                    value = inputSettings.globalSmoothing,
                    onValueChange = { onCommand(BrushCommandIds.SETTINGS_SMOOTHING, JsonObject(mapOf("amount" to JsonPrimitive(it)))) },
                    modifier = Modifier.semantics { contentDescription = smoothingLabel },
                )
                HorizontalDivider()
                QuickShapeSettings(inputSettings, onCommand)
                HorizontalDivider()
                Text(stringResource(R.string.gestures_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_gestures_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onCommand(GestureCommandIds.OPEN, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.settings_gestures_open))
                }
                TextButton(onClick = { onCommand(KeyboardCommandIds.OPEN, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.settings_keyboard_shortcuts_open))
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = appSettings.projectedCanvas,
                            role = Role.Switch,
                            onValueChange = {
                                onCommand(AppCommandIds.SETTINGS_PROJECTED_CANVAS, JsonObject(mapOf("enabled" to JsonPrimitive(it))))
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_projected_canvas), modifier = Modifier.weight(1f))
                    Switch(checked = appSettings.projectedCanvas, onCheckedChange = null)
                }
                Text(stringResource(R.string.settings_projected_canvas_hint), style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text(stringResource(R.string.settings_storage_title), style = MaterialTheme.typography.titleMedium)
                usage?.let { current ->
                    Text(stringResource(R.string.settings_storage_artworks, current.artworkCount, Formatter.formatShortFileSize(LocalContext.current, current.artworkBytes)))
                    Text(stringResource(R.string.settings_storage_cache, Formatter.formatShortFileSize(LocalContext.current, current.cacheBytes)))
                }
                TextButton(onClick = {
                    onCommand(AppCommandIds.SETTINGS_CLEAR_CACHE, JsonObject(emptyMap()))
                    storageRefresh++
                }) { Text(stringResource(R.string.settings_storage_clear)) }
                HorizontalDivider()
                Text(stringResource(R.string.settings_help_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onCommand(HelpCommandIds.WELCOME_SHOW, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.settings_help_welcome))
                }
                TextButton(onClick = { onCommand(HelpCommandIds.TIPS_RESET, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.settings_help_tips))
                }
                TextButton(onClick = { onCommand(HelpCommandIds.SAMPLES_INSTALL, JsonObject(emptyMap())) }) {
                    Text(stringResource(R.string.settings_help_samples))
                }
                if (stringResource(R.string.help_manual_url).isNotBlank()) {
                    TextButton(onClick = { onCommand(HelpCommandIds.MANUAL_OPEN, JsonObject(emptyMap())) }) {
                        Text(stringResource(R.string.settings_help_manual))
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { onCommand(AppCommandIds.SUPPORT_OPEN, JsonObject(emptyMap())) }) { Text(stringResource(R.string.settings_support)) }
                TextButton(onClick = { onCommand(AppCommandIds.ABOUT_OPEN, JsonObject(emptyMap())) }) { Text(stringResource(R.string.settings_about)) }
                Box(Modifier.height(8.dp))
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .combinedClickable(onClick = {}, onLongClick = onOpenDiagnostics)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun QuickShapeSettings(
    inputSettings: InputSettings,
    onCommand: (String, JsonObject) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = inputSettings.hapticsEnabled,
                role = Role.Switch,
                onValueChange = { onCommand(QuickShapeCommandIds.HAPTICS, JsonObject(mapOf("enabled" to JsonPrimitive(it)))) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_haptics), modifier = Modifier.weight(1f))
        Switch(checked = inputSettings.hapticsEnabled, onCheckedChange = null)
    }
    Text(stringResource(R.string.settings_quick_shape_title), style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = inputSettings.quickShapeEnabled,
                role = Role.Switch,
                onValueChange = { onCommand(QuickShapeCommandIds.SETTINGS, JsonObject(mapOf("enabled" to JsonPrimitive(it)))) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_quick_shape_enabled), modifier = Modifier.weight(1f))
        Switch(checked = inputSettings.quickShapeEnabled, onCheckedChange = null)
    }
    val delayLabel = stringResource(R.string.settings_quick_shape_delay)
    Row {
        Text(delayLabel, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.settings_quick_shape_delay_value, inputSettings.quickShapeDelayMillis))
    }
    val delays = InputSettings.QUICK_SHAPE_DELAYS
    Slider(
        value = inputSettings.quickShapeDelayMillis.toFloat(),
        onValueChange = { onCommand(QuickShapeCommandIds.SETTINGS, JsonObject(mapOf("delay" to JsonPrimitive(it.roundToInt())))) },
        valueRange = delays.first.toFloat()..delays.last.toFloat(),
        steps = (delays.last - delays.first) / QUICK_SHAPE_DELAY_STEP - 1,
        enabled = inputSettings.quickShapeEnabled,
        modifier = Modifier.semantics { contentDescription = delayLabel },
    )
}

private const val QUICK_SHAPE_DELAY_STEP = 100
private val CURVE_SIZE = 260.dp
private val CONTENT_WIDTH = 720.dp
