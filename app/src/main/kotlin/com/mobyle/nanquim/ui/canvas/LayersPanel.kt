package com.mobyle.nanquim.ui.canvas

import androidx.compose.foundation.layout.sizeIn
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ui.common.onSecondaryClick
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.DocumentCommandIds
import com.mobyle.nanquim.command.TextCommandIds
import com.mobyle.nanquim.command.VideoCommandIds
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.RasterLayer
import kotlin.math.abs
import kotlin.math.roundToInt

private data class LayerRow(
    val node: LayerNode,
    val depth: Int,
    val parentId: Int?,
)

private fun rowsOf(state: DocumentState): List<LayerRow> {
    val rows = mutableListOf<LayerRow>()

    fun visit(
        nodes: List<LayerNode>,
        depth: Int,
        parentId: Int?,
    ) {
        for (node in nodes.asReversed()) {
            rows += LayerRow(node, depth, parentId)
            if (node is LayerGroup && !node.collapsed) visit(node.children, depth + 1, node.id)
        }
    }
    visit(state.layers, 0, null)
    return rows
}

@Composable
fun LayersPanel(
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = session.document ?: return
    val document = session.documentSession ?: return
    @Suppress("UNUSED_VARIABLE")
    val version = session.documentVersion
    val rows = rowsOf(state)
    var renaming by remember { mutableStateOf<LayerNode?>(null) }
    var blendFor by remember { mutableStateOf<LayerNode?>(null) }
    Surface(modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, shadowElevation = 6.dp) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.layers_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (document.selectedIds.size > 1) {
                    TextButton(onClick = { onCommand(DocumentCommandIds.LAYER_GROUP, emptyMap()) }) { Text(stringResource(R.string.layers_group)) }
                    TextButton(onClick = { onCommand(DocumentCommandIds.LAYER_MERGE, emptyMap()) }) { Text(stringResource(R.string.layers_merge)) }
                    TextButton(onClick = { onCommand(DocumentCommandIds.LAYER_DELETE, emptyMap()) }) { Text(stringResource(R.string.layers_delete)) }
                }
                val addLabel = stringResource(R.string.command_layer_new)
                TextButton(
                    onClick = { onCommand(DocumentCommandIds.LAYER_NEW, emptyMap()) },
                    enabled = !document.layerLimitReached,
                    modifier = Modifier.semantics { contentDescription = addLabel },
                ) { Text("+", style = MaterialTheme.typography.titleLarge) }
            }
            if (document.layerLimitReached) {
                Text(
                    stringResource(R.string.layers_limit_reached),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
            LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                items(rows, key = { it.node.id }) { row ->
                    LayerRowView(
                        row = row,
                        session = session,
                        rows = rows,
                        rowHeightPx = rowHeightPx,
                        onCommand = onCommand,
                        onRename = { renaming = it },
                        onBlend = { blendFor = it },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            BackgroundRow(state, session, onCommand)
        }
    }
    renaming?.let { node -> RenameDialog(node, onDismiss = { renaming = null }, onCommand = onCommand) }
    blendFor?.let { node -> BlendDialog(node, state, onDismiss = { blendFor = null }, onCommand = onCommand) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerRowView(
    row: LayerRow,
    session: CanvasSession,
    rows: List<LayerRow>,
    rowHeightPx: Float,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onRename: (LayerNode) -> Unit,
    onBlend: (LayerNode) -> Unit,
) {
    val document = session.documentSession ?: return
    val node = row.node
    val active = document.state.activeLayerId == node.id
    val selected = node.id in document.selectedIds
    var menuOpen by remember { mutableStateOf(false) }
    var quickActions by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val background =
        when {
            active -> MaterialTheme.colorScheme.primaryContainer
            selected -> MaterialTheme.colorScheme.secondaryContainer
            else -> Color.Transparent
        }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .graphicsLayer { if (dragOffset != 0f) shadowElevation = 8f }
                .background(background)
                .pointerInput(node.id) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onHorizontalDrag = { _, amount -> travel += amount },
                        onDragEnd = {
                            when {
                                travel > SWIPE_THRESHOLD_PX -> onCommand(DocumentCommandIds.LAYER_TOGGLE_SELECTED, mapOf("layerId" to node.id))
                                travel < -SWIPE_THRESHOLD_PX -> quickActions = true
                            }
                        },
                    )
                }.pointerInput(node.id, rows) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, amount ->
                            change.consume()
                            dragOffset += amount.y
                        },
                        onDragEnd = {
                            moveRow(row, rows, dragOffset / rowHeightPx, onCommand)
                            dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    )
                }.onSecondaryClick {
                    if (!active) onCommand(DocumentCommandIds.LAYER_SELECT, mapOf("layerId" to node.id))
                    menuOpen = true
                }.clickable {
                    if (active) {
                        menuOpen = true
                    } else {
                        onCommand(DocumentCommandIds.LAYER_SELECT, mapOf("layerId" to node.id))
                    }
                }.padding(start = 8.dp + INDENT * row.depth, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (node is RasterLayer && node.clipping) {
                Text("↳", style = MaterialTheme.typography.bodyLarge)
            }
            if (node is LayerGroup) {
                val label = stringResource(if (node.collapsed) R.string.layers_expand_group else R.string.layers_collapse_group)
                Text(
                    if (node.collapsed) "▸" else "▾",
                    modifier =
                        Modifier
                            .clickable { onCommand(DocumentCommandIds.LAYER_SET_COLLAPSED, mapOf("layerId" to node.id, "collapsed" to !node.collapsed)) }
                            .padding(6.dp)
                            .semantics { contentDescription = label },
                )
            } else {
                Thumbnail(session, node.id, mask = false, highlighted = active && !document.maskSelected)
            }
            val layerMask = (node as? RasterLayer)?.mask
            if (layerMask != null) {
                val maskLabel = stringResource(R.string.layers_select_mask)
                Box(
                    Modifier
                        .clickable { onCommand(DocumentCommandIds.LAYER_SELECT, mapOf("layerId" to node.id, "mask" to true)) }
                        .semantics { contentDescription = maskLabel },
                ) {
                    Thumbnail(session, layerMask.id, mask = true, highlighted = active && document.maskSelected, disabled = !layerMask.enabled)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                val flags =
                    buildList {
                        if (node.locked) add(stringResource(R.string.layers_flag_locked))
                        if (node is RasterLayer && node.alphaLock) add(stringResource(R.string.layers_flag_alpha_lock))
                        if (node is RasterLayer && node.assisted) add(stringResource(R.string.layers_flag_assisted))
                        if (node is RasterLayer && node.isReference) add(stringResource(R.string.layers_flag_reference))
                        if (node is RasterLayer && node.isPrivate) add(stringResource(R.string.layers_private_marker))
                        if (session.documentSession?.soloLayerId == node.id) add(stringResource(R.string.layers_flag_solo))
                    }
                if (flags.isNotEmpty()) {
                    Text(flags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val blendLabel = stringResource(R.string.layers_blend_and_opacity, stringResource(blendNameRes(node.blend)))
            Box(
                Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable { onBlend(node) }
                    .semantics(mergeDescendants = true) { contentDescription = blendLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(node.blend.abbreviation, style = MaterialTheme.typography.labelLarge)
            }
            val visibilityLabel = stringResource(R.string.layers_visibility)
            Box(
                Modifier
                    .combinedClickable(
                        onClick = { onCommand(DocumentCommandIds.LAYER_SET_HIDDEN, mapOf("layerId" to node.id, "hidden" to !node.hidden)) },
                        onLongClick = {
                            if (node is RasterLayer) {
                                val solo = if (session.documentSession?.soloLayerId == node.id) null else node.id
                                onCommand(DocumentCommandIds.LAYER_SOLO, mapOf("layerId" to solo))
                            }
                        },
                    ).semantics { contentDescription = visibilityLabel },
            ) {
                Checkbox(checked = !node.hidden, onCheckedChange = null, modifier = Modifier.padding(8.dp))
            }
        }
        if (quickActions) {
            Row(
                Modifier.align(Alignment.CenterEnd).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            ) {
                TextButton(onClick = {
                    quickActions = false
                    onCommand(DocumentCommandIds.LAYER_LOCK, mapOf("layerId" to node.id, "locked" to !node.locked))
                }) { Text(stringResource(if (node.locked) R.string.layers_unlock else R.string.layers_lock)) }
                TextButton(onClick = {
                    quickActions = false
                    onCommand(DocumentCommandIds.LAYER_DUPLICATE, mapOf("layerId" to node.id))
                }) { Text(stringResource(R.string.layers_duplicate)) }
                TextButton(onClick = {
                    quickActions = false
                    onCommand(DocumentCommandIds.LAYER_DELETE, mapOf("layerIds" to listOf(node.id)))
                }) { Text(stringResource(R.string.layers_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { quickActions = false }) { Text("×") }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            fun item(
                @StringRes labelRes: Int,
                checked: Boolean? = null,
                command: String,
                arguments: Map<String, Any?>,
            ): @Composable () -> Unit =
                {
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        trailingIcon = checked?.let { value -> @Composable { Checkbox(checked = value, onCheckedChange = null) } },
                        onClick = {
                            menuOpen = false
                            onCommand(command, arguments)
                        },
                    )
                }
            DropdownMenuItem(text = { Text(stringResource(R.string.layers_rename)) }, onClick = {
                menuOpen = false
                onRename(node)
            })
            val id = mapOf("layerId" to node.id)
            when (node) {
                is RasterLayer -> {
                    if (node.text != null) {
                        item(R.string.layers_edit_text, command = TextCommandIds.EDIT, arguments = id)()
                        item(R.string.layers_rasterize_text, command = TextCommandIds.RASTERIZE, arguments = id)()
                    }
                    if (node.video != null) item(R.string.layers_rasterize_video, command = VideoCommandIds.RASTERIZE, arguments = id)()
                    item(R.string.layers_fill, command = DocumentCommandIds.LAYER_FILL, arguments = id)()
                    item(R.string.layers_clear, command = DocumentCommandIds.LAYER_CLEAR, arguments = id)()
                    item(R.string.layers_alpha_lock, node.alphaLock, DocumentCommandIds.LAYER_ALPHA_LOCK, id + ("enabled" to !node.alphaLock))()
                    item(R.string.layers_drawing_assist, node.assisted, DocumentCommandIds.LAYER_SET_ASSISTED, id + ("enabled" to !node.assisted))()
                    val mask = node.mask
                    if (mask == null) {
                        item(R.string.layers_mask_add, command = DocumentCommandIds.LAYER_MASK_ADD, arguments = id)()
                    } else {
                        item(R.string.layers_mask_enabled, mask.enabled, DocumentCommandIds.LAYER_MASK_SET_ENABLED, id + ("enabled" to !mask.enabled))()
                        item(R.string.layers_mask_inverted, mask.inverted, DocumentCommandIds.LAYER_MASK_SET_INVERTED, id + ("inverted" to !mask.inverted))()
                        item(R.string.layers_mask_apply, command = DocumentCommandIds.LAYER_MASK_APPLY, arguments = id)()
                        item(R.string.layers_mask_delete, command = DocumentCommandIds.LAYER_MASK_DELETE, arguments = id)()
                    }
                    item(R.string.layers_clipping, node.clipping, DocumentCommandIds.LAYER_CLIPPING, id + ("enabled" to !node.clipping))()
                    item(R.string.layers_invert, command = DocumentCommandIds.LAYER_INVERT, arguments = id)()
                    item(R.string.layers_reference, node.isReference, DocumentCommandIds.LAYER_SET_REFERENCE, id + ("enabled" to !node.isReference))()
                    item(R.string.layers_private, node.isPrivate, DocumentCommandIds.LAYER_SET_PRIVATE, id + ("enabled" to !node.isPrivate))()
                    item(R.string.layers_merge_down, command = DocumentCommandIds.LAYER_MERGE_DOWN, arguments = id)()
                    item(R.string.layers_combine_down, command = DocumentCommandIds.LAYER_COMBINE_DOWN, arguments = id)()
                }
                is LayerGroup -> {
                    item(R.string.layers_flatten_group, command = DocumentCommandIds.LAYER_FLATTEN, arguments = mapOf("groupId" to node.id))()
                    item(R.string.layers_ungroup, command = DocumentCommandIds.LAYER_UNGROUP, arguments = id)()
                }
            }
            item(R.string.layers_lock, node.locked, DocumentCommandIds.LAYER_LOCK, id + ("locked" to !node.locked))()
            item(R.string.layers_duplicate, command = DocumentCommandIds.LAYER_DUPLICATE, arguments = id)()
            item(R.string.layers_delete, command = DocumentCommandIds.LAYER_DELETE, arguments = mapOf("layerIds" to listOf(node.id)))()
            item(R.string.layers_flatten_document, command = DocumentCommandIds.LAYER_FLATTEN, arguments = emptyMap())()
        }
    }
}

private fun moveRow(
    row: LayerRow,
    rows: List<LayerRow>,
    rowsMoved: Float,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val from = rows.indexOf(row)
    val targetPosition = from + rowsMoved
    val targetIndex = targetPosition.roundToInt().coerceIn(0, rows.lastIndex)
    if (targetIndex == from) return
    val target = rows[targetIndex]
    if (target.node.id == row.node.id || isInside(target, row.node)) return
    val fraction = abs(targetPosition - targetIndex)
    if (target.node is LayerGroup && fraction < GROUP_DROP_ZONE) {
        val childCount = (target.node as LayerGroup).children.count { it.id != row.node.id }
        onCommand(DocumentCommandIds.LAYER_MOVE, mapOf("layerId" to row.node.id, "parentId" to target.node.id, "index" to childCount))
        return
    }
    val siblings = rows.filter { it.parentId == target.parentId && it.node.id != row.node.id }.map { it.node.id }.asReversed()
    val targetModelIndex = siblings.indexOf(target.node.id)
    val index = if (rowsMoved > 0) targetModelIndex else targetModelIndex + 1
    onCommand(DocumentCommandIds.LAYER_MOVE, mapOf("layerId" to row.node.id, "parentId" to target.parentId, "index" to index))
}

private fun isInside(
    row: LayerRow,
    node: LayerNode,
): Boolean = node is LayerGroup && node.children.any { it.id == row.node.id || (it is LayerGroup && isInside(row, it)) }

@Composable
private fun Thumbnail(
    session: CanvasSession,
    surfaceId: Int,
    mask: Boolean,
    highlighted: Boolean,
    disabled: Boolean = false,
) {
    val image = session.layerThumbnails.images[surfaceId]
    val outline = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(4.dp))
            .border(if (highlighted) 2.dp else 1.dp, outline, RoundedCornerShape(4.dp))
            .background(if (mask) Color.White else CHECKER_LIGHT),
    ) {
        if (!mask) Checkerboard()
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(THUMBNAIL_SIZE), alpha = if (disabled) 0.4f else 1f)
        }
    }
}

@Composable
private fun Checkerboard() {
    Column {
        repeat(CHECKER_CELLS) { row ->
            Row {
                repeat(CHECKER_CELLS) { column ->
                    Box(Modifier.size(THUMBNAIL_SIZE / CHECKER_CELLS).background(if ((row + column) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK))
                }
            }
        }
    }
}

@Composable
private fun BackgroundRow(
    state: DocumentState,
    session: CanvasSession,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val background = state.background
    val primary = session.primaryColor
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.layers_background), modifier = Modifier.weight(1f))
        val swatchLabel = stringResource(R.string.layers_background_use_color)
        Box(
            Modifier
                .size(32.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                .padding(3.dp)
                .background(Color(background.color.red, background.color.green, background.color.blue), RoundedCornerShape(4.dp))
                .clickable {
                    onCommand(
                        DocumentCommandIds.CANVAS_SET_BACKGROUND,
                        mapOf("red" to primary.red, "green" to primary.green, "blue" to primary.blue, "hidden" to background.hidden),
                    )
                }.semantics { contentDescription = swatchLabel },
        )
        Spacer(Modifier.width(8.dp))
        val visibilityLabel = stringResource(R.string.layers_visibility)
        Checkbox(
            checked = !background.hidden,
            onCheckedChange = { visible ->
                onCommand(
                    DocumentCommandIds.CANVAS_SET_BACKGROUND,
                    mapOf("red" to background.color.red, "green" to background.color.green, "blue" to background.color.blue, "hidden" to !visible),
                )
            },
            modifier = Modifier.semantics { contentDescription = visibilityLabel },
        )
    }
}

@Composable
private fun RenameDialog(
    node: LayerNode,
    onDismiss: () -> Unit,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    var name by remember(node.id) { mutableStateOf(node.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layers_rename)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onCommand(DocumentCommandIds.LAYER_RENAME, mapOf("layerId" to node.id, "name" to name))
                onDismiss()
            }) { Text(stringResource(R.string.dialog_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun BlendDialog(
    node: LayerNode,
    state: DocumentState,
    onDismiss: () -> Unit,
    onCommand: (String, Map<String, Any?>) -> Unit,
) {
    val current = state.find(node.id) ?: return
    var opacity by remember(node.id) { mutableFloatStateOf(current.opacity) }
    val modes = if (current is LayerGroup) BlendMode.GROUP_MODES else BlendMode.LAYER_MODES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.name) },
        text = {
            Column {
                Text(stringResource(R.string.layers_opacity_value, (opacity * 100).roundToInt()))
                Slider(
                    value = opacity,
                    onValueChange = {
                        opacity = it
                        onCommand(DocumentCommandIds.LAYER_SET_OPACITY, mapOf("layerId" to node.id, "opacity" to it, "final" to false))
                    },
                    onValueChangeFinished = {
                        onCommand(DocumentCommandIds.LAYER_SET_OPACITY, mapOf("layerId" to node.id, "opacity" to opacity, "final" to true))
                    },
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    for (mode in modes) {
                        val selected = mode == current.blend
                        Text(
                            stringResource(blendNameRes(mode)),
                            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onCommand(DocumentCommandIds.LAYER_SET_BLEND, mapOf("layerId" to node.id, "blend" to mode.id)) }
                                    .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_done)) } },
    )
}

@StringRes
fun blendNameRes(mode: BlendMode): Int =
    when (mode) {
        BlendMode.Normal -> R.string.blend_normal
        BlendMode.Multiply -> R.string.blend_multiply
        BlendMode.Screen -> R.string.blend_screen
        BlendMode.Overlay -> R.string.blend_overlay
        BlendMode.Darken -> R.string.blend_darken
        BlendMode.Lighten -> R.string.blend_lighten
        BlendMode.ColorDodge -> R.string.blend_color_dodge
        BlendMode.ColorBurn -> R.string.blend_color_burn
        BlendMode.HardLight -> R.string.blend_hard_light
        BlendMode.SoftLight -> R.string.blend_soft_light
        BlendMode.Difference -> R.string.blend_difference
        BlendMode.Exclusion -> R.string.blend_exclusion
        BlendMode.Add -> R.string.blend_add
        BlendMode.Subtract -> R.string.blend_subtract
        BlendMode.Divide -> R.string.blend_divide
        BlendMode.LinearBurn -> R.string.blend_linear_burn
        BlendMode.VividLight -> R.string.blend_vivid_light
        BlendMode.LinearLight -> R.string.blend_linear_light
        BlendMode.PinLight -> R.string.blend_pin_light
        BlendMode.HardMix -> R.string.blend_hard_mix
        BlendMode.Hue -> R.string.blend_hue
        BlendMode.Saturation -> R.string.blend_saturation
        BlendMode.Color -> R.string.blend_color
        BlendMode.Luminosity -> R.string.blend_luminosity
        BlendMode.DarkerColor -> R.string.blend_darker_color
        BlendMode.LighterColor -> R.string.blend_lighter_color
        BlendMode.PassThrough -> R.string.blend_pass_through
    }

private val ROW_HEIGHT = 56.dp
private val INDENT = 16.dp
private val THUMBNAIL_SIZE = 40.dp
private val LIST_MAX_HEIGHT = 520.dp
private const val SWIPE_THRESHOLD_PX = 96f
private const val GROUP_DROP_ZONE = 0.25f
private const val CHECKER_CELLS = 4
private val CHECKER_LIGHT = Color(0xFFEDEDED)
private val CHECKER_DARK = Color(0xFFCFCFCF)
