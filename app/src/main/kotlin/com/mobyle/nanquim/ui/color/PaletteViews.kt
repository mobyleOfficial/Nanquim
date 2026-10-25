package com.mobyle.nanquim.ui.color

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.sizeIn
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import com.mobyle.nanquim.R
import com.mobyle.nanquim.color.PaletteEntry
import com.mobyle.nanquim.color.PaletteFileFormat
import com.mobyle.nanquim.color.PaletteLibrary
import com.mobyle.nanquim.command.PaletteCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.formats.palette.PaletteSwatch

@Composable
fun PaletteSlots(
    entry: PaletteEntry,
    current: ColorValue,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = entry.palette.columns,
    slots: Int = entry.palette.swatches.size,
) {
    val swatches = entry.palette.swatches.take(slots)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val size = min(SLOT_MAXIMUM, (maxWidth - SLOT_GAP * (columns - 1)) / columns)
        Column(verticalArrangement = Arrangement.spacedBy(SLOT_GAP)) {
            for (row in swatches.indices.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(SLOT_GAP)) {
                    for (index in row) PaletteSlot(entry.id, index, swatches[index], current, size, onCommand)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaletteSlot(
    paletteId: String,
    index: Int,
    swatch: PaletteSwatch?,
    current: ColorValue,
    size: Dp,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val currentColor = listOf(current.red, current.green, current.blue, current.alpha)
    val label =
        if (swatch == null) {
            stringResource(R.string.palette_empty_slot, index + 1)
        } else {
            val hex = ColorValue.fromRgb(swatch.color.red, swatch.color.green, swatch.color.blue).toHex()
            stringResource(R.string.palette_slot, index + 1, swatch.name.ifEmpty { hex })
        }
    Box {
        Box(
            Modifier
                .size(size)
                .border(1.dp, if (swatch == null) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.outline, shape)
                .padding(1.dp)
                .background(swatch?.color?.let { Color(it.red, it.green, it.blue, it.alpha) } ?: Color.Transparent, shape)
                .combinedClickable(
                    onClick = {
                        if (swatch == null) {
                            onCommand(
                                PaletteCommandIds.SET_SWATCH,
                                mapOf(
                                    "paletteId" to paletteId,
                                    "index" to index,
                                    "color" to currentColor,
                                ),
                            )
                        } else {
                            with(swatch.color) {
                                onCommand(
                                    ToolCommandIds.COLOR_SET_PRIMARY,
                                    mapOf(
                                        "red" to red,
                                        "green" to green,
                                        "blue" to blue,
                                        "alpha" to alpha,
                                    ),
                                )
                            }
                        }
                    },
                    onLongClick = { if (swatch != null) menuOpen = true },
                ).semantics { contentDescription = label },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palette_replace_with_current)) },
                onClick = {
                    menuOpen = false
                    onCommand(PaletteCommandIds.SET_SWATCH, mapOf("paletteId" to paletteId, "index" to index, "color" to currentColor))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.palette_remove_swatch)) },
                onClick = {
                    menuOpen = false
                    onCommand(PaletteCommandIds.SET_SWATCH, mapOf("paletteId" to paletteId, "index" to index, "color" to null))
                },
            )
        }
    }
}

@Composable
fun DefaultPaletteRow(
    library: PaletteLibrary,
    current: ColorValue,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val entry = library.defaultPalette ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(entry.palette.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        PaletteSlots(entry, current, onCommand, columns = COMPACT_SLOTS, slots = COMPACT_SLOTS)
    }
}

@Composable
fun PalettesMode(
    library: PaletteLibrary,
    current: ColorValue,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var cards by remember { mutableStateOf(true) }
    val newName = stringResource(R.string.palette_new_name)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { cards = false }) {
                Text(
                    stringResource(R.string.palette_view_compact),
                    color = if (!cards) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            TextButton(onClick = { cards = true }) {
                Text(
                    stringResource(R.string.palette_view_cards),
                    color = if (cards) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }
            Spacer(Modifier.weight(1f))
            var addMenuOpen by remember { mutableStateOf(false) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) onCommand(PaletteCommandIds.IMPORT, mapOf("uri" to uri.toString()))
                }
            val photoName = stringResource(R.string.palette_from_photo_name)
            val photoLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) onCommand(PaletteCommandIds.CREATE_FROM_IMAGE, mapOf("uri" to uri.toString(), "name" to photoName))
                }
            val context = LocalContext.current
            var cameraOpen by remember { mutableStateOf(false) }
            var cameraDenied by remember { mutableStateOf(false) }
            val permissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    cameraOpen = granted
                    cameraDenied = !granted
                }
            val cameraName = stringResource(R.string.palette_from_camera_name)
            if (cameraOpen) {
                CameraPaletteDialog(
                    onSave = { colors ->
                        cameraOpen = false
                        onCommand(
                            PaletteCommandIds.ADD,
                            mapOf("name" to cameraName, "colors" to colors.map { listOf(it.red, it.green, it.blue) }),
                        )
                    },
                    onDismiss = { cameraOpen = false },
                )
            }
            if (cameraDenied) {
                AlertDialog(
                    onDismissRequest = { cameraDenied = false },
                    text = { Text(stringResource(R.string.camera_palette_permission_denied)) },
                    confirmButton = { TextButton(onClick = { cameraDenied = false }) { Text(stringResource(R.string.import_report_ok)) } },
                )
            }
            Box {
                val addLabel = stringResource(R.string.palette_add)
                TextButton(
                    onClick = { addMenuOpen = true },
                    modifier = Modifier.semantics { contentDescription = addLabel },
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    val close = { addMenuOpen = false }
                    MenuItem(R.string.palette_new, close) { onCommand(PaletteCommandIds.CREATE, mapOf("name" to newName)) }
                    MenuItem(R.string.palette_import_files, close) { importLauncher.launch(arrayOf("*/*")) }
                    MenuItem(R.string.palette_new_from_photos, close) {
                        photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    MenuItem(R.string.palette_new_from_camera, close) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraOpen = true
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            }
        }
        if (library.entries.isEmpty()) {
            Text(stringResource(R.string.palette_none), style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.heightIn(max = PALETTE_LIST_HEIGHT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(library.entries, key = { _, entry -> entry.id }) { position, entry ->
                PaletteItem(
                    entry = entry,
                    isDefault = entry.id == library.defaultPalette?.id,
                    position = position,
                    count = library.entries.size,
                    cards = cards,
                    current = current,
                    onCommand = onCommand,
                )
            }
        }
    }
}

@Composable
private fun PaletteItem(
    entry: PaletteEntry,
    isDefault: Boolean,
    position: Int,
    count: Int,
    cards: Boolean,
    current: ColorValue,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val id = mapOf<String, Any?>("paletteId" to entry.id)
    val copyName = stringResource(R.string.palette_copy_name, entry.palette.name)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (isDefault) "★ " else "") + entry.palette.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box {
                val menuLabel = stringResource(R.string.palette_menu, entry.palette.name)
                Box(
                    Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clickable(role = Role.Button) { menuOpen = true }
                        .semantics { contentDescription = menuLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋯", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val close = { menuOpen = false }
                    MenuItem(R.string.palette_rename, close) { renaming = true }
                    MenuItem(R.string.palette_duplicate, close) { onCommand(PaletteCommandIds.DUPLICATE, id + ("name" to copyName)) }
                    MenuItem(R.string.palette_set_default, close, enabled = !isDefault) { onCommand(PaletteCommandIds.SET_DEFAULT, id) }
                    MenuItem(R.string.palette_move_up, close, enabled = position > 0) {
                        onCommand(PaletteCommandIds.MOVE, id + ("index" to position - 1))
                    }
                    MenuItem(R.string.palette_move_down, close, enabled = position < count - 1) {
                        onCommand(PaletteCommandIds.MOVE, id + ("index" to position + 1))
                    }
                    MenuItem(R.string.palette_share, close) { sharing = true }
                    MenuItem(R.string.palette_delete, close) { deleting = true }
                }
            }
        }
        if (cards) {
            PaletteSlots(entry, current, onCommand)
        } else {
            PaletteSlots(entry, current, onCommand, columns = COMPACT_SLOTS, slots = COMPACT_SLOTS)
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(entry.palette.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.palette_rename)) },
            text = { OutlinedTextField(name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    onCommand(PaletteCommandIds.RENAME, id + ("name" to name.trim()))
                }) { Text(stringResource(R.string.palette_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (sharing) {
        AlertDialog(
            onDismissRequest = { sharing = false },
            title = { Text(stringResource(R.string.palette_share_title, entry.palette.name)) },
            text = {
                Column {
                    for ((format, labelRes) in listOf(
                        PaletteFileFormat.Nanquim to R.string.palette_format_nanquim,
                        PaletteFileFormat.Ase to R.string.palette_format_ase,
                        PaletteFileFormat.Gpl to R.string.palette_format_gpl,
                    )) {
                        TextButton(onClick = {
                            sharing = false
                            onCommand(PaletteCommandIds.EXPORT, id + ("format" to format.id))
                        }) { Text(stringResource(labelRes)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sharing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.palette_delete_title, entry.palette.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onCommand(PaletteCommandIds.DELETE, id)
                }) { Text(stringResource(R.string.palette_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MenuItem(
    textRes: Int,
    close: () -> Unit,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(textRes)) },
        enabled = enabled,
        onClick = {
            close()
            action()
        },
    )
}

private const val COMPACT_SLOTS = 10
private val SLOT_MAXIMUM = 30.dp
private val SLOT_GAP = 4.dp
private val PALETTE_LIST_HEIGHT = 420.dp
