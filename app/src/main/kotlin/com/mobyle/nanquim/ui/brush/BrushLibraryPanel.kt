package com.mobyle.nanquim.ui.brush

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ui.common.LocalWindowLayout
import com.mobyle.nanquim.ui.common.onSecondaryClick
import com.mobyle.nanquim.brush.BrushAnimation
import com.mobyle.nanquim.brush.BrushThumbnail
import com.mobyle.nanquim.brush.BrushThumbnails
import com.mobyle.nanquim.brush.BrushUiState
import com.mobyle.nanquim.brush.StampMode
import com.mobyle.nanquim.brush.builtInGroupNameRes
import com.mobyle.nanquim.brush.library.BrushEntryEntity
import com.mobyle.nanquim.brush.library.BrushGroup
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.command.BrushCommandIds
import com.mobyle.nanquim.command.STAMP_SOURCE_IMAGE
import com.mobyle.nanquim.command.STAMP_SOURCE_SELECTION
import kotlin.math.roundToInt

@Composable
fun BrushLibraryPanel(
    ui: BrushUiState,
    tool: BrushTool,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maxHeight: Dp = Dp.Unspecified,
) {
    val groupsState = ui.library.groups.collectAsState(initial = emptyList())
    val recentState = ui.library.recent.collectAsState(initial = emptyList())
    val groups = groupsState.value
    val recent = recentState.value
    val selectedBrushId = ui.brushes[tool]?.brushId
    var creatingGroup by remember { mutableStateOf(false) }
    var renamingGroup by remember { mutableStateOf<BrushGroup?>(null) }
    var groupMenuId by remember { mutableStateOf<String?>(null) }
    val importPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onCommand(BrushCommandIds.BRUSH_IMPORT, mapOf("uri" to uri.toString()))
        }
    var stampMenuOpen by remember { mutableStateOf(false) }
    var stampMode by remember { mutableStateOf(StampMode.Luminance) }
    val stampPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                onCommand(
                    BrushCommandIds.BRUSH_CREATE_STAMP,
                    mapOf("source" to STAMP_SOURCE_IMAGE, "uri" to uri.toString(), "mode" to stampMode.key),
                )
            }
        }

    LaunchedEffect(groups.isNotEmpty()) {
        if (ui.shownGroupId == null && selectedBrushId != null) {
            ui.shownGroupId = groups.firstOrNull { group -> group.brushes.any { it.brushId == selectedBrushId } }?.id
        }
    }
    val shownGroup = groups.firstOrNull { it.id == ui.shownGroupId }
    val shownBrushes =
        shownGroup?.brushes ?: run {
            val pinned = groups.flatMap { it.brushes }.filter { it.pinned }
            pinned + recent.filterNot { it.pinned }
        }

    val drag = remember { LibraryDragState() }
    val window = LocalWindowLayout.current
    val listHeight =
        if (compact) {
            COMPACT_PANEL_HEIGHT.coerceAtMost(window.height * COMPACT_HEIGHT_SHARE)
        } else if (maxHeight != Dp.Unspecified) {
            PANEL_HEIGHT.coerceAtMost(maxHeight - HEADER_ALLOWANCE).coerceAtLeast(MINIMUM_LIST_HEIGHT)
        } else {
            PANEL_HEIGHT
        }
    val groupListState = rememberLazyListState()
    val brushListState = rememberLazyListState()
    val groupNames = groups.associate { it.id to groupName(it) }
    val dragContext by rememberUpdatedState(
        DragContext(groups, groupNames, shownGroup?.id, shownBrushes),
    )
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val command by rememberUpdatedState(onCommand)

    fun updateTarget() {
        val item = drag.item ?: return
        val context = dragContext
        drag.target =
            LibraryDrop.target(
                item = item,
                pointer = drag.pointer,
                groupRows = drag.groupRows,
                groupIds = context.groups.map { it.id },
                brushRows = drag.brushRows,
                brushListArea = drag.brushListArea,
                shownGroupId = context.shownGroupId,
                shownBrushIds = context.shownBrushes.map { it.brushId },
                groupsInRow = compact,
            )
    }

    fun drop() {
        val item = drag.item
        when (val target = drag.target) {
            is LibraryDropTarget.IntoGroup ->
                if (item is LibraryDragItem.Brush) {
                    val group = dragContext.groups.firstOrNull { it.id == target.groupId }
                    val size = group?.brushes?.size ?: 0
                    command(BrushCommandIds.BRUSH_MOVE, mapOf("brushId" to item.brushId, "groupId" to target.groupId, "position" to size))
                }
            is LibraryDropTarget.BrushPosition ->
                if (item is LibraryDragItem.Brush) {
                    command(
                        BrushCommandIds.BRUSH_MOVE,
                        mapOf("brushId" to item.brushId, "groupId" to target.groupId, "position" to target.position),
                    )
                }
            is LibraryDropTarget.GroupPosition ->
                if (item is LibraryDragItem.Group) {
                    command(BrushCommandIds.GROUP_MOVE, mapOf("groupId" to item.groupId, "position" to target.position))
                }
            null -> Unit
        }
        drag.item = null
        drag.target = null
    }

    val edgePx = with(LocalDensity.current) { SCROLL_EDGE.toPx() }
    LaunchedEffect(drag.item != null) {
        while (drag.item != null) {
            withFrameNanos { }
            val pointer = drag.pointer
            for ((area, state) in listOf(drag.groupListArea to groupListState, drag.brushListArea to brushListState)) {
                val horizontal = compact && state === groupListState
                if (area == null || (if (horizontal) pointer.y !in area.top..area.bottom else pointer.x !in area.left..area.right)) continue
                val position = if (horizontal) pointer.x else pointer.y
                when {
                    position < (if (horizontal) area.left else area.top) + edgePx -> state.scrollBy(-SCROLL_STEP_PX)
                    position > (if (horizontal) area.right else area.bottom) - edgePx -> state.scrollBy(SCROLL_STEP_PX)
                }
            }
            updateTarget()
        }
    }

    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (tool == BrushTool.Erase) R.string.brush_library_title_eraser else R.string.brush_library_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Row(
                    Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.brush_library_import)) }
                    Box {
                        TextButton(onClick = { stampMenuOpen = true }) { Text(stringResource(R.string.brush_library_stamp)) }
                        DropdownMenu(expanded = stampMenuOpen, onDismissRequest = { stampMenuOpen = false }) {
                            for ((labelRes, mode) in listOf(
                                R.string.brush_stamp_from_image to StampMode.Luminance,
                                R.string.brush_stamp_color_from_image to StampMode.Color,
                            )) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = {
                                        stampMenuOpen = false
                                        stampMode = mode
                                        stampPicker.launch(arrayOf("image/*"))
                                    },
                                )
                            }
                            for ((labelRes, mode) in listOf(
                                R.string.brush_stamp_from_selection to StampMode.Alpha,
                                R.string.brush_stamp_color_from_selection to StampMode.Color,
                            )) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(labelRes)) },
                                    onClick = {
                                        stampMenuOpen = false
                                        onCommand(
                                            BrushCommandIds.BRUSH_CREATE_STAMP,
                                            mapOf("source" to STAMP_SOURCE_SELECTION, "mode" to mode.key),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { creatingGroup = true }) { Text(stringResource(R.string.brush_group_new)) }
                    TextButton(onClick = { onCommand(BrushCommandIds.LIBRARY_CLOSE, emptyMap()) }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
            HorizontalDivider()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(listHeight)
                    .onGloballyPositioned { panelOrigin = it.positionInRoot() }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val context = dragContext
                            val item =
                                drag.itemAt(
                                    panelOrigin + down.position,
                                    context.shownBrushes.associate { it.brushId to (it.groupId to it.name) },
                                    context.groupNames,
                                ) ?: return@awaitEachGesture
                            if (!heldStill(down)) return@awaitEachGesture
                            when (item) {
                                is LibraryDragItem.Brush -> command(BrushCommandIds.LIBRARY_SHOW_PREVIEW, mapOf("brushId" to item.brushId))
                                is LibraryDragItem.Group -> groupMenuId = item.groupId
                            }
                            var dragging = false
                            while (true) {
                                val change =
                                    awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) break
                                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    if (item is LibraryDragItem.Group) groupMenuId = null
                                    drag.item = item
                                }
                                if (dragging) {
                                    drag.pointer = panelOrigin + change.position
                                    updateTarget()
                                }
                            }
                            if (dragging) {
                                if (item is LibraryDragItem.Brush) command(BrushCommandIds.LIBRARY_SHOW_PREVIEW, emptyMap())
                                drop()
                            }
                        }
                    },
            ) {
                val groupItems: LazyListScope.() -> Unit = {
                    item {
                        GroupRow(
                            name = stringResource(R.string.brush_group_recent),
                            tab = compact,
                            selected = shownGroup == null,
                            onClick = { onCommand(BrushCommandIds.LIBRARY_SHOW_GROUP, mapOf("groupId" to "")) },
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        val name = groupNames.getValue(group.id)
                        DisposableEffect(group.id) { onDispose { drag.groupRows.remove(group.id) } }
                        Box(
                            Modifier
                                .onGloballyPositioned { drag.groupRows[group.id] = it.boundsInRoot() }
                                .alpha(if ((drag.item as? LibraryDragItem.Group)?.groupId == group.id) DRAGGED_ALPHA else 1f),
                        ) {
                            GroupRow(
                                name = name,
                                tab = compact,
                                selected = group.id == shownGroup?.id,
                                onClick = { onCommand(BrushCommandIds.LIBRARY_SHOW_GROUP, mapOf("groupId" to group.id)) },
                                onLongClick = { groupMenuId = group.id },
                            )
                            DropdownMenu(expanded = groupMenuId == group.id, onDismissRequest = { groupMenuId = null }) {
                                if (group.builtInKey == null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.brush_group_rename)) },
                                        onClick = {
                                            groupMenuId = null
                                            renamingGroup = group
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.brush_menu_share)) },
                                    onClick = {
                                        groupMenuId = null
                                        onCommand(BrushCommandIds.BRUSH_EXPORT, mapOf("groupId" to group.id))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.brush_group_delete)) },
                                    onClick = {
                                        groupMenuId = null
                                        onCommand(BrushCommandIds.GROUP_DELETE, mapOf("groupId" to group.id))
                                    },
                                )
                            }
                        }
                    }
                }

                @Composable
                fun brushList(listModifier: Modifier) {
                    LazyColumn(
                        listModifier.onGloballyPositioned { drag.brushListArea = it.boundsInRoot() },
                        state = brushListState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (shownBrushes.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.brush_library_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                        items(shownBrushes, key = { it.brushId }) { entry ->
                            DisposableEffect(entry.brushId) { onDispose { drag.brushRows.remove(entry.brushId) } }
                            BrushRow(
                                entry = entry,
                                thumbnails = ui.thumbnails,
                                selected = entry.brushId == selectedBrushId,
                                previewOpen = ui.previewBrushId == entry.brushId && drag.item == null,
                                dragged = (drag.item as? LibraryDragItem.Brush)?.brushId == entry.brushId,
                                tool = tool,
                                onCommand = onCommand,
                                modifier = Modifier.onGloballyPositioned { drag.brushRows[entry.brushId] = it.boundsInRoot() },
                            )
                        }
                    }
                }
                val groupListModifier = Modifier.onGloballyPositioned { drag.groupListArea = it.boundsInRoot() }
                if (compact) {
                    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                        LazyRow(
                            groupListModifier.fillMaxWidth(),
                            state = groupListState,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            content = groupItems,
                        )
                        brushList(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                        LazyColumn(
                            groupListModifier.width(GROUP_COLUMN_WIDTH).fillMaxHeight(),
                            state = groupListState,
                            content = groupItems,
                        )
                        brushList(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp))
                    }
                }
                DragOverlay(drag, panelOrigin, groupsInRow = compact)
            }
        }
    }

    if (creatingGroup) {
        NameDialog(
            title = stringResource(R.string.brush_group_new),
            initial = "",
            onDismiss = { creatingGroup = false },
            onConfirm = { name ->
                creatingGroup = false
                onCommand(BrushCommandIds.GROUP_CREATE, mapOf("name" to name))
            },
        )
    }
    renamingGroup?.let { group ->
        NameDialog(
            title = stringResource(R.string.brush_group_rename),
            initial = group.name.orEmpty(),
            onDismiss = { renamingGroup = null },
            onConfirm = { name ->
                renamingGroup = null
                onCommand(BrushCommandIds.GROUP_RENAME, mapOf("groupId" to group.id, "name" to name))
            },
        )
    }
}

private class DragContext(
    val groups: List<BrushGroup>,
    val groupNames: Map<String, String>,
    val shownGroupId: String?,
    val shownBrushes: List<BrushEntryEntity>,
)

@Composable
private fun DragOverlay(
    drag: LibraryDragState,
    panelOrigin: Offset,
    groupsInRow: Boolean,
) {
    val item = drag.item ?: return
    val color = MaterialTheme.colorScheme.primary
    val target = drag.target
    Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
        val line = 3.dp.toPx()

        fun local(rect: Rect) = rect.translate(-panelOrigin)

        fun insertion(
            rect: Rect,
            after: Boolean,
        ) {
            val y = if (after) rect.bottom else rect.top
            drawRect(color, Offset(rect.left, y - line / 2), Size(rect.width, line))
        }

        fun tabInsertion(
            rect: Rect,
            after: Boolean,
        ) {
            val x = if (after) rect.right else rect.left
            drawRect(color, Offset(x - line / 2, rect.top), Size(line, rect.height))
        }
        when (target) {
            is LibraryDropTarget.IntoGroup ->
                drag.groupRows[target.groupId]?.let { rect ->
                    val local = local(rect)
                    drawRoundRect(color, local.topLeft, local.size, style = Stroke(line))
                }
            is LibraryDropTarget.BrushPosition ->
                target.besideBrushId?.let { id -> drag.brushRows[id]?.let { insertion(local(it), target.after) } }
            is LibraryDropTarget.GroupPosition ->
                drag.groupRows[target.besideGroupId]?.let {
                    if (groupsInRow) tabInsertion(local(it), target.after) else insertion(local(it), target.after)
                }
            null -> Unit
        }
    }
    val pointer = drag.pointer - panelOrigin
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.offset { IntOffset(pointer.x.roundToInt(), pointer.y.roundToInt()) },
    ) {
        Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun groupName(group: BrushGroup): String =
    group.name ?: builtInGroupNameRes(group)?.let { stringResource(it) } ?: group.builtInKey.orEmpty()

@Composable
private fun GroupRow(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    tab: Boolean = false,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val holdLabel = stringResource(R.string.brush_library_hold)
    Text(
        name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .then(if (tab) Modifier.widthIn(max = GROUP_COLUMN_WIDTH) else Modifier.fillMaxWidth())
                .background(background, RoundedCornerShape(8.dp))
                .onSecondaryClick { onLongClick?.invoke() }
                .clickable(onClick = onClick)
                .semantics {
                    if (onLongClick != null) {
                        onLongClick(holdLabel) {
                            onLongClick()
                            true
                        }
                    }
                }.padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

@Composable
private fun BrushRow(
    entry: BrushEntryEntity,
    thumbnails: BrushThumbnails,
    selected: Boolean,
    previewOpen: Boolean,
    dragged: Boolean,
    tool: BrushTool,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnail: BrushThumbnail? by produceState<BrushThumbnail?>(null, entry.brushId, entry.name, entry.hasOverride) {
        value = thumbnails.thumbnail(entry.brushId)
    }
    var replays by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(replays) {
        if (replays == 0) return@LaunchedEffect
        val animation = thumbnails.animation(entry.brushId) ?: return@LaunchedEffect
        play(animation) { frame = it }
        frame = null
    }
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val ink = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val holdLabel = stringResource(R.string.brush_library_hold)
    Box(modifier.alpha(if (dragged) DRAGGED_ALPHA else 1f)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(background, RoundedCornerShape(8.dp))
                .clickable {
                    if (selected) {
                        onCommand(BrushCommandIds.EDITOR_OPEN, mapOf("brushId" to entry.brushId, "tool" to tool.key))
                    } else {
                        onCommand(BrushCommandIds.BRUSH_SELECT, mapOf("brushId" to entry.brushId, "tool" to tool.key))
                        replays++
                    }
                }.semantics {
                    contentDescription = entry.name
                    onLongClick(holdLabel) {
                        onCommand(BrushCommandIds.LIBRARY_SHOW_PREVIEW, mapOf("brushId" to entry.brushId))
                        true
                    }
                }.padding(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.weight(1f))
                if (entry.pinned) Text(stringResource(R.string.brush_pinned_mark), color = ink)
            }
            val current = thumbnail
            val image = frame ?: current?.image
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    colorFilter = if (current?.colored == true) null else ColorFilter.tint(ink),
                    modifier = Modifier.fillMaxWidth().aspectRatio(BrushThumbnails.WIDTH.toFloat() / BrushThumbnails.HEIGHT),
                )
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(BrushThumbnails.WIDTH.toFloat() / BrushThumbnails.HEIGHT))
            }
        }
        DropdownMenu(expanded = previewOpen, onDismissRequest = { onCommand(BrushCommandIds.LIBRARY_SHOW_PREVIEW, emptyMap()) }) {
            LargePreview(entry.brushId, thumbnails)
            fun item(
                labelRes: Int,
                command: String,
                arguments: Map<String, Any> = mapOf("brushId" to entry.brushId),
            ): @Composable () -> Unit =
                {
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes)) },
                        onClick = {
                            onCommand(BrushCommandIds.LIBRARY_SHOW_PREVIEW, emptyMap())
                            onCommand(command, arguments)
                        },
                    )
                }
            item(R.string.brush_menu_edit, BrushCommandIds.EDITOR_OPEN, mapOf("brushId" to entry.brushId, "tool" to tool.key))()
            item(R.string.brush_menu_share, BrushCommandIds.BRUSH_EXPORT)()
            item(R.string.command_brush_duplicate, BrushCommandIds.BRUSH_DUPLICATE)()
            if (entry.builtInPath != null && entry.hasOverride) item(R.string.command_brush_reset, BrushCommandIds.BRUSH_RESET)()
            item(
                if (entry.pinned) R.string.brush_menu_unpin else R.string.brush_menu_pin,
                BrushCommandIds.BRUSH_SET_PINNED,
                mapOf("brushId" to entry.brushId, "pinned" to !entry.pinned),
            )()
            item(R.string.command_brush_delete, BrushCommandIds.BRUSH_DELETE)()
        }
    }
}

@Composable
private fun LargePreview(
    brushId: String,
    thumbnails: BrushThumbnails,
) {
    val animation: BrushAnimation? by produceState<BrushAnimation?>(null, brushId) {
        value = thumbnails.animation(brushId, large = true)
    }
    var replays by remember { mutableIntStateOf(0) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(animation, replays) {
        animation?.let { play(it) { image -> frame = image } }
    }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .width(LARGE_PREVIEW_WIDTH.coerceAtMost(LocalWindowLayout.current.width - PREVIEW_MARGIN))
            .aspectRatio(BrushThumbnails.LARGE_WIDTH.toFloat() / BrushThumbnails.LARGE_HEIGHT)
            .clickable { replays++ }
            .padding(horizontal = 12.dp),
    ) {
        frame?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                colorFilter = if (animation?.colored == true) null else ColorFilter.tint(ink),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            )
        }
    }
}

private suspend fun AwaitPointerEventScope.heldStill(down: PointerInputChange): Boolean =
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        var change: PointerInputChange
        do {
            change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull
        } while (change.pressed && !change.isConsumed && (change.position - down.position).getDistance() <= viewConfiguration.touchSlop)
    } == null

private suspend fun play(
    animation: BrushAnimation,
    show: (ImageBitmap) -> Unit,
) {
    val frameMilliseconds = BrushThumbnails.ANIMATION_MILLISECONDS / animation.frames.size
    for (image in animation.frames) {
        show(image)
        delay(frameMilliseconds)
    }
}

@Composable
fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val PANEL_HEIGHT = 560.dp
private val COMPACT_PANEL_HEIGHT = 440.dp
private val HEADER_ALLOWANCE = 72.dp
private val MINIMUM_LIST_HEIGHT = 200.dp
private const val COMPACT_HEIGHT_SHARE = 0.6f
private val PREVIEW_MARGIN = 48.dp
private val GROUP_COLUMN_WIDTH = 180.dp
private val LARGE_PREVIEW_WIDTH = 600.dp
private val SCROLL_EDGE = 48.dp
private const val SCROLL_STEP_PX = 12f
private const val DRAGGED_ALPHA = 0.4f
