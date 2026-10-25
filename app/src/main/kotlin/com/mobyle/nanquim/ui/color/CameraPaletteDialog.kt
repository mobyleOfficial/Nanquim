package com.mobyle.nanquim.ui.color

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobyle.nanquim.R
import com.mobyle.nanquim.color.PaletteImages
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.color.OklabColor
import com.mobyle.nanquim.document.color.PaletteExtractor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraPaletteDialog(
    onSave: (List<ColorValue>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var colors by remember { mutableStateOf(emptyList<ColorValue>()) }
    var frozen by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val paused = remember { AtomicBoolean(false) }
    val previewView =
        remember {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    LaunchedEffect(frozen) { paused.set(frozen) }
    DisposableEffect(lifecycleOwner) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val cameras = runCatching { future.get() }.getOrNull()
                if (cameras == null) {
                    failed = true
                    return@addListener
                }
                provider = cameras
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                                ).build(),
                        ).build()
                var centers = emptyList<OklabColor>()
                var lastMillis = 0L
                val mainExecutor = ContextCompat.getMainExecutor(context)
                analysis.setAnalyzer(analysisExecutor) { image ->
                    image.use {
                        val now = System.currentTimeMillis()
                        if (paused.get() || now - lastMillis < 1000L / FRAMES_PER_SECOND) return@setAnalyzer
                        lastMillis = now
                        val (width, height, pixels) = PaletteImages.cameraPixels(image)
                        val found =
                            PaletteExtractor.extract(
                                pixels,
                                width,
                                height,
                                PaletteExtractor.DEFAULT_COUNT,
                                initial = centers,
                                iterations = if (centers.isEmpty()) PaletteExtractor.ITERATIONS else LIVE_ITERATIONS,
                                maximumSide = PaletteExtractor.LIVE_SIDE,
                            )
                        centers = PaletteExtractor.centersOf(found)
                        mainExecutor.execute { if (!paused.get()) colors = found }
                    }
                }
                val bound =
                    runCatching {
                        cameras.unbindAll()
                        cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                if (bound.isFailure) failed = true
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 560.dp).padding(16.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.camera_palette_title), style = MaterialTheme.typography.titleMedium)
                if (failed) {
                    Text(stringResource(R.string.camera_palette_unavailable), color = MaterialTheme.colorScheme.error)
                } else {
                    AndroidView(
                        factory = { previewView },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(PREVIEW_ASPECT)
                                .heightIn(max = 420.dp)
                                .clipToBounds(),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (color in colors) {
                        Box(
                            Modifier
                                .size(SWATCH)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                                .padding(1.dp)
                                .background(Color(color.red, color.green, color.blue), RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { frozen = !frozen }, enabled = !failed) {
                        Text(stringResource(if (frozen) R.string.camera_palette_resume else R.string.camera_palette_capture))
                    }
                    TextButton(onClick = { onSave(colors) }, enabled = colors.isNotEmpty()) {
                        Text(stringResource(R.string.camera_palette_save))
                    }
                }
            }
        }
    }
}

private const val FRAMES_PER_SECOND = 5

private const val LIVE_ITERATIONS = 3

private val ANALYSIS_SIZE = Size(640, 480)
private const val PREVIEW_ASPECT = 4f / 3f
private val SWATCH = 22.dp
