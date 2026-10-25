package com.mobyle.nanquim.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.R
import com.mobyle.nanquim.command.GalleryCommandIds
import com.mobyle.nanquim.gallery.ArtworkEntity
import com.mobyle.nanquim.gallery.Gallery
import com.mobyle.nanquim.gallery.GalleryItem
import com.mobyle.nanquim.gallery.GallerySort
import com.mobyle.nanquim.ui.common.LocalWindowLayout
import java.io.File
import kotlin.math.hypot

@Composable
fun GalleryScreen(
    gallery: Gallery,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onImport: () -> Unit,
    onPhoto: () -> Unit,
    onShare: (List<String>) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val allItems by gallery.items.collectAsState()
    val items = gallery.visibleItems(allItems)
    val openStack = gallery.openStackId?.let { id -> allItems.firstOrNull { it.id == id } as? GalleryItem.Stack }
    var renaming by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            GalleryTopBar(gallery, openStack, onCommand, onImport, onPhoto, onOpenSettings)
            if (gallery.selecting) SelectionBar(gallery, openStack != null, onCommand, onShare) { confirmingDelete = true }
            if (!gallery.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val loadingLabel = stringResource(R.string.gallery_loading)
                    CircularProgressIndicator(Modifier.semantics { contentDescription = loadingLabel })
                }
            } else if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (gallery.searchQuery.isBlank()) stringResource(R.string.gallery_empty) else stringResource(R.string.gallery_empty_search, gallery.searchQuery),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(CELL_MINIMUM_WIDTH),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        GalleryCell(
                            item = item,
                            gallery = gallery,
                            selected = item.id in gallery.selectedIds,
                            onCommand = onCommand,
                            onRename = { renaming = item },
                        )
                    }
                }
            }
        }
        gallery.previewId?.let { previewId ->
            val artworks = items.filterIsInstance<GalleryItem.Artwork>().map { it.entity }
            if (artworks.any { it.id == previewId }) {
                PreviewPager(gallery, artworks, previewId, onClose = { onCommand(GalleryCommandIds.PREVIEW, mapOf("id" to null)) })
            }
        }
    }

    renaming?.let { item ->
        RenameDialog(
            title = stringResource(if (item is GalleryItem.Stack) R.string.gallery_rename_stack_title else R.string.gallery_rename_title),
            initial = item.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (item is GalleryItem.Stack) {
                    onCommand(GalleryCommandIds.RENAME_STACK, mapOf("stackId" to item.id, "name" to name))
                } else {
                    onCommand(GalleryCommandIds.RENAME, mapOf("id" to item.id, "name" to name))
                }
            },
        )
    }
    if (confirmingDelete) {
        val count = gallery.selectedArtworkIds().size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(pluralStringResource(R.plurals.gallery_delete_title, count, count)) },
            text = { Text(stringResource(R.string.gallery_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onCommand(GalleryCommandIds.DELETE, mapOf("ids" to gallery.selectedIds.toList()))
                }) { Text(stringResource(R.string.gallery_action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.gallery_cancel)) } },
        )
    }
}

@Composable
private fun GalleryTopBar(
    gallery: Gallery,
    openStack: GalleryItem.Stack?,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onImport: () -> Unit,
    onPhoto: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ready = gallery.loaded
            if (openStack != null) {
                TextButton(onClick = { onCommand(GalleryCommandIds.OPEN_STACK, mapOf("stackId" to null)) }) { Text(stringResource(R.string.gallery_back)) }
            }
            Text(
                openStack?.name ?: stringResource(R.string.gallery_title),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            val selectLabel = stringResource(if (gallery.selecting) R.string.gallery_done else R.string.gallery_select)
            val select = { onCommand(GalleryCommandIds.SELECT_MODE, mapOf("enabled" to !gallery.selecting)) }
            if (LocalWindowLayout.current.compact) {
                TextButton(enabled = ready, onClick = { onCommand(GalleryCommandIds.NEW_CANVAS_SCREEN, emptyMap()) }) {
                    Text("+ " + stringResource(R.string.gallery_new_canvas))
                }
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(enabled = ready, onClick = { menuOpen = true }) { Text(stringResource(R.string.gallery_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        @Composable
                        fun item(
                            label: String,
                            action: () -> Unit,
                        ) = DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                action()
                            },
                        )
                        item(selectLabel, select)
                        item(stringResource(R.string.gallery_import), onImport)
                        item(stringResource(R.string.gallery_photo), onPhoto)
                        item(stringResource(R.string.settings_title), onOpenSettings)
                    }
                }
            } else {
                TextButton(enabled = ready, onClick = select) { Text(selectLabel) }
                TextButton(enabled = ready, onClick = onImport) { Text(stringResource(R.string.gallery_import)) }
                TextButton(enabled = ready, onClick = onPhoto) { Text(stringResource(R.string.gallery_photo)) }
                TextButton(enabled = ready, onClick = { onCommand(GalleryCommandIds.NEW_CANVAS_SCREEN, emptyMap()) }) {
                    Text("+ " + stringResource(R.string.gallery_new_canvas))
                }
                TextButton(enabled = ready, onClick = onOpenSettings) { Text(stringResource(R.string.settings_title)) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = gallery.searchQuery,
                onValueChange = { onCommand(GalleryCommandIds.SEARCH, mapOf("query" to it)) },
                placeholder = { Text(stringResource(R.string.gallery_search)) },
                singleLine = true,
                enabled = gallery.loaded,
                modifier = Modifier.weight(1f, fill = false).width(SEARCH_WIDTH),
            )
            val sortName = stringResource(if (gallery.sort == GallerySort.Date) R.string.gallery_sort_date else R.string.gallery_sort_name)
            TextButton(enabled = gallery.loaded, onClick = {
                val next = if (gallery.sort == GallerySort.Date) GallerySort.Name else GallerySort.Date
                onCommand(GalleryCommandIds.SORT, mapOf("order" to next.id))
            }) { Text(stringResource(R.string.gallery_sort_label, sortName)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    gallery: Gallery,
    insideStack: Boolean,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onShare: (List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    val ids = gallery.selectedIds.toList()
    val enabled = ids.isNotEmpty()
    FlowRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        Text(pluralStringResource(R.plurals.gallery_selected_count, ids.size, ids.size), Modifier.weight(1f))
        TextButton(enabled = enabled, onClick = { onShare(gallery.selectedArtworkIds()) }) { Text(stringResource(R.string.gallery_action_share)) }
        TextButton(enabled = enabled, onClick = { onCommand(GalleryCommandIds.DUPLICATE, mapOf("ids" to ids)) }) { Text(stringResource(R.string.gallery_action_duplicate)) }
        if (insideStack) {
            TextButton(enabled = enabled, onClick = { onCommand(GalleryCommandIds.UNSTACK, mapOf("ids" to ids)) }) { Text(stringResource(R.string.gallery_action_unstack)) }
        } else {
            TextButton(enabled = gallery.selectedArtworkIds().size >= 2, onClick = { onCommand(GalleryCommandIds.STACK, mapOf("ids" to ids)) }) {
                Text(stringResource(R.string.gallery_action_stack))
            }
        }
        TextButton(enabled = enabled, onClick = onDelete) { Text(stringResource(R.string.gallery_action_delete), color = MaterialTheme.colorScheme.error) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryCell(
    item: GalleryItem,
    gallery: Gallery,
    selected: Boolean,
    onCommand: (String, Map<String, Any?>) -> Unit,
    onRename: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CELL_ASPECT)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                .pinchOut {
                    if (item is GalleryItem.Artwork && !gallery.selecting) onCommand(GalleryCommandIds.PREVIEW, mapOf("id" to item.id))
                }.combinedClickable(
                    onClick = {
                        when {
                            gallery.selecting -> onCommand(GalleryCommandIds.TOGGLE_SELECTED, mapOf("id" to item.id))
                            item is GalleryItem.Stack -> onCommand(GalleryCommandIds.OPEN_STACK, mapOf("stackId" to item.id))
                            else -> {
                                gallery.transitions.prepareOpen(item.id)
                                onCommand(GalleryCommandIds.OPEN, mapOf("id" to item.id))
                            }
                        }
                    },
                    onLongClick = { onCommand(GalleryCommandIds.TOGGLE_SELECTED, mapOf("id" to item.id)) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (item) {
                is GalleryItem.Artwork ->
                    Thumbnail(
                        gallery,
                        item.entity,
                        Modifier.fillMaxSize().padding(8.dp).onGloballyPositioned { coordinates ->
                            gallery.transitions.cellPlaced(item.id, coordinates.boundsInWindow())
                        },
                        tracked = true,
                    )
                is GalleryItem.Stack -> StackThumbnails(gallery, item.artworks)
            }
        }
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !gallery.selecting, onClick = onRename).padding(top = 6.dp),
        )
        Text(
            when (item) {
                is GalleryItem.Artwork -> stringResource(R.string.gallery_artwork_size, item.entity.widthPx, item.entity.heightPx)
                is GalleryItem.Stack -> pluralStringResource(R.plurals.gallery_stack_count, item.artworks.size, item.artworks.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StackThumbnails(
    gallery: Gallery,
    artworks: List<ArtworkEntity>,
) {
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        artworks.take(STACK_PREVIEW_COUNT).chunked(2).forEach { row ->
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { artwork -> Thumbnail(gallery, artwork, Modifier.weight(1f).fillMaxSize()) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Thumbnail(
    gallery: Gallery,
    artwork: ArtworkEntity,
    modifier: Modifier,
    tracked: Boolean = false,
) {
    val file = gallery.thumbnailFile(artwork.id)
    val image by produceState<ImageBitmap?>(null, file, artwork.modifiedAt, gallery.thumbnailVersion) {
        value = loadThumbnail(file)?.also { if (tracked) gallery.transitions.imageLoaded(artwork.id, it) }
    }
    image?.let { Image(it, contentDescription = artwork.name, contentScale = ContentScale.Fit, modifier = modifier) }
}

private suspend fun loadThumbnail(file: File): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (!file.isFile) null else BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

@Composable
private fun PreviewPager(
    gallery: Gallery,
    artworks: List<ArtworkEntity>,
    initialId: String,
    onClose: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = artworks.indexOfFirst { it.id == initialId }.coerceAtLeast(0)) { artworks.size }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
        HorizontalPager(pagerState, Modifier.fillMaxSize()) { page ->
            val artwork = artworks[page]
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Thumbnail(gallery, artwork, Modifier.weight(1f).fillMaxWidth())
                Text(artwork.name, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Text(stringResource(R.string.gallery_preview_close), color = Color.White)
        }
    }
}

@Composable
fun RenameDialog(
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
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.gallery_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) } },
    )
}

private fun Modifier.pinchOut(onPinchOut: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startDistance = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    val distance = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                    if (startDistance == 0f) startDistance = distance
                    if (distance > startDistance * PINCH_OUT_RATIO) {
                        pressed.forEach { it.consume() }
                        onPinchOut()
                        break
                    }
                }
            }
        }
    }

private val CELL_MINIMUM_WIDTH = 200.dp
private val SEARCH_WIDTH = 320.dp
private const val CELL_ASPECT = 4f / 3f
private const val STACK_PREVIEW_COUNT = 4
private const val PINCH_OUT_RATIO = 1.35f
