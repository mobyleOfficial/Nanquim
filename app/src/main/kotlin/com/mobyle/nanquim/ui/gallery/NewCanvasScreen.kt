package com.mobyle.nanquim.ui.gallery

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.color.ColorProfileEntry
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.isPrintProfile
import com.mobyle.nanquim.command.GalleryCommandIds
import com.mobyle.nanquim.command.MAXIMUM_CANVAS_SIDE_PX
import com.mobyle.nanquim.command.MINIMUM_LAYERS_FOR_NEW_CANVAS
import com.mobyle.nanquim.gallery.BuiltInCanvasPresets
import com.mobyle.nanquim.gallery.CanvasPreset
import com.mobyle.nanquim.gallery.CanvasSize
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.ui.common.LocalWindowLayout
import com.mobyle.nanquim.ui.common.ScreenHeader
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.maximumLayers
import com.mobyle.nanquim.formats.nanquim.CanvasUnit
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewCanvasScreen(
    gallery: Gallery,
    colorProfiles: ColorProfileLibrary,
    memoryBudgetBytes: Long,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onBack: () -> Unit,
) {
    val presets by gallery.presets.collectAsState()
    var editingPresetId by remember { mutableStateOf<String?>(null) }
    var width by remember { mutableStateOf("2048") }
    var height by remember { mutableStateOf("2048") }
    var unit by remember { mutableStateOf(CanvasUnit.Pixels) }
    var dpi by remember { mutableStateOf("132") }
    var name by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(BackgroundChoice.White) }
    var backgroundHidden by remember { mutableStateOf(false) }
    var pixelFormat by remember { mutableStateOf(PixelFormat.Rgba8) }
    var profileId by remember { mutableStateOf(ColorProfileLibrary.SRGB_ID) }

    val size =
        width.toDecimal()?.let { w -> height.toDecimal()?.let { h -> dpi.toDecimal()?.let { d -> CanvasSize(w, h, unit, d) } } }
            ?.takeIf { it.width > 0 && it.height > 0 && it.dpi > 0 }
    val layerLimit = size?.let { maximumLayers(memoryBudgetBytes, it.widthPx, it.heightPx, pixelFormat.bytesPerPixel) }
    val fits = size != null && layerLimit != null && layerLimit >= MINIMUM_LAYERS_FOR_NEW_CANVAS &&
        size.widthPx <= MAXIMUM_CANVAS_SIDE_PX && size.heightPx <= MAXIMUM_CANVAS_SIDE_PX

    fun create(canvas: CanvasSize, canvasName: String?) =
        onCommand(
            GalleryCommandIds.NEW_CANVAS,
            mapOf(
                "width" to canvas.width,
                "height" to canvas.height,
                "unit" to canvas.unit.id,
                "dpi" to canvas.dpi,
                "name" to canvasName?.takeIf { it.isNotBlank() },
                "background" to background.color,
                "backgroundHidden" to backgroundHidden,
                "bitsPerChannel" to pixelFormat.bitsPerChannel,
                "profileId" to profileId,
            ),
        )

    val customCanvas: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.new_canvas_custom), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(stringResource(R.string.new_canvas_width), width, Modifier.weight(1f)) { width = it }
                NumberField(stringResource(R.string.new_canvas_height), height, Modifier.weight(1f)) { height = it }
            }
            Text(stringResource(R.string.new_canvas_unit), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CanvasUnit.entries.forEach { candidate ->
                    FilterChip(
                        selected = unit == candidate,
                        onClick = {
                            val density = dpi.toDecimal() ?: return@FilterChip
                            size?.let { current ->
                                width = CanvasSize.fromPixels(current.widthPx, candidate, density).formatted()
                                height = CanvasSize.fromPixels(current.heightPx, candidate, density).formatted()
                            }
                            unit = candidate
                        },
                        label = { Text(unitLabel(candidate)) },
                    )
                }
            }
            NumberField(stringResource(R.string.new_canvas_dpi), dpi, Modifier.width(FIELD_WIDTH)) { dpi = it }
            Text(stringResource(R.string.new_canvas_color_depth), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelFormat.entries.forEach { candidate ->
                    FilterChip(
                        selected = pixelFormat == candidate,
                        onClick = { pixelFormat = candidate },
                        label = {
                            Text(pluralStringResource(R.plurals.new_canvas_bits, candidate.bitsPerChannel, candidate.bitsPerChannel))
                        },
                    )
                }
            }
            Text(stringResource(R.string.new_canvas_color_profile), style = MaterialTheme.typography.labelLarge)
            ProfileChoices(stringResource(R.string.new_canvas_profile_rgb), colorProfiles.documentProfiles, profileId) { profileId = it }
            ProfileChoices(stringResource(R.string.new_canvas_profile_cmyk), colorProfiles.printProfiles, profileId) { profileId = it }
            if (colorProfiles.entry(profileId)?.isPrintProfile == true) {
                Text(
                    stringResource(R.string.new_canvas_profile_cmyk_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.new_canvas_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (size != null) {
                Text(stringResource(R.string.new_canvas_pixels, size.widthPx, size.heightPx), style = MaterialTheme.typography.bodyLarge)
                layerLimit?.let { Text(stringResource(R.string.new_canvas_max_layers, it)) }
                if (!fits) Text(stringResource(R.string.new_canvas_too_large, MINIMUM_LAYERS_FOR_NEW_CANVAS), color = MaterialTheme.colorScheme.error)
            } else {
                Text(stringResource(R.string.new_canvas_invalid), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.new_canvas_background), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                BackgroundChoice.entries.forEach { choice ->
                    val choiceLabel = stringResource(choice.labelRes)
                    Box(
                        Modifier
                            .size(SWATCH_TARGET)
                            .selectable(selected = background == choice, role = Role.RadioButton) { background = choice }
                            .semantics { contentDescription = choiceLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(SWATCH_SIZE)
                                .background(Color(choice.color[0], choice.color[1], choice.color[2]), CircleShape)
                                .border(if (background == choice) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .toggleable(value = backgroundHidden, role = Role.Checkbox) { backgroundHidden = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = backgroundHidden, onCheckedChange = null)
                    Text(stringResource(R.string.new_canvas_background_hidden))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = fits, onClick = { size?.let { create(it, name) } }) { Text(stringResource(R.string.new_canvas_create)) }
                TextButton(enabled = size != null, onClick = {
                    size?.let { current ->
                        onCommand(
                            GalleryCommandIds.PRESET_SAVE,
                            mapOf(
                                "id" to editingPresetId,
                                "name" to name.ifBlank { "${current.widthPx} × ${current.heightPx}" },
                                "width" to current.width,
                                "height" to current.height,
                                "unit" to current.unit.id,
                                "dpi" to current.dpi,
                            ),
                        )
                        editingPresetId = null
                    }
                }) { Text(stringResource(if (editingPresetId != null) R.string.new_canvas_update_preset else R.string.new_canvas_save_preset)) }
            }
        }
    }
    val presetList: @Composable () -> Unit = {
        Column {
            Text(stringResource(R.string.new_canvas_presets), style = MaterialTheme.typography.titleMedium)
            presets.forEach { preset ->
                PresetRow(
                    preset = preset,
                    fits = maximumLayers(memoryBudgetBytes, preset.size.widthPx, preset.size.heightPx) >= MINIMUM_LAYERS_FOR_NEW_CANVAS,
                    onCreate = { create(preset.size, null) },
                    onEdit = {
                        editingPresetId = preset.id
                        width = preset.size.width.formatted()
                        height = preset.size.height.formatted()
                        unit = preset.size.unit
                        dpi = preset.size.dpi.formatted()
                        name = preset.name ?: ""
                    },
                    onDelete = { onCommand(GalleryCommandIds.PRESET_DELETE, mapOf("id" to preset.id)) },
                )
                HorizontalDivider()
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(stringResource(R.string.new_canvas_title), onBack)
            if (LocalWindowLayout.current.compact) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    customCanvas()
                    presetList()
                }
            } else {
                Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { customCanvas() }
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { presetList() }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileChoices(
    title: String,
    profiles: List<ColorProfileEntry>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    if (profiles.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(PROFILE_GROUP_WIDTH).padding(top = 16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { profile ->
                FilterChip(
                    selected = profile.id == selectedId,
                    onClick = { onSelect(profile.id) },
                    label = { Text(profile.name, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: CanvasPreset,
    fits: Boolean,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(enabled = fits, onClick = onCreate).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(preset.name ?: presetName(preset.builtInKey), style = MaterialTheme.typography.bodyLarge)
            val size = preset.size
            val dimensions =
                if (size.unit == CanvasUnit.Pixels) {
                    stringResource(R.string.new_canvas_pixels, size.widthPx, size.heightPx)
                } else {
                    "${size.width.formatted()} × ${size.height.formatted()} ${unitLabel(size.unit)} · ${size.dpi.formatted()} DPI"
                }
            Text(dimensions, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.new_canvas_edit)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.new_canvas_delete)) }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier,
    onChange: (String) -> Unit,
) = OutlinedTextField(
    value = value,
    onValueChange = onChange,
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    modifier = modifier,
)

@Composable
private fun unitLabel(unit: CanvasUnit): String =
    stringResource(
        when (unit) {
            CanvasUnit.Pixels -> R.string.unit_px
            CanvasUnit.Millimeters -> R.string.unit_mm
            CanvasUnit.Centimeters -> R.string.unit_cm
            CanvasUnit.Inches -> R.string.unit_in
        },
    )

@Composable
private fun presetName(key: String?): String =
    stringResource(
        when (key) {
            BuiltInCanvasPresets.SCREEN -> R.string.preset_screen
            BuiltInCanvasPresets.SQUARE -> R.string.preset_square
            BuiltInCanvasPresets.UHD -> R.string.preset_uhd
            BuiltInCanvasPresets.A4 -> R.string.preset_a4
            BuiltInCanvasPresets.A3 -> R.string.preset_a3
            BuiltInCanvasPresets.PHOTO -> R.string.preset_photo
            BuiltInCanvasPresets.LETTER -> R.string.preset_letter
            else -> R.string.preset_comic
        },
    )

private enum class BackgroundChoice(
    val color: List<Float>,
    val labelRes: Int,
) {
    White(listOf(1f, 1f, 1f), R.string.new_canvas_background_white),
    Gray(listOf(0.5f, 0.5f, 0.5f), R.string.new_canvas_background_gray),
    Black(listOf(0f, 0f, 0f), R.string.new_canvas_background_black),
}

private fun String.toDecimal(): Double? = trim().replace(',', '.').toDoubleOrNull()

private fun Double.formatted(): String =
    if (this == Math.rint(this)) {
        toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.3f", this).trimEnd('0').trimEnd('.')
    }

private val FIELD_WIDTH = 160.dp
private val SWATCH_SIZE = 36.dp
private val SWATCH_TARGET = 48.dp
private val PROFILE_GROUP_WIDTH = 48.dp
