package com.mobyle.nanquim.ui.brush

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

sealed interface LibraryDragItem {
    val name: String

    data class Brush(
        val brushId: String,
        val groupId: String,
        override val name: String,
    ) : LibraryDragItem

    data class Group(
        val groupId: String,
        override val name: String,
    ) : LibraryDragItem
}

sealed interface LibraryDropTarget {
    data class IntoGroup(
        val groupId: String,
    ) : LibraryDropTarget

    data class BrushPosition(
        val groupId: String,
        val position: Int,
        val besideBrushId: String?,
        val after: Boolean,
    ) : LibraryDropTarget

    data class GroupPosition(
        val position: Int,
        val besideGroupId: String,
        val after: Boolean,
    ) : LibraryDropTarget
}

object LibraryDrop {
    fun target(
        item: LibraryDragItem,
        pointer: Offset,
        groupRows: Map<String, Rect>,
        groupIds: List<String>,
        brushRows: Map<String, Rect>,
        brushListArea: Rect?,
        shownGroupId: String?,
        shownBrushIds: List<String>,
        groupsInRow: Boolean = false,
    ): LibraryDropTarget? =
        when (item) {
            is LibraryDragItem.Brush -> {
                val overGroup = groupRows.entries.firstOrNull { it.value.contains(pointer) }?.key
                when {
                    overGroup != null -> LibraryDropTarget.IntoGroup(overGroup).takeIf { overGroup != item.groupId }
                    shownGroupId == null || brushListArea?.contains(pointer) != true -> null
                    else -> {
                        val others = shownBrushIds - item.brushId
                        val beside = nearest(pointer, brushRows, others)
                        if (beside == null) {
                            LibraryDropTarget.BrushPosition(shownGroupId, others.size, null, true)
                        } else {
                            val (id, after) = beside
                            LibraryDropTarget.BrushPosition(shownGroupId, others.indexOf(id) + if (after) 1 else 0, id, after)
                        }
                    }
                }
            }
            is LibraryDragItem.Group -> {
                val others = groupIds - item.groupId
                val column = groupRows.values.fold(null as Rect?) { union, rect -> union?.union(rect) ?: rect }
                val outside =
                    when {
                        column == null -> true
                        groupsInRow -> pointer.y !in column.top..column.bottom
                        else -> pointer.x !in column.left..column.right
                    }
                if (outside) {
                    null
                } else {
                    nearest(pointer, groupRows, others, horizontal = groupsInRow)?.let { (id, after) ->
                        LibraryDropTarget.GroupPosition(others.indexOf(id) + if (after) 1 else 0, id, after)
                    }
                }
            }
        }

    private fun nearest(
        pointer: Offset,
        rows: Map<String, Rect>,
        candidates: List<String>,
        horizontal: Boolean = false,
    ): Pair<String, Boolean>? {
        val position = if (horizontal) pointer.x else pointer.y

        fun Rect.start() = if (horizontal) left else top

        fun Rect.end() = if (horizontal) right else bottom
        val id =
            candidates
                .filter { it in rows }
                .minByOrNull { id ->
                    val rect = rows.getValue(id)
                    when {
                        position < rect.start() -> rect.start() - position
                        position > rect.end() -> position - rect.end()
                        else -> 0f
                    }
                } ?: return null
        val rect = rows.getValue(id)
        return id to (position > (rect.start() + rect.end()) / 2)
    }

    private fun Rect.union(other: Rect) =
        Rect(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))
}

class LibraryDragState {
    val groupRows = mutableMapOf<String, Rect>()
    val brushRows = mutableMapOf<String, Rect>()
    var groupListArea: Rect? = null
    var brushListArea: Rect? = null

    var item: LibraryDragItem? by mutableStateOf(null)
    var pointer: Offset by mutableStateOf(Offset.Zero)
    var target: LibraryDropTarget? by mutableStateOf(null)

    fun itemAt(
        point: Offset,
        brushes: Map<String, Pair<String, String>>,
        groups: Map<String, String>,
    ): LibraryDragItem? {
        brushRows.entries.firstOrNull { it.value.contains(point) }?.key?.let { id ->
            brushes[id]?.let { (groupId, name) -> return LibraryDragItem.Brush(id, groupId, name) }
        }
        groupRows.entries.firstOrNull { it.value.contains(point) }?.key?.let { id ->
            groups[id]?.let { name -> return LibraryDragItem.Group(id, name) }
        }
        return null
    }
}
