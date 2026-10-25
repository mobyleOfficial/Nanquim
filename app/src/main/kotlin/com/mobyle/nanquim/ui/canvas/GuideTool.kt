package com.mobyle.nanquim.ui.canvas

import com.mobyle.nanquim.ui.common.LabeledSwitch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.command.GuideCommandIds
import com.mobyle.nanquim.command.ToolCommandIds
import com.mobyle.nanquim.ui.color.ColorSlider
import com.mobyle.nanquim.document.color.ColorComponent
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.guide.GridGeometry
import com.mobyle.nanquim.document.guide.GridGuide
import com.mobyle.nanquim.document.guide.GuideRect
import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.guide.GuideType
import com.mobyle.nanquim.document.guide.PerspectiveGeometry
import com.mobyle.nanquim.document.guide.SymmetryGeometry
import com.mobyle.nanquim.document.guide.SymmetryGuide
import com.mobyle.nanquim.document.guide.SymmetryKind
import com.mobyle.nanquim.engine.Point
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun GuideOverlay(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val guides = session.guides
    val editing = session.canvasTool == CanvasTool.Guide
    val document = session.document ?: return
    if (!guides.visible && !editing) return

    @Suppress("UNUSED_VARIABLE")
    val cameraVersion = session.viewport.cameraVersion
    val toScreen = session.viewport.canvasToScreen() ?: return
    val toCanvas = session.viewport.screenToCanvas() ?: return
    val color = guideColor(guides)
    Canvas(Modifier.fillMaxSize()) {
        val corners =
            listOf(
                Point(0.0, 0.0),
                Point(size.width.toDouble(), 0.0),
                Point(0.0, size.height.toDouble()),
                Point(size.width.toDouble(), size.height.toDouble()),
            ).map(toCanvas::apply)
        val visible =
            GuideRect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
                .intersect(GuideRect(0.0, 0.0, document.widthPx.toDouble(), document.heightPx.toDouble())) ?: return@Canvas
        val zoom = toScreen.scaleFactor
        val segments =
            when (guides.type) {
                GuideType.Grid, GuideType.Isometric -> {
                    val screenSpacing = GridGeometry.spacing(guides.type, guides.grid.size) * zoom
                    val minimum = MINIMUM_SCREEN_SPACING.toPx()
                    val stride = if (screenSpacing >= minimum) 1 else ceil(minimum / screenSpacing).toInt()
                    GridGeometry.lines(guides.type, guides.grid, visible, stride)
                }
                GuideType.Perspective -> PerspectiveGeometry.lines(guides.perspective, visible, PARALLEL_SPACING.toPx() / zoom)
                GuideType.Symmetry -> SymmetryGeometry.lines(guides.symmetry, visible)
            }
        val width = guides.thickness.dp.toPx()
        for (segment in segments) {
            val start = toScreen.apply(Point(segment.x0, segment.y0))
            val end = toScreen.apply(Point(segment.x1, segment.y1))
            val lineWidth = if (segment.emphasized) width * 2 else width
            drawLine(color, Offset(start.x.toFloat(), start.y.toFloat()), Offset(end.x.toFloat(), end.y.toFloat()), lineWidth)
        }
    }
    if (editing) {
        when (guides.type) {
            GuideType.Grid, GuideType.Isometric -> GridHandles(session, guides, onCommand)
            GuideType.Perspective -> PerspectiveHandles(session, guides, onCommand)
            GuideType.Symmetry -> SymmetryHandles(session, guides, onCommand)
        }
    }
}

@Composable
private fun GridHandles(
    session: CanvasSession,
    guides: GuideSettings,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val toScreen = session.viewport.canvasToScreen() ?: return
    val currentGuides by rememberUpdatedState(guides)
    val origin = toScreen.apply(Point(guides.grid.originX, guides.grid.originY))
    val distance = with(LocalDensity.current) { ROTATE_HANDLE_DISTANCE.toPx() } / toScreen.scaleFactor
    val (rotateX, rotateY) = GridGeometry.rotateHandle(guides.type, guides.grid, distance)
    val rotate = toScreen.apply(Point(rotateX, rotateY))
    val moveLabel = stringResource(R.string.guide_move_handle)
    val rotateLabel = stringResource(R.string.guide_rotate_handle)
    Handle(origin, moveLabel, filled = true) { screen ->
        val point = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
        onCommand(GuideCommandIds.SET, mapOf("originX" to point.x, "originY" to point.y))
    }
    Handle(rotate, rotateLabel, filled = false) { screen ->
        val point = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
        val grid = currentGuides.grid
        onCommand(GuideCommandIds.SET, mapOf("rotation" to GridGeometry.rotationToward(currentGuides.type, grid, point.x, point.y)))
    }
}

@Composable
private fun PerspectiveHandles(
    session: CanvasSession,
    guides: GuideSettings,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val toScreen = session.viewport.canvasToScreen() ?: return
    guides.perspective.points.forEachIndexed { index, point ->
        val selected = session.selectedVanishingPoint == index
        Handle(
            toScreen.apply(Point(point.x, point.y)),
            stringResource(R.string.guide_point_handle, index + 1),
            filled = true,
            highlight = if (selected) guideColor(guides).copy(alpha = 1f) else null,
            onTap = {
                if (selected) {
                    onCommand(GuideCommandIds.PERSPECTIVE_REMOVE, mapOf("index" to index))
                } else {
                    session.selectVanishingPoint(index)
                }
            },
            onDragStart = { session.selectVanishingPoint(index) },
        ) { screen ->
            val canvas = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
            onCommand(GuideCommandIds.PERSPECTIVE_MOVE, mapOf("index" to index, "x" to canvas.x, "y" to canvas.y))
        }
    }
}

@Composable
private fun SymmetryHandles(
    session: CanvasSession,
    guides: GuideSettings,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val toScreen = session.viewport.canvasToScreen() ?: return
    val currentGuides by rememberUpdatedState(guides)
    val symmetry = guides.symmetry
    val center = toScreen.apply(Point(symmetry.centerX, symmetry.centerY))
    val distance = with(LocalDensity.current) { ROTATE_HANDLE_DISTANCE.toPx() } / toScreen.scaleFactor
    val (rotateX, rotateY) = SymmetryGeometry.rotateHandle(symmetry, distance)
    Handle(center, stringResource(R.string.guide_symmetry_center), filled = true) { screen ->
        val point = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
        onCommand(GuideCommandIds.SET, mapOf("centerX" to point.x, "centerY" to point.y))
    }
    Handle(toScreen.apply(Point(rotateX, rotateY)), stringResource(R.string.guide_rotate_handle), filled = false) { screen ->
        val point = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
        val rotation = SymmetryGeometry.rotationToward(currentGuides.symmetry, point.x, point.y)
        onCommand(GuideCommandIds.SET, mapOf("symmetryRotation" to rotation))
    }
}

@Composable
internal fun Handle(
    at: Point,
    description: String,
    filled: Boolean,
    highlight: Color? = null,
    onTap: (() -> Unit)? = null,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDrag: (Point) -> Unit,
) {
    val half = with(LocalDensity.current) { (HANDLE_SIZE / 2).toPx() }
    val currentAt by rememberUpdatedState(at)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
            .size(HANDLE_SIZE)
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures { currentOnTap?.invoke() } }
            .pointerInput(Unit) {
                var position = Point(0.0, 0.0)
                detectDragGestures(
                    onDragStart = {
                        position = currentAt
                        currentOnDragStart()
                    },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                    onDrag = { change, amount ->
                        change.consume()
                        position = Point(position.x + amount.x, position.y + amount.y)
                        currentOnDrag(position)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(HANDLE_DOT)
                .border(1.dp, Color.Black, CircleShape)
                .padding(1.dp)
                .background(highlight ?: Color.White, CircleShape)
                .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (filled) Box(Modifier.size(HANDLE_DOT / 3).background(Color.Black, CircleShape))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideToolBar(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val guides = session.guides
    Surface(modifier = modifier.widthIn(max = BAR_WIDTH), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.Center) {
                for (type in GuideType.entries) {
                    val selected = type == guides.type
                    TextButton(
                        onClick = { onCommand(GuideCommandIds.SET, mapOf("type" to type.id)) },
                    ) {
                        Text(
                            stringResource(type.titleRes),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            LabeledSwitch(stringResource(R.string.guide_visible), guides.visible, { onCommand(GuideCommandIds.TOGGLE, mapOf("visible" to it)) })
            val hueLabel = stringResource(R.string.guide_color)
            LabeledRow(hueLabel) {
                ColorSlider(
                    ColorComponent.Hue,
                    ColorValue.fromHsb(guides.hue, 1f, 1f),
                    hueLabel,
                    onChange = { onCommand(GuideCommandIds.SET, mapOf("hue" to it.hue)) },
                )
            }
            LabeledRow(stringResource(R.string.guide_opacity)) {
                Slider(value = guides.opacity, onValueChange = { onCommand(GuideCommandIds.SET, mapOf("opacity" to it)) })
            }
            LabeledRow(stringResource(R.string.guide_thickness)) {
                Slider(
                    value = guides.thickness,
                    valueRange = GuideSettings.MINIMUM_THICKNESS..GuideSettings.MAXIMUM_THICKNESS,
                    onValueChange = { onCommand(GuideCommandIds.SET, mapOf("thickness" to it)) },
                )
            }
            when (guides.type) {
                GuideType.Grid, GuideType.Isometric ->
                    LabeledRow(stringResource(R.string.guide_grid_size, guides.grid.size.roundToInt())) {
                        Slider(
                            value = sizeToSlider(guides.grid.size),
                            onValueChange = { onCommand(GuideCommandIds.SET, mapOf("gridSize" to sliderToSize(it))) },
                        )
                    }
                GuideType.Perspective -> PerspectiveRows(session, onCommand)
                GuideType.Symmetry -> SymmetryRows(guides, onCommand)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = { onCommand(ToolCommandIds.TOOL_SELECT, mapOf("tool" to "brush")) }) {
                    Text(stringResource(R.string.guide_done))
                }
            }
        }
    }
}

@Composable
private fun PerspectiveRows(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val selected = session.selectedVanishingPoint
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.guide_perspective_hint, session.guides.perspective.points.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { selected?.let { onCommand(GuideCommandIds.PERSPECTIVE_REMOVE, mapOf("index" to it)) } },
            enabled = selected != null,
        ) { Text(stringResource(R.string.guide_remove_point)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymmetryRows(
    guides: GuideSettings,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val symmetry = guides.symmetry
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (kind in SymmetryKind.entries) {
            val selected = kind == symmetry.kind
            TextButton(onClick = { onCommand(GuideCommandIds.SET, mapOf("symmetry" to kind.id)) }) {
                Text(
                    stringResource(kind.titleRes),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    LabeledSwitch(
        stringResource(R.string.guide_symmetry_rotational),
        symmetry.rotational,
        { onCommand(GuideCommandIds.SET, mapOf("rotational" to it)) },
    )
    if (symmetry.kind == SymmetryKind.Radial) {
        LabeledRow(stringResource(R.string.guide_symmetry_segments, SymmetryGeometry.effectiveSegments(symmetry))) {
            Slider(
                value = symmetry.segments.toFloat(),
                valueRange = SymmetryGuide.MINIMUM_SEGMENTS.toFloat()..SymmetryGuide.MAXIMUM_SEGMENTS.toFloat(),
                steps = SymmetryGuide.MAXIMUM_SEGMENTS - SymmetryGuide.MINIMUM_SEGMENTS - 1,
                onValueChange = { onCommand(GuideCommandIds.SET, mapOf("segments" to it.roundToInt())) },
            )
        }
    }
}

@Composable
private fun LabeledRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(GUIDE_LABEL_WIDTH), maxLines = 2)
        Box(Modifier.weight(1f)) { content() }
    }
}

private val GuideType.titleRes: Int
    get() =
        when (this) {
            GuideType.Grid -> R.string.guide_type_grid
            GuideType.Isometric -> R.string.guide_type_isometric
            GuideType.Perspective -> R.string.guide_type_perspective
            GuideType.Symmetry -> R.string.guide_type_symmetry
        }

private val SymmetryKind.titleRes: Int
    get() =
        when (this) {
            SymmetryKind.Vertical -> R.string.guide_symmetry_vertical
            SymmetryKind.Horizontal -> R.string.guide_symmetry_horizontal
            SymmetryKind.Quadrant -> R.string.guide_symmetry_quadrant
            SymmetryKind.Radial -> R.string.guide_symmetry_radial
        }

private fun guideColor(guides: GuideSettings): Color {
    val color = ColorValue.fromHsb(guides.hue, GUIDE_SATURATION, 1f)
    return Color(color.red, color.green, color.blue, guides.opacity)
}

private fun sizeToSlider(size: Double): Float =
    ((ln(size) - ln(GridGuide.MINIMUM_SIZE)) / (ln(MAXIMUM_SLIDER_SIZE) - ln(GridGuide.MINIMUM_SIZE))).toFloat().coerceIn(0f, 1f)

private fun sliderToSize(slider: Float): Double =
    exp(ln(GridGuide.MINIMUM_SIZE) + slider * (ln(MAXIMUM_SLIDER_SIZE) - ln(GridGuide.MINIMUM_SIZE))).let { (it * 10).roundToInt() / 10.0 }

private val MINIMUM_SCREEN_SPACING = 8.dp
private val HANDLE_SIZE = 48.dp
private val HANDLE_DOT = 20.dp
private val ROTATE_HANDLE_DISTANCE = 120.dp

private val PARALLEL_SPACING = 48.dp
private val BAR_WIDTH = 520.dp
private val GUIDE_LABEL_WIDTH = 96.dp
private const val GUIDE_SATURATION = 0.85f
private const val MAXIMUM_SLIDER_SIZE = 1000.0
