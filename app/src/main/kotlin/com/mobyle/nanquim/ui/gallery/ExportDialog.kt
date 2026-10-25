package com.mobyle.nanquim.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.ImageFormat
import com.mobyle.nanquim.gallery.AnimationExportSettings
import com.mobyle.nanquim.gallery.AnimationFormat
import com.mobyle.nanquim.gallery.ExportOptions
import com.mobyle.nanquim.gallery.InterchangeFormat
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.engine.AnimationFileWriter
import kotlin.math.roundToInt

private const val PDF_JPEG_QUALITY = 90

private enum class ExportKind(
    val label: Int,
    val imageFormat: ImageFormat? = null,
    val interchangeFormat: InterchangeFormat? = null,
    val animationFormat: AnimationFormat? = null,
) {
    Png(R.string.export_format_png, ImageFormat.Png),
    Jpeg(R.string.export_format_jpeg, ImageFormat.Jpeg),
    WebP(R.string.export_format_webp, ImageFormat.WebP),
    Tiff(R.string.export_format_tiff, interchangeFormat = InterchangeFormat.Tiff),
    Psd(R.string.export_format_psd, interchangeFormat = InterchangeFormat.Psd),
    Pdf(R.string.export_format_pdf, interchangeFormat = InterchangeFormat.Pdf),
    Nanquim(R.string.export_format_nanquim),

    Gif(R.string.export_format_gif, animationFormat = AnimationFormat.Gif),
    Apng(R.string.export_format_apng, animationFormat = AnimationFormat.Apng),
    Mp4(R.string.export_format_mp4, animationFormat = AnimationFormat.Mp4),
    WebM(R.string.export_format_webm, animationFormat = AnimationFormat.WebM),
    PngSequence(R.string.export_format_png_sequence, animationFormat = AnimationFormat.PngSequence),
}

data class ExportAnimation(
    val frames: Int,
    val framesPerSecond: Int,
)

@Composable
fun ExportDialog(
    artworkCount: Int,
    cmykProfiles: List<Pair<String, String>>,
    animation: ExportAnimation? = null,
    documentCmyk: String? = null,
    onShare: (ExportOptions) -> Unit,
    onSaveTo: (ExportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(ExportKind.Png) }
    var quality by remember { mutableFloatStateOf(ExportOptions.DEFAULT_QUALITY.toFloat()) }
    var lossless by remember { mutableStateOf(false) }
    var pdfBest by remember { mutableStateOf(true) }
    var layersAsPages by remember { mutableStateOf(false) }
    var cmykProfileId by remember { mutableStateOf(if (documentCmyk != null) null else ExportOptions.RGB_COLOR) }
    val frameCount = maxOf(animation?.frames ?: 1, 1)
    var frameRange by remember { mutableStateOf(1f..frameCount.toFloat()) }
    var framesPerSecond by remember { mutableFloatStateOf((animation?.framesPerSecond ?: AnimationSettings.DEFAULT_FRAMES_PER_SECOND).toFloat()) }
    var scalePercent by remember { mutableIntStateOf(100) }
    var includeBackground by remember { mutableStateOf(true) }
    var playOnce by remember { mutableStateOf(false) }
    var dither by remember { mutableStateOf(AnimationFileWriter.Dither.FloydSteinberg) }
    var hevc by remember { mutableStateOf(false) }
    val animationFormat = kind.animationFormat
    val format = kind.imageFormat
    val isPdf = kind == ExportKind.Pdf
    val offersCmyk = kind == ExportKind.Tiff || kind == ExportKind.Psd || isPdf
    val options =
        ExportOptions(
            imageFormat = format,
            quality = if (isPdf) PDF_JPEG_QUALITY else quality.roundToInt(),
            lossless = if (isPdf) pdfBest else lossless,
            interchangeFormat = kind.interchangeFormat,
            layersAsPages = isPdf && layersAsPages,
            cmykProfileId =
                when {
                    !offersCmyk -> ExportOptions.RGB_COLOR
                    cmykProfileId == null && documentCmyk != null -> null
                    cmykProfiles.any { it.first == cmykProfileId } -> cmykProfileId
                    else -> ExportOptions.RGB_COLOR
                },
            animationFormat = animationFormat,
            animation =
                AnimationExportSettings(
                    firstFrame = frameRange.start.roundToInt(),
                    lastFrame = frameRange.endInclusive.roundToInt(),
                    framesPerSecond = framesPerSecond.roundToInt().takeIf { it != animation?.framesPerSecond },
                    scalePercent = scalePercent,
                    plays = if (playOnce) 1 else null,
                    includeBackground = includeBackground,
                    dither = dither,
                    hevc = hevc,
                ),
        )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExportKind.entries.filter { it.animationFormat == null || animation != null }.forEach { candidate ->
                        FilterChip(
                            selected = kind == candidate,
                            onClick = { kind = candidate },
                            label = { Text(stringResource(candidate.label)) },
                        )
                    }
                }
                if (format == ImageFormat.WebP) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = lossless, onCheckedChange = { lossless = it })
                        Text(stringResource(R.string.export_lossless))
                    }
                }
                if (isPdf) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = pdfBest,
                            onClick = { pdfBest = true },
                            label = { Text(stringResource(R.string.export_pdf_best)) },
                        )
                        FilterChip(
                            selected = !pdfBest,
                            onClick = { pdfBest = false },
                            label = { Text(stringResource(R.string.export_pdf_good)) },
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = layersAsPages, onCheckedChange = { layersAsPages = it })
                        Text(stringResource(R.string.export_pdf_layers_as_pages))
                    }
                }
                if (offersCmyk) {
                    Text(stringResource(R.string.export_color_mode))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (documentCmyk != null) {
                            FilterChip(
                                selected = cmykProfileId == null,
                                onClick = { cmykProfileId = null },
                                label = { Text(stringResource(R.string.export_color_cmyk, documentCmyk)) },
                            )
                        }
                        FilterChip(
                            selected = cmykProfileId == ExportOptions.RGB_COLOR,
                            onClick = { cmykProfileId = ExportOptions.RGB_COLOR },
                            label = { Text(stringResource(R.string.export_color_rgb)) },
                        )
                        cmykProfiles.forEach { (id, name) ->
                            FilterChip(
                                selected = cmykProfileId == id,
                                onClick = { cmykProfileId = id },
                                label = { Text(stringResource(R.string.export_color_cmyk, name)) },
                            )
                        }
                    }
                    if (cmykProfiles.isEmpty() && documentCmyk == null) Text(stringResource(R.string.export_cmyk_needs_profile))
                }
                if (animationFormat != null) {
                    AnimationOptions(
                        format = animationFormat,
                        frameCount = frameCount,
                        frameRange = frameRange,
                        onFrameRange = { frameRange = it },
                        framesPerSecond = framesPerSecond,
                        onFramesPerSecond = { framesPerSecond = it },
                        scalePercent = scalePercent,
                        onScale = { scalePercent = it },
                        includeBackground = includeBackground,
                        onIncludeBackground = { includeBackground = it },
                        playOnce = playOnce,
                        onPlayOnce = { playOnce = it },
                        dither = dither,
                        onDither = { dither = it },
                        hevc = hevc,
                        onHevc = { hevc = it },
                    )
                }
                if (format == ImageFormat.Jpeg || (format == ImageFormat.WebP && !lossless)) {
                    Text(stringResource(R.string.export_quality, quality.roundToInt()))
                    Slider(value = quality, onValueChange = { quality = it }, valueRange = 1f..100f)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(options) }) { Text(stringResource(R.string.export_share)) } },
        dismissButton = {
            Row {
                if (artworkCount == 1) TextButton(onClick = { onSaveTo(options) }) { Text(stringResource(R.string.export_save_to)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) }
            }
        },
    )
}

@Composable
private fun AnimationOptions(
    format: AnimationFormat,
    frameCount: Int,
    frameRange: ClosedFloatingPointRange<Float>,
    onFrameRange: (ClosedFloatingPointRange<Float>) -> Unit,
    framesPerSecond: Float,
    onFramesPerSecond: (Float) -> Unit,
    scalePercent: Int,
    onScale: (Int) -> Unit,
    includeBackground: Boolean,
    onIncludeBackground: (Boolean) -> Unit,
    playOnce: Boolean,
    onPlayOnce: (Boolean) -> Unit,
    dither: AnimationFileWriter.Dither,
    onDither: (AnimationFileWriter.Dither) -> Unit,
    hevc: Boolean,
    onHevc: (Boolean) -> Unit,
) {
    if (frameCount > 1) {
        Text(stringResource(R.string.export_animation_frames, frameRange.start.roundToInt(), frameRange.endInclusive.roundToInt()))
        RangeSlider(
            value = frameRange,
            onValueChange = onFrameRange,
            valueRange = 1f..frameCount.toFloat(),
            steps = (frameCount - 2).coerceAtLeast(0),
        )
    }
    Text(stringResource(R.string.animation_frames_per_second, framesPerSecond.roundToInt()))
    Slider(
        value = framesPerSecond,
        onValueChange = onFramesPerSecond,
        valueRange = AnimationSettings.FRAMES_PER_SECOND.first.toFloat()..AnimationSettings.FRAMES_PER_SECOND.last.toFloat(),
    )
    Text(stringResource(R.string.export_animation_scale))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimationExportSettings.SCALES.forEach { scale ->
            FilterChip(selected = scalePercent == scale, onClick = { onScale(scale) }, label = { Text(stringResource(R.string.export_animation_percent, scale)) })
        }
    }
    val video = format == AnimationFormat.Mp4 || format == AnimationFormat.WebM
    if (!video) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = includeBackground, onCheckedChange = onIncludeBackground)
            Text(stringResource(R.string.export_animation_background))
        }
    }
    if (format == AnimationFormat.Gif || format == AnimationFormat.Apng) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = playOnce, onCheckedChange = onPlayOnce)
            Text(stringResource(R.string.export_animation_play_once))
        }
    }
    if (format == AnimationFormat.Gif) {
        Text(stringResource(R.string.export_animation_dither))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                AnimationFileWriter.Dither.None to R.string.export_dither_none,
                AnimationFileWriter.Dither.Ordered to R.string.export_dither_ordered,
                AnimationFileWriter.Dither.FloydSteinberg to R.string.export_dither_diffusion,
            ).forEach { (mode, label) ->
                FilterChip(selected = dither == mode, onClick = { onDither(mode) }, label = { Text(stringResource(label)) })
            }
        }
    }
    if (format == AnimationFormat.Mp4) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = hevc, onCheckedChange = onHevc)
            Text(stringResource(R.string.export_animation_hevc))
        }
    }
}
