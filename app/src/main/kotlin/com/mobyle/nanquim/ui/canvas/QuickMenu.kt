package com.mobyle.nanquim.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.QuickMenuCommandIds
import com.mobyle.nanquim.settings.QuickMenuPreset
import com.mobyle.nanquim.settings.QuickMenuSettings
import com.mobyle.nanquim.settings.QuickMenuSlot
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private data class QuickMenuChoice(
    val commandId: String,
    val arguments: Map<String, String> = emptyMap(),
    @StringRes val labelRes: Int,
) {
    fun matches(slot: QuickMenuSlot): Boolean =
        slot.commandId == commandId && slot.arguments == JsonObject(arguments.mapValues { JsonPrimitive(it.value) })
}

private val CHOICES =
    listOf(
        QuickMenuChoice("layer.new", labelRes = R.string.command_layer_new),
        QuickMenuChoice("layer.duplicate", labelRes = R.string.command_layer_duplicate),
        QuickMenuChoice("layer.mergeDown", labelRes = R.string.command_layer_merge_down),
        QuickMenuChoice("layer.clear", labelRes = R.string.command_layer_clear),
        QuickMenuChoice("canvas.flipHorizontal", labelRes = R.string.canvas_flip_horizontal),
        QuickMenuChoice("canvas.flipVertical", labelRes = R.string.canvas_flip_vertical),
        QuickMenuChoice("view.flipView", labelRes = R.string.command_view_flip),
        QuickMenuChoice("view.fit", labelRes = R.string.command_view_fit),
        QuickMenuChoice("view.fullScreen", labelRes = R.string.command_view_full_screen),
        QuickMenuChoice("edit.undo", labelRes = R.string.command_edit_undo),
        QuickMenuChoice("edit.redo", labelRes = R.string.command_edit_redo),
        QuickMenuChoice("edit.copy", labelRes = R.string.command_edit_copy),
        QuickMenuChoice("edit.paste", labelRes = R.string.command_edit_paste),
        QuickMenuChoice("selection.all", labelRes = R.string.command_selection_all),
        QuickMenuChoice("selection.clear", labelRes = R.string.command_selection_clear),
        QuickMenuChoice("selection.invert", labelRes = R.string.command_selection_invert),
        QuickMenuChoice("color.eyedropper.arm", labelRes = R.string.gesture_action_eyedropper),
        QuickMenuChoice("guide.toggle", labelRes = R.string.command_guide_toggle),
        QuickMenuChoice("tool.select", mapOf("tool" to "brush"), R.string.tool_brush),
        QuickMenuChoice("tool.select", mapOf("tool" to "smudge"), R.string.tool_smudge),
        QuickMenuChoice("tool.select", mapOf("tool" to "eraser"), R.string.tool_eraser),
        QuickMenuChoice("tool.select", mapOf("tool" to "selection"), R.string.tool_selection),
        QuickMenuChoice("tool.select", mapOf("tool" to "transform"), R.string.tool_transform),
        QuickMenuChoice(BrushCommandIds.SHORTCUT_TOGGLE, labelRes = R.string.command_brush_shortcut_toggle),
    )

@Composable
private fun slotName(slot: QuickMenuSlot): String =
    slot.label ?: CHOICES.firstOrNull { it.matches(slot) }?.let { stringResource(it.labelRes) } ?: slot.commandId

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickMenu(
    session: CanvasSession,
    settings: QuickMenuSettings,
    currentBrush: Pair<String, String>?,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val anchor = session.quickMenu ?: return
    var editedSlot by remember { mutableStateOf<Int?>(null) }
    var managingPresets by remember { mutableStateOf(false) }
    val preset = settings.active
    val menuLabel = stringResource(R.string.quick_menu_content_description)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onCommand(QuickMenuCommandIds.HIDE, emptyMap()) } }
            .semantics { contentDescription = menuLabel },
    ) {
        val density = LocalDensity.current
        val radius = with(density) { RADIUS.toPx() }
        val reach = radius + with(density) { SLOT_SIZE.toPx() } / 2
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val centerX = (anchor.x ?: (width / 2)).coerceIn(reach, (width - reach).coerceAtLeast(reach))
        val centerY = (anchor.y ?: (height / 2)).coerceIn(reach, (height - reach).coerceAtLeast(reach))

        fun place(
            x: Float,
            y: Float,
            size: Float,
        ) = Modifier.offset { IntOffset((x - size / 2).roundToInt(), (y - size / 2).roundToInt()) }
        val slotSize = with(density) { SLOT_SIZE.toPx() }
        preset.slots.forEachIndexed { index, slot ->
            val angle = Math.toRadians(-90.0 + index * 360.0 / QuickMenuPreset.SLOTS)
            val x = centerX + radius * cos(angle).toFloat()
            val y = centerY + radius * sin(angle).toFloat()
            val name = slot?.let { slotName(it) }
            val emptyLabel = stringResource(R.string.quick_menu_slot_empty, index + 1)
            MenuCircle(
                text = name ?: "+",
                size = SLOT_SIZE,
                description = name ?: emptyLabel,
                modifier = place(x, y, slotSize),
                onClick = {
                    if (slot == null) editedSlot = index else onCommand(QuickMenuCommandIds.RUN, mapOf("slot" to index))
                },
                onLongClick = { editedSlot = index },
            )
        }
        val centerSize = with(density) { CENTER_SIZE.toPx() }
        MenuCircle(
            text = preset.name,
            size = CENTER_SIZE,
            description = stringResource(R.string.quick_menu_presets_title),
            modifier = place(centerX, centerY, centerSize),
            highlighted = true,
            onClick = {
                onCommand(QuickMenuCommandIds.SELECT_PRESET, mapOf("preset" to (settings.activeIndex + 1) % settings.presets.size))
            },
            onLongClick = { managingPresets = true },
        )
    }
    editedSlot?.let { index ->
        SlotEditor(
            slot = preset.slots[index],
            currentBrush = currentBrush,
            onChoose = { command, arguments, label ->
                onCommand(
                    QuickMenuCommandIds.SET_SLOT,
                    mapOf(
                        "slot" to index,
                        "command" to command,
                        "arguments" to
                            arguments?.let {
                                JsonObject(
                                    it.mapValues { (_, value) ->
                                        JsonPrimitive(value)
                                    },
                                )
                            },
                        "label" to label,
                    ),
                )
                editedSlot = null
            },
            onDismiss = { editedSlot = null },
        )
    }
    if (managingPresets) {
        PresetManager(settings, onCommand, onDismiss = { managingPresets = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MenuCircle(
    text: String,
    size: androidx.compose.ui.unit.Dp,
    description: String,
    modifier: Modifier,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics { contentDescription = description }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 3)
        }
    }
}

@Composable
private fun SlotEditor(
    slot: QuickMenuSlot?,
    currentBrush: Pair<String, String>?,
    onChoose: (command: String?, arguments: Map<String, String>?, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_menu_edit_slot)) },
        text = {
            LazyColumn {
                item { ChoiceRow(stringResource(R.string.quick_menu_empty), slot == null) { onChoose(null, null, null) } }
                currentBrush?.let { (brushId, brushName) ->
                    item {
                        ChoiceRow(
                            stringResource(R.string.quick_menu_current_brush, brushName),
                            slot?.commandId == BrushCommandIds.BRUSH_SELECT,
                        ) {
                            onChoose(BrushCommandIds.BRUSH_SELECT, mapOf("brushId" to brushId, "tool" to "paint"), brushName)
                        }
                    }
                }
                items(CHOICES) { choice ->
                    ChoiceRow(stringResource(choice.labelRes), slot?.let(choice::matches) == true) {
                        onChoose(choice.commandId, choice.arguments, null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
    )
}

@Composable
private fun PresetManager(
    settings: QuickMenuSettings,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(settings.activeIndex) { mutableStateOf(settings.active.name) }
    val newName = stringResource(R.string.quick_menu_new_preset_name, settings.presets.size + 1)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quick_menu_presets_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.presets.forEachIndexed { index, preset ->
                    ChoiceRow(preset.name, index == settings.activeIndex) {
                        onCommand(QuickMenuCommandIds.SELECT_PRESET, mapOf("preset" to index))
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.quick_menu_preset_name)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        onCommand(QuickMenuCommandIds.RENAME_PRESET, mapOf("preset" to settings.activeIndex, "name" to name.trim()))
                    }, enabled = name.isNotBlank()) { Text(stringResource(R.string.quick_menu_rename_preset)) }
                    TextButton(onClick = { onCommand(QuickMenuCommandIds.ADD_PRESET, mapOf("name" to newName)) }) {
                        Text(stringResource(R.string.quick_menu_add_preset))
                    }
                    TextButton(
                        onClick = { onCommand(QuickMenuCommandIds.DELETE_PRESET, mapOf("preset" to settings.activeIndex)) },
                        enabled = settings.presets.size > 1,
                    ) { Text(stringResource(R.string.quick_menu_delete_preset)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.quick_menu_done)) } },
    )
}

private val RADIUS = 132.dp
private val SLOT_SIZE = 92.dp
private val CENTER_SIZE = 96.dp
