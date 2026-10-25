package com.mobyle.nanquim.canvas.selection

import com.mobyle.nanquim.engine.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class SelectionMode {
    Automatic,
    Freehand,
    Rectangle,
    Ellipse,
    Paint,
}

sealed interface PendingSelection {
    data class Box(
        val start: Point,
        val end: Point,
        val ellipse: Boolean,
    ) : PendingSelection {
        val left: Double get() = min(start.x, end.x)
        val top: Double get() = min(start.y, end.y)
        val right: Double get() = max(start.x, end.x)
        val bottom: Double get() = max(start.y, end.y)
        val corners: List<Point> get() = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
    }

    data class Path(
        val nodes: List<PathNode>,
    ) : PendingSelection

    fun polygon(): List<Point>? =
        when (this) {
            is Box ->
                if (right - left < MINIMUM_SIZE_PX || bottom - top < MINIMUM_SIZE_PX) {
                    null
                } else if (ellipse) {
                    SelectionGeometry.ellipse(left, top, right, bottom)
                } else {
                    SelectionGeometry.rectangle(left, top, right, bottom)
                }
            is Path -> SelectionGeometry.closedPath(nodes).takeIf { it.size >= 3 }
        }

    companion object {
        const val MINIMUM_SIZE_PX = 1.0
    }
}

class SelectionEditor {
    var mode: SelectionMode = SelectionMode.Freehand
        set(value) {
            if (value != field) pending = null
            field = value
        }

    var pending: PendingSelection? = null
        private set

    var replaced: PendingSelection? = null
        private set

    private sealed interface Drag {
        data class NewBox(
            val anchor: Point,
        ) : Drag

        data class BoxCorner(
            val fixed: Point,
        ) : Drag

        data class Node(
            val index: Int,
            val down: Point,
        ) : Drag

        data class Path(
            val down: Point,
            var moved: Boolean = false,
        ) : Drag
    }

    private var drag: Drag? = null

    enum class Result {
        None,

        ApplyPrevious,

        Apply,
    }

    fun down(
        point: Point,
        zoom: Double,
    ): Result {
        val handleRadius = HANDLE_RADIUS_SCREEN_PX / zoom
        val current = pending
        return when (mode) {
            SelectionMode.Rectangle, SelectionMode.Ellipse -> {
                if (current is PendingSelection.Box) {
                    val corners = current.corners
                    val nearest = corners.indices.minBy { SelectionGeometry.distance(corners[it], point) }
                    if (SelectionGeometry.distance(corners[nearest], point) <= handleRadius) {
                        drag = Drag.BoxCorner(corners[(nearest + 2) % 4])
                        return Result.None
                    }
                }
                replaced = current
                pending = null
                drag = Drag.NewBox(point)
                if (current != null) Result.ApplyPrevious else Result.None
            }
            SelectionMode.Freehand -> {
                if (current is PendingSelection.Path) {
                    val nearest = current.nodes.indices.minByOrNull { SelectionGeometry.distance(current.nodes[it].point, point) }
                    if (nearest != null && SelectionGeometry.distance(current.nodes[nearest].point, point) <= handleRadius) {
                        drag = Drag.Node(nearest, point)
                        return Result.None
                    }
                }
                drag = Drag.Path(point)
                Result.None
            }
            SelectionMode.Paint, SelectionMode.Automatic -> Result.None
        }
    }

    fun move(
        point: Point,
        zoom: Double,
        constrain: Boolean,
    ) {
        when (val active = drag) {
            is Drag.NewBox -> {
                val end = if (constrain) constrained(active.anchor, point) else point
                if (pending != null || SelectionGeometry.distance(active.anchor, point) * zoom >= TAP_SLOP_SCREEN_PX) {
                    pending = PendingSelection.Box(active.anchor, end, mode == SelectionMode.Ellipse)
                }
            }
            is Drag.BoxCorner -> {
                val box = pending as? PendingSelection.Box ?: return
                pending = box.copy(start = active.fixed, end = if (constrain) constrained(active.fixed, point) else point)
            }
            is Drag.Node -> {
                val path = pending as? PendingSelection.Path ?: return
                pending = path.copy(nodes = path.nodes.toMutableList().also { it[active.index] = it[active.index].copy(point = point) })
            }
            is Drag.Path -> {
                if (!active.moved && SelectionGeometry.distance(active.down, point) * zoom < TAP_SLOP_SCREEN_PX) return
                val nodes = (pending as? PendingSelection.Path)?.nodes.orEmpty()
                if (!active.moved) {
                    active.moved = true
                    pending = PendingSelection.Path(nodes + PathNode(active.down))
                }
                val path = pending as PendingSelection.Path
                if (SelectionGeometry.distance(path.nodes.last().point, point) * zoom >= NODE_SPACING_SCREEN_PX) {
                    pending = PendingSelection.Path(path.nodes + PathNode(point))
                }
            }
            null -> Unit
        }
    }

    fun up(
        point: Point,
        zoom: Double,
    ): Result {
        val active = drag ?: return Result.None
        drag = null
        when (active) {
            is Drag.NewBox, is Drag.BoxCorner -> return Result.None
            is Drag.Node -> {
                val path = pending as? PendingSelection.Path ?: return Result.None
                val tapped = SelectionGeometry.distance(active.down, point) * zoom < TAP_SLOP_SCREEN_PX
                if (tapped && active.index == 0 && path.nodes.size >= 3) return Result.Apply
                return Result.None
            }
            is Drag.Path -> {
                if (active.moved) {
                    val path = pending as? PendingSelection.Path ?: return Result.None
                    if (SelectionGeometry.distance(path.nodes.last().point, point) > 0) {
                        pending = PendingSelection.Path(path.nodes + PathNode(point))
                    }
                    return Result.None
                }
                val nodes = (pending as? PendingSelection.Path)?.nodes.orEmpty()
                pending = PendingSelection.Path(nodes + PathNode(active.down, corner = true))
                return Result.None
            }
        }
    }

    fun setPendingBox(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ) {
        pending = PendingSelection.Box(Point(left, top), Point(right, bottom), ellipse = false)
        drag = null
    }

    fun cancelGesture() {
        if (drag is Drag.NewBox) pending = null
        drag = null
    }

    fun takePending(): PendingSelection? {
        val taken = pending
        pending = null
        drag = null
        return taken
    }

    private fun constrained(
        anchor: Point,
        point: Point,
    ): Point {
        val dx = point.x - anchor.x
        val dy = point.y - anchor.y
        val side = max(abs(dx), abs(dy))
        return Point(anchor.x + side * (if (dx == 0.0) 1.0 else sign(dx)), anchor.y + side * (if (dy == 0.0) 1.0 else sign(dy)))
    }

    companion object {
        const val HANDLE_RADIUS_SCREEN_PX = 28.0
        const val TAP_SLOP_SCREEN_PX = 12.0
        const val NODE_SPACING_SCREEN_PX = 8.0
    }
}
