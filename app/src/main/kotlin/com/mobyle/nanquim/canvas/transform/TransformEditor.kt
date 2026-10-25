package com.mobyle.nanquim.canvas.transform

import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.TransformFilter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.round

enum class TransformMode {
    Freeform,
    Uniform,
    Distort,
    Warp,
}

data class LiftedContent(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val centroid: Point,
    val principalAngle: Double,
)

data class TransformState(
    val sourceQuad: List<Point>,
    val targetQuad: List<Point>,
    val warpGrid: List<Point>? = null,
    val warpSize: Int = DEFAULT_WARP_SIZE,
) {
    companion object {
        const val DEFAULT_WARP_SIZE = 4
        const val MAXIMUM_WARP_SIZE = 8
    }
}

sealed interface TransformHandle {
    val point: Point

    data class Corner(
        val index: Int,
        override val point: Point,
    ) : TransformHandle

    data class Edge(
        val index: Int,
        override val point: Point,
    ) : TransformHandle

    data class Rotation(
        override val point: Point,
    ) : TransformHandle

    data class GridPoint(
        val index: Int,
        override val point: Point,
    ) : TransformHandle
}

class TransformEditor(
    private val canvasWidth: Double,
    private val canvasHeight: Double,
    private val onSnapped: () -> Unit = {},
) {
    private var angleSnapped = false

    var content: LiftedContent? = null
        private set

    var state: TransformState? = null
        private set

    var mode: TransformMode = TransformMode.Freeform
        private set

    var filter: TransformFilter = TransformFilter.Bilinear

    var magnetics: Boolean = true

    var snapDistance: Double = DEFAULT_SNAP_DISTANCE
    var snapVelocity: Double = DEFAULT_SNAP_VELOCITY

    var fitted: Boolean = false
        private set

    private val undoStack = ArrayDeque<Pair<TransformMode, TransformState>>()
    private val redoStack = ArrayDeque<Pair<TransformMode, TransformState>>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    fun initialize(lifted: LiftedContent) {
        content = lifted
        val quad = axisQuad(lifted)
        state = TransformState(quad, quad)
        mode = TransformMode.Freeform
        fitted = false
        undoStack.clear()
        redoStack.clear()
    }

    fun mesh(): FloatArray? {
        val current = state ?: return null
        val grid = current.warpGrid
        return if (grid != null) {
            TransformGeometry.warpMesh(current.sourceQuad, grid, current.warpSize)
        } else {
            TransformGeometry.quadMesh(current.sourceQuad, current.targetQuad)
        }
    }

    fun handles(zoom: Double): List<TransformHandle> {
        val current = state ?: return emptyList()
        val quad = current.targetQuad
        val grid = current.warpGrid
        if (grid != null) return grid.mapIndexed { index, point -> TransformHandle.GridPoint(index, point) }
        val corners = quad.mapIndexed { index, point -> TransformHandle.Corner(index, point) }
        val edges = (0 until 4).map { index -> TransformHandle.Edge(index, midpoint(quad[index], quad[(index + 1) % 4])) }
        return when (mode) {
            TransformMode.Freeform -> corners + edges + TransformHandle.Rotation(rotationHandle(quad, zoom))
            TransformMode.Uniform -> corners + TransformHandle.Rotation(rotationHandle(quad, zoom))
            TransformMode.Distort -> corners + edges
            TransformMode.Warp -> corners
        }
    }

    private sealed interface Drag {
        val start: Point

        data class Move(
            override val start: Point,
        ) : Drag

        data class Handle(
            override val start: Point,
            val handle: TransformHandle,
        ) : Drag

        data class Pull(
            override val start: Point,
            val nearest: Int,
        ) : Drag
    }

    private var drag: Drag? = null
    private var gestureStart: TransformState? = null

    fun down(
        point: Point,
        zoom: Double,
    ): Boolean {
        val current = state ?: return false
        val radius = HANDLE_RADIUS_SCREEN_PX / zoom
        val handle = handles(zoom).minByOrNull { TransformGeometry.distance(it.point, point) }?.takeIf { TransformGeometry.distance(it.point, point) <= radius }
        drag =
            when {
                handle != null -> Drag.Handle(point, handle)
                current.warpGrid != null && inside(point) -> Drag.Pull(point, nearestGridPoint(current.warpGrid, point))
                inside(point) -> Drag.Move(point)
                else -> null
            }
        if (drag != null) gestureStart = current
        return drag != null
    }

    fun move(
        point: Point,
        zoom: Double,
        velocity: Double,
    ) {
        val active = drag ?: return
        val start = gestureStart ?: return
        val snapping = magnetics && velocity < snapVelocity
        val tolerance = snapDistance / zoom
        val delta = Point(point.x - active.start.x, point.y - active.start.y)
        state =
            when (active) {
                is Drag.Move -> translated(start, if (snapping) snapMove(start, delta, tolerance) else delta)
                is Drag.Pull -> pulled(start, active.nearest, delta)
                is Drag.Handle -> moveHandle(start, active.handle, point, snapping)
            }
    }

    fun up() {
        val start = gestureStart
        drag = null
        gestureStart = null
        if (start != null && start != state) pushUndo(start)
    }

    fun cancelGesture() {
        gestureStart?.let { state = it }
        drag = null
        gestureStart = null
    }

    private var twistStart: Triple<TransformState, Point, Point>? = null

    fun twistStart(
        first: Point,
        second: Point,
    ) {
        val current = state ?: return
        drag = null
        twistStart = Triple(current, first, second)
    }

    fun twistUpdate(
        first: Point,
        second: Point,
    ) {
        val (start, a, b) = twistStart ?: return
        val startDistance = TransformGeometry.distance(a, b)
        if (startDistance < 1e-6) return
        val scale = TransformGeometry.distance(first, second) / startDistance
        var angle = atan2(second.y - first.y, second.x - first.x) - atan2(b.y - a.y, b.x - a.x)
        if (magnetics) angle = snapAngle(start, angle)
        val startMid = midpoint(a, b)
        val mid = midpoint(first, second)
        state = mapped(start) { point -> similarity(point, startMid, mid, scale, angle) }
    }

    fun twistEnd() {
        val start = twistStart?.first ?: return
        twistStart = null
        if (start != state) pushUndo(start)
    }

    fun inside(point: Point): Boolean {
        val current = state ?: return false
        val outline = current.warpGrid?.let { gridOutline(it, current.warpSize) } ?: current.targetQuad
        var inside = false
        for (index in outline.indices) {
            val a = outline[index]
            val b = outline[(index + 1) % outline.size]
            if ((a.y > point.y) != (b.y > point.y) && point.x < (b.x - a.x) * (point.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        }
        return inside
    }

    fun setMode(newMode: TransformMode) {
        val current = state ?: return
        if (newMode == mode) return
        val next =
            when {
                newMode == TransformMode.Warp ->
                    current.copy(warpGrid = TransformGeometry.warpGrid(current.sourceQuad, current.targetQuad, current.warpSize))
                current.warpGrid != null -> {
                    val corners = gridCorners(current.warpGrid, current.warpSize)
                    current.copy(targetQuad = if (newMode == TransformMode.Distort) corners else parallelogram(corners), warpGrid = null)
                }
                newMode != TransformMode.Distort -> current.copy(targetQuad = parallelogram(current.targetQuad))
                else -> current
            }
        apply(next)
        mode = newMode
    }

    fun setWarpSize(size: Int) {
        val current = state ?: return
        val clamped = size.coerceIn(TransformState.DEFAULT_WARP_SIZE, TransformState.MAXIMUM_WARP_SIZE)
        val grid = current.warpGrid ?: return
        if (clamped == current.warpSize) return
        val resampled =
            List(clamped * clamped) { index ->
                TransformGeometry.surface(grid, current.warpSize, (index % clamped) / (clamped - 1.0), (index / clamped) / (clamped - 1.0))
            }
        apply(current.copy(warpGrid = resampled, warpSize = clamped))
    }

    fun flipHorizontal() =
        transform { current ->
            val quad = current.targetQuad
            current.copy(
                targetQuad = listOf(quad[1], quad[0], quad[3], quad[2]),
                warpGrid = current.warpGrid?.let { grid -> grid.chunked(current.warpSize).flatMap { it.reversed() } },
            )
        }

    fun flipVertical() =
        transform { current ->
            val quad = current.targetQuad
            current.copy(
                targetQuad = listOf(quad[3], quad[2], quad[1], quad[0]),
                warpGrid = current.warpGrid?.let { grid -> grid.chunked(current.warpSize).reversed().flatten() },
            )
        }

    fun rotate45() =
        transform { current ->
            val center = TransformGeometry.center(current.targetQuad)
            mapped(current) { TransformGeometry.rotate(it, center, PI / 4) }
        }

    fun fitToCanvas() =
        transform { current ->
            val width = TransformGeometry.distance(current.sourceQuad[0], current.sourceQuad[1])
            val height = TransformGeometry.distance(current.sourceQuad[0], current.sourceQuad[3])
            if (width <= 0 || height <= 0) return@transform current
            val scale = min(canvasWidth / width, canvasHeight / height)
            val quad = rectangle(Point(canvasWidth / 2, canvasHeight / 2), width * scale, height * scale, 0.0)
            current.copy(targetQuad = quad, warpGrid = current.warpGrid?.let { TransformGeometry.warpGrid(current.sourceQuad, quad, current.warpSize) })
        }

    fun reset() =
        transform { current ->
            current.copy(
                targetQuad = current.sourceQuad,
                warpGrid = current.warpGrid?.let { TransformGeometry.warpGrid(current.sourceQuad, current.sourceQuad, current.warpSize) },
            )
        }

    fun setNumeric(
        centerX: Double,
        centerY: Double,
        width: Double,
        height: Double,
        rotationDegrees: Double,
    ) = transform { current ->
        if (current.warpGrid != null || width <= 0 || height <= 0) return@transform current
        current.copy(targetQuad = rectangle(Point(centerX, centerY), width, height, rotationDegrees * PI / 180))
    }

    fun setNumericQuad(quad: List<Point>) {
        require(quad.size == 4)
        transform { current -> current.copy(targetQuad = quad, warpGrid = null) }
    }

    fun setWarpGrid(points: List<Point>) {
        val current = state ?: return
        val size = kotlin.math.sqrt(points.size.toDouble()).toInt()
        require(size * size == points.size && size in TransformState.DEFAULT_WARP_SIZE..TransformState.MAXIMUM_WARP_SIZE) {
            "the grid has 4² to 8² points"
        }
        apply(current.copy(warpGrid = points, warpSize = size))
    }

    fun numeric(): DoubleArray? {
        val quad = state?.targetQuad ?: return null
        val center = TransformGeometry.center(quad)
        val angle = atan2(quad[1].y - quad[0].y, quad[1].x - quad[0].x) * 180 / PI
        return doubleArrayOf(center.x, center.y, TransformGeometry.distance(quad[0], quad[1]), TransformGeometry.distance(quad[0], quad[3]), angle)
    }

    fun setFitted(value: Boolean) {
        val current = state ?: return
        val lifted = content ?: return
        if (value == fitted || current.warpGrid != null) return
        val newSource = if (value) fittedQuad(lifted) else axisQuad(lifted)
        val h = TransformGeometry.homography(current.sourceQuad, current.targetQuad) ?: return
        val newTarget = newSource.map { TransformGeometry.project(h, it).first }
        fitted = value
        apply(current.copy(sourceQuad = newSource, targetQuad = newTarget))
    }

    fun undo(): Boolean {
        val (previousMode, previous) = undoStack.removeLastOrNull() ?: return false
        state?.let { redoStack.addLast(mode to it) }
        state = previous
        mode = previousMode
        return true
    }

    fun redo(): Boolean {
        val (nextMode, next) = redoStack.removeLastOrNull() ?: return false
        state?.let { undoStack.addLast(mode to it) }
        state = next
        mode = nextMode
        return true
    }

    private fun transform(change: (TransformState) -> TransformState) {
        val current = state ?: return
        apply(change(current))
    }

    private fun apply(next: TransformState) {
        val current = state ?: return
        if (next == current) return
        pushUndo(current)
        state = next
    }

    private fun pushUndo(previous: TransformState) {
        undoStack.addLast(mode to previous)
        if (undoStack.size > MAXIMUM_UNDO) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun mapped(
        start: TransformState,
        map: (Point) -> Point,
    ) = start.copy(targetQuad = start.targetQuad.map(map), warpGrid = start.warpGrid?.map(map))

    private fun translated(
        start: TransformState,
        delta: Point,
    ) = mapped(start) { Point(it.x + delta.x, it.y + delta.y) }

    private fun snapMove(
        start: TransformState,
        delta: Point,
        tolerance: Double,
    ): Point {
        val quad = start.warpGrid ?: start.targetQuad
        val left = quad.minOf { it.x } + delta.x
        val right = quad.maxOf { it.x } + delta.x
        val top = quad.minOf { it.y } + delta.y
        val bottom = quad.maxOf { it.y } + delta.y
        fun snap(candidates: List<Pair<Double, Double>>): Double {
            val best = candidates.minByOrNull { abs(it.second - it.first) } ?: return 0.0
            return if (abs(best.second - best.first) <= tolerance) best.second - best.first else 0.0
        }
        val dx = snap(listOf((left + right) / 2 to canvasWidth / 2, left to 0.0, right to canvasWidth))
        val dy = snap(listOf((top + bottom) / 2 to canvasHeight / 2, top to 0.0, bottom to canvasHeight))
        return Point(delta.x + dx, delta.y + dy)
    }

    private fun pulled(
        start: TransformState,
        nearest: Int,
        delta: Point,
    ): TransformState {
        val grid = start.warpGrid ?: return start
        val size = start.warpSize
        val (nearestColumn, nearestRow) = nearest % size to nearest / size
        return start.copy(
            warpGrid =
                grid.mapIndexed { index, point ->
                    val distance = hypot((index % size - nearestColumn).toDouble(), (index / size - nearestRow).toDouble())
                    val weight = exp(-distance * distance / (2 * PULL_SPREAD * PULL_SPREAD))
                    Point(point.x + delta.x * weight, point.y + delta.y * weight)
                },
        )
    }

    private fun moveHandle(
        start: TransformState,
        handle: TransformHandle,
        point: Point,
        snapping: Boolean,
    ): TransformState {
        val quad = start.targetQuad
        return when (handle) {
            is TransformHandle.GridPoint -> start.copy(warpGrid = start.warpGrid?.toMutableList()?.also { it[handle.index] = point })
            is TransformHandle.Rotation -> {
                val center = TransformGeometry.center(quad)
                var angle = atan2(point.y - center.y, point.x - center.x) - atan2(handle.point.y - center.y, handle.point.x - center.x)
                if (snapping) angle = snapAngle(start, angle)
                mapped(start) { TransformGeometry.rotate(it, center, angle) }
            }
            is TransformHandle.Corner ->
                when (mode) {
                    TransformMode.Distort, TransformMode.Warp -> start.copy(targetQuad = quad.toMutableList().also { it[handle.index] = point })
                    else -> start.copy(targetQuad = scaledCorner(quad, handle.index, point, uniform = mode == TransformMode.Uniform, snapping = snapping, start = start))
                }
            is TransformHandle.Edge -> {
                val a = handle.index
                val b = (handle.index + 1) % 4
                if (mode == TransformMode.Distort) {
                    val delta = Point(point.x - handle.point.x, point.y - handle.point.y)
                    start.copy(
                        targetQuad =
                            quad.mapIndexed { index, corner ->
                                if (index == a || index == b) Point(corner.x + delta.x, corner.y + delta.y) else corner
                            },
                    )
                } else {
                    start.copy(targetQuad = scaledEdge(quad, handle.index, point))
                }
            }
        }
    }

    private fun scaledCorner(
        quad: List<Point>,
        index: Int,
        point: Point,
        uniform: Boolean,
        snapping: Boolean,
        start: TransformState,
    ): List<Point> {
        val fixedIndex = (index + 2) % 4
        val fixed = quad[fixedIndex]
        val sideA = quad[(fixedIndex + 1) % 4].let { Point(it.x - fixed.x, it.y - fixed.y) }
        val sideB = quad[(fixedIndex + 3) % 4].let { Point(it.x - fixed.x, it.y - fixed.y) }
        val drag = Point(point.x - fixed.x, point.y - fixed.y)
        var (a, b) = solve(sideA, sideB, drag) ?: return quad
        if (uniform) {
            val diagonal = Point(sideA.x + sideB.x, sideA.y + sideB.y)
            val length2 = diagonal.x * diagonal.x + diagonal.y * diagonal.y
            if (length2 == 0.0) return quad
            val scale = (drag.x * diagonal.x + drag.y * diagonal.y) / length2
            a = scale
            b = scale
        }
        if (snapping) {
            val originalA = TransformGeometry.distance(start.sourceQuad[(fixedIndex + 1) % 4], start.sourceQuad[fixedIndex])
            val lengthA = hypot(sideA.x, sideA.y)
            if (lengthA > 0 && abs(abs(a) * lengthA / originalA - 1) < SIZE_SNAP) a = originalA / lengthA * if (a < 0) -1 else 1
            val originalB = TransformGeometry.distance(start.sourceQuad[(fixedIndex + 3) % 4], start.sourceQuad[fixedIndex])
            val lengthB = hypot(sideB.x, sideB.y)
            if (lengthB > 0 && abs(abs(b) * lengthB / originalB - 1) < SIZE_SNAP) b = originalB / lengthB * if (b < 0) -1 else 1
        }
        val newA = Point(fixed.x + sideA.x * a, fixed.y + sideA.y * a)
        val newB = Point(fixed.x + sideB.x * b, fixed.y + sideB.y * b)
        val opposite = Point(newA.x + newB.x - fixed.x, newA.y + newB.y - fixed.y)
        return quad.toMutableList().also {
            it[(fixedIndex + 1) % 4] = newA
            it[(fixedIndex + 3) % 4] = newB
            it[index] = opposite
        }
    }

    private fun scaledEdge(
        quad: List<Point>,
        edge: Int,
        point: Point,
    ): List<Point> {
        val a = edge
        val b = (edge + 1) % 4
        val fixedA = (edge + 3) % 4
        val fixedB = (edge + 2) % 4
        val side = Point(quad[a].x - quad[fixedA].x, quad[a].y - quad[fixedA].y)
        val length2 = side.x * side.x + side.y * side.y
        if (length2 == 0.0) return quad
        val fixedMid = midpoint(quad[fixedA], quad[fixedB])
        val scale = ((point.x - fixedMid.x) * side.x + (point.y - fixedMid.y) * side.y) / length2
        return quad.toMutableList().also {
            it[a] = Point(quad[fixedA].x + side.x * scale, quad[fixedA].y + side.y * scale)
            it[b] = Point(quad[fixedB].x + side.x * scale, quad[fixedB].y + side.y * scale)
        }
    }

    private fun snapAngle(
        start: TransformState,
        angle: Double,
    ): Double {
        val quad = start.targetQuad
        val base = atan2(quad[1].y - quad[0].y, quad[1].x - quad[0].x)
        val total = base + angle
        val step = PI / 4
        val nearest = round(total / step) * step
        val snapped = abs(total - nearest) < ANGLE_SNAP
        if (snapped && !angleSnapped) onSnapped()
        angleSnapped = snapped
        return if (snapped) nearest - base else angle
    }

    private fun solve(
        a: Point,
        b: Point,
        target: Point,
    ): Pair<Double, Double>? {
        val determinant = a.x * b.y - a.y * b.x
        if (abs(determinant) < 1e-9) return null
        return ((target.x * b.y - target.y * b.x) / determinant) to ((a.x * target.y - a.y * target.x) / determinant)
    }

    private fun similarity(
        point: Point,
        startMid: Point,
        mid: Point,
        scale: Double,
        angle: Double,
    ): Point {
        val rotated = TransformGeometry.rotate(point, startMid, angle)
        return Point(mid.x + (rotated.x - startMid.x) * scale, mid.y + (rotated.y - startMid.y) * scale)
    }

    private fun rotationHandle(
        quad: List<Point>,
        zoom: Double,
    ): Point {
        val top = midpoint(quad[0], quad[1])
        val center = TransformGeometry.center(quad)
        val dx = top.x - center.x
        val dy = top.y - center.y
        val length = hypot(dx, dy)
        val offset = ROTATION_HANDLE_SCREEN_PX / zoom
        return if (length == 0.0) Point(top.x, top.y - offset) else Point(top.x + dx / length * offset, top.y + dy / length * offset)
    }

    private fun nearestGridPoint(
        grid: List<Point>,
        point: Point,
    ): Int = grid.indices.minBy { TransformGeometry.distance(grid[it], point) }

    private fun gridCorners(
        grid: List<Point>,
        size: Int,
    ) = listOf(grid[0], grid[size - 1], grid[size * size - 1], grid[size * (size - 1)])

    private fun gridOutline(
        grid: List<Point>,
        size: Int,
    ): List<Point> {
        val top = (0 until size).map { grid[it] }
        val right = (1 until size).map { grid[it * size + size - 1] }
        val bottom = (size - 2 downTo 0).map { grid[(size - 1) * size + it] }
        val left = (size - 2 downTo 1).map { grid[it * size] }
        return top + right + bottom + left
    }

    private fun parallelogram(quad: List<Point>) = listOf(quad[0], quad[1], Point(quad[1].x + quad[3].x - quad[0].x, quad[1].y + quad[3].y - quad[0].y), quad[3])

    private fun axisQuad(lifted: LiftedContent): List<Point> {
        val left = lifted.x.toDouble()
        val top = lifted.y.toDouble()
        val right = left + lifted.width
        val bottom = top + lifted.height
        return listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
    }

    private fun fittedQuad(lifted: LiftedContent): List<Point> {
        val angle = lifted.principalAngle
        val pivot = lifted.centroid
        val corners = axisQuad(lifted).map { TransformGeometry.rotate(it, pivot, -angle) }
        val left = corners.minOf { it.x }
        val right = corners.maxOf { it.x }
        val top = corners.minOf { it.y }
        val bottom = corners.maxOf { it.y }
        return listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)).map {
            TransformGeometry.rotate(it, pivot, angle)
        }
    }

    private fun rectangle(
        center: Point,
        width: Double,
        height: Double,
        angle: Double,
    ): List<Point> {
        val halfWidth = width / 2
        val halfHeight = height / 2
        return listOf(
            Point(center.x - halfWidth, center.y - halfHeight),
            Point(center.x + halfWidth, center.y - halfHeight),
            Point(center.x + halfWidth, center.y + halfHeight),
            Point(center.x - halfWidth, center.y + halfHeight),
        ).map { TransformGeometry.rotate(it, center, angle) }
    }

    private fun midpoint(
        a: Point,
        b: Point,
    ) = Point((a.x + b.x) / 2, (a.y + b.y) / 2)

    companion object {
        const val HANDLE_RADIUS_SCREEN_PX = 28.0
        const val ROTATION_HANDLE_SCREEN_PX = 48.0
        const val DEFAULT_SNAP_DISTANCE = 12.0
        const val DEFAULT_SNAP_VELOCITY = 600.0
        const val MAXIMUM_UNDO = 100
        private const val ANGLE_SNAP = 3 * PI / 180
        private const val SIZE_SNAP = 0.03
        private const val PULL_SPREAD = 0.8
    }
}

