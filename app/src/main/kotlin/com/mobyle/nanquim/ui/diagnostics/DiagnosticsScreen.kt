package com.mobyle.nanquim.ui.diagnostics

import androidx.compose.foundation.layout.heightIn
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobyle.nanquim.R
import com.mobyle.nanquim.diagnostics.DiagnosticsCollector
import com.mobyle.nanquim.diagnostics.DiagnosticsReport
import com.mobyle.nanquim.diagnostics.StylusObservation
import com.mobyle.nanquim.diagnostics.StylusObservationRecorder
import com.mobyle.nanquim.diagnostics.StylusProbeView
import com.mobyle.nanquim.diagnostics.StylusSample
import com.mobyle.nanquim.ui.common.ScreenHeader
import com.mobyle.nanquim.engine.Renderer
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    collector: DiagnosticsCollector,
    recorder: StylusObservationRecorder,
    onBack: () -> Unit,
    onOpenLatencyTest: () -> Unit,
    onOpenShapeTraces: () -> Unit,
    onOpenTouchSessions: () -> Unit,
    onExport: (Uri) -> Unit,
    selectedRenderer: Renderer,
    activeRenderer: Renderer?,
    rendererFellBack: Boolean,
    onSelectRenderer: (Renderer) -> Unit,
) {
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var latestSample by remember { mutableStateOf<StylusSample?>(null) }
    var observation by remember { mutableStateOf(recorder.snapshot()) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) onExport(uri)
        }
    LaunchedEffect(collector) { report = collector.collect(recorder.snapshot()) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.diagnostics_title), onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch(DEFAULT_EXPORT_FILE_NAME) }, enabled = report != null) {
                        Text(stringResource(R.string.diagnostics_export))
                    }
                    OutlinedButton(onClick = onOpenLatencyTest) { Text(stringResource(R.string.diagnostics_latency_test)) }
                    OutlinedButton(onClick = onOpenShapeTraces) { Text(stringResource(R.string.diagnostics_shape_traces)) }
                    OutlinedButton(onClick = onOpenTouchSessions) { Text(stringResource(R.string.diagnostics_touch_sessions)) }
                }
                RendererSection(selectedRenderer, activeRenderer, rendererFellBack, onSelectRenderer)
                val currentReport = report
                if (currentReport == null) {
                    Text(stringResource(R.string.diagnostics_loading))
                } else {
                    DeviceSection(currentReport)
                    GraphicsSection(currentReport)
                    DisplaysSection(currentReport)
                    StylusDevicesSection(currentReport)
                }
                StylusProbeSection(
                    recorder = recorder,
                    latestSample = latestSample,
                    observation = observation,
                    onSample = { sample ->
                        latestSample = sample
                        observation = recorder.snapshot()
                    },
                )
            }
        }
    }
}

@Composable
private fun RendererSection(
    selected: Renderer,
    active: Renderer?,
    fellBack: Boolean,
    onSelect: (Renderer) -> Unit,
) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_renderer)) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (renderer in Renderer.entries) {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .selectable(selected = renderer == selected, role = Role.RadioButton, onClick = { onSelect(renderer) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = renderer == selected, onClick = null)
                    Text(rendererName(renderer), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        DiagnosticsRow(stringResource(R.string.diagnostics_renderer_active), active?.let { rendererName(it) } ?: "—")
        if (fellBack) {
            Text(stringResource(R.string.renderer_fallback_notice), color = MaterialTheme.colorScheme.error)
        } else if (active != null && active != selected) {
            Text(stringResource(R.string.diagnostics_renderer_restart), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun rendererName(renderer: Renderer): String =
    stringResource(if (renderer == Renderer.Vulkan) R.string.diagnostics_renderer_vulkan else R.string.diagnostics_renderer_gles)

@Composable
private fun DeviceSection(report: DiagnosticsReport) {
    val context = LocalContext.current
    val device = report.device
    DiagnosticsSection(stringResource(R.string.diagnostics_section_device)) {
        DiagnosticsRow(stringResource(R.string.diagnostics_model), "${device.manufacturer} ${device.model}")
        DiagnosticsRow(stringResource(R.string.diagnostics_android_version), "${device.androidRelease} (API ${device.sdkInt})")
        DiagnosticsRow(
            stringResource(R.string.diagnostics_soc),
            listOfNotNull(device.socManufacturer, device.socModel).joinToString(" ").ifEmpty { device.hardware },
        )
        DiagnosticsRow(stringResource(R.string.diagnostics_total_ram), Formatter.formatShortFileSize(context, device.totalRamBytes))
        DiagnosticsRow(stringResource(R.string.diagnostics_available_ram), Formatter.formatShortFileSize(context, device.availableRamBytes))
    }
}

@Composable
private fun GraphicsSection(report: DiagnosticsReport) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_graphics)) {
        val gpu = report.gpu
        if (gpu == null) {
            Text(stringResource(R.string.diagnostics_gpu_unavailable), color = MaterialTheme.colorScheme.error)
        } else {
            DiagnosticsRow("GL_VENDOR", gpu.vendor)
            DiagnosticsRow("GL_RENDERER", gpu.renderer)
            DiagnosticsRow("GL_VERSION", gpu.version)
            DiagnosticsRow("GL_SHADING_LANGUAGE_VERSION", gpu.shadingLanguageVersion)
            DiagnosticsRow("GL_MAX_TEXTURE_SIZE", gpu.maxTextureSize.toString())
            DiagnosticsRow("EXT_color_buffer_half_float", yesNo(gpu.supportsColorBufferHalfFloat))
            DiagnosticsRow("GL_PROGRAM_BINARY_FORMATS", gpu.programBinaryFormats.joinToString { "0x%X".format(it) }.ifEmpty { "—" })
            DiagnosticsRow("EGL_VERSION", gpu.eglVersion)
            DiagnosticsRow(stringResource(R.string.diagnostics_gl_extensions, gpu.extensions.size), gpu.extensions.joinToString(" "))
            DiagnosticsRow(stringResource(R.string.diagnostics_egl_extensions, gpu.eglExtensions.size), gpu.eglExtensions.joinToString(" "))
        }
        DiagnosticsRow(
            stringResource(R.string.diagnostics_vulkan),
            report.vulkan.apiVersion ?: stringResource(R.string.diagnostics_not_supported),
        )
    }
}

@Composable
private fun DisplaysSection(report: DiagnosticsReport) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_displays)) {
        report.displays.forEach { display ->
            DiagnosticsRow(
                "${display.displayId}: ${display.name}",
                "${display.currentWidthPx}×${display.currentHeightPx}, ${display.densityDpi} dpi, ${formatHertz(
                    display.currentRefreshRateHz,
                )}",
            )
            DiagnosticsRow(
                stringResource(R.string.diagnostics_supported_modes),
                display.supportedModes.joinToString { "${it.widthPx}×${it.heightPx} @ ${formatHertz(it.refreshRateHz)}" },
            )
        }
    }
}

@Composable
private fun StylusDevicesSection(report: DiagnosticsReport) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_stylus_devices)) {
        if (report.stylusDevices.isEmpty()) Text(stringResource(R.string.diagnostics_no_stylus_device))
        report.stylusDevices.forEach { device ->
            DiagnosticsRow(stringResource(R.string.diagnostics_device_name), device.name)
            DiagnosticsRow(stringResource(R.string.diagnostics_declares_pressure), yesNo(device.declaresPressure))
            DiagnosticsRow(stringResource(R.string.diagnostics_declares_tilt), yesNo(device.declaresTilt))
            DiagnosticsRow(stringResource(R.string.diagnostics_declares_orientation), yesNo(device.declaresOrientation))
            DiagnosticsRow(stringResource(R.string.diagnostics_declares_distance), yesNo(device.declaresDistance))
        }
    }
}

@Composable
private fun StylusProbeSection(
    recorder: StylusObservationRecorder,
    latestSample: StylusSample?,
    observation: StylusObservation,
    onSample: (StylusSample) -> Unit,
) {
    DiagnosticsSection(stringResource(R.string.diagnostics_section_stylus_probe)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).border(1.dp, MaterialTheme.colorScheme.outline),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.diagnostics_stylus_probe_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            AndroidView(factory = { context -> StylusProbeView(context, recorder, onSample) }, modifier = Modifier.fillMaxSize())
        }
        latestSample?.let { sample ->
            DiagnosticsRow(stringResource(R.string.diagnostics_tool_type), sample.toolTypeName)
            DiagnosticsRow("x, y", "%.1f, %.1f".format(Locale.ROOT, sample.xPx, sample.yPx))
            DiagnosticsRow("AXIS_PRESSURE", "%.3f".format(Locale.ROOT, sample.pressure))
            DiagnosticsRow("AXIS_TILT", "%.3f rad".format(Locale.ROOT, sample.tiltRadians))
            DiagnosticsRow("AXIS_ORIENTATION", "%.3f rad".format(Locale.ROOT, sample.orientationRadians))
            DiagnosticsRow("AXIS_DISTANCE", "%.3f".format(Locale.ROOT, sample.distance))
            DiagnosticsRow(stringResource(R.string.diagnostics_button_state), sample.buttonState.toString())
        }
        DiagnosticsRow(stringResource(R.string.diagnostics_samples_recorded), observation.samplesRecorded.toString())
        DiagnosticsRow(stringResource(R.string.diagnostics_pressure_varies), yesNo(observation.pressureVaries))
        DiagnosticsRow(stringResource(R.string.diagnostics_tilt_reported), yesNo(observation.tiltReported))
        DiagnosticsRow(stringResource(R.string.diagnostics_orientation_varies), yesNo(observation.orientationVaries))
        DiagnosticsRow(stringResource(R.string.diagnostics_hover_reported), yesNo(observation.hoverReported))
        DiagnosticsRow(
            stringResource(R.string.diagnostics_sample_rate),
            observation.averageContactSampleRateHz?.let { formatHertz(it.toFloat()) } ?: "—",
        )
    }
}

@Composable
private fun DiagnosticsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun DiagnosticsRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun yesNo(flag: Boolean): String = stringResource(if (flag) R.string.diagnostics_yes else R.string.diagnostics_no)

private fun formatHertz(hertz: Float): String = "%.1f Hz".format(Locale.getDefault(), hertz)

private const val DEFAULT_EXPORT_FILE_NAME = "nanquim-diagnostics.json"
