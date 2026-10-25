package com.mobyle.nanquim.ui.canvas

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.QuickShapeCommandIds
import com.mobyle.nanquim.document.shape.ShapeHandleKind
import com.mobyle.nanquim.engine.Point

@Composable
fun ShapeEditOverlay(session: CanvasSession) {
    if (session.editedShape == null) return
    val distance = with(LocalDensity.current) { ROTATE_HANDLE_DISTANCE.toPx().toDouble() }
    SideEffect { session.shapeRotateHandleScreenDistance = distance }

    @Suppress("UNUSED_VARIABLE")
    val cameraVersion = session.viewport.cameraVersion
    val toScreen = session.viewport.canvasToScreen() ?: return
    val drag = remember { ShapeDrag() }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) {
        while (dragging) {
            repeat(REDRAW_FRAMES) { withFrameNanos { } }
            drag.flush(session)
        }
    }
    session.shapeHandles(distance).forEachIndexed { index, handle ->
        val rotate = handle.kind == ShapeHandleKind.Rotate
        Handle(
            toScreen.apply(Point(handle.position.x, handle.position.y)),
            if (rotate) stringResource(R.string.shape_handle_rotate) else stringResource(R.string.shape_handle_point, index + 1),
            filled = !rotate,
            onDragStart = { dragging = session.startShapeDrag() },
            onDragEnd = {
                drag.flush(session)
                dragging = false
                session.endShapeDrag()
            },
        ) { screen ->
            val canvas = session.viewport.screenToCanvas()?.apply(screen) ?: return@Handle
            drag.pending = index to canvas
        }
    }
}

private class ShapeDrag {
    var pending: Pair<Int, Point>? = null

    fun flush(session: CanvasSession) {
        val (index, point) = pending ?: return
        pending = null
        session.dragShapeHandle(index, point)
    }
}

@Composable
fun ShapeEditButton(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    if (session.editedShape != null) {
        FilledTonalButton(onClick = { onCommand(QuickShapeCommandIds.END_EDIT, emptyMap()) }) {
            Text(stringResource(R.string.shape_edit_done))
        }
        return
    }
    val offer = session.shapeEditOffer ?: return
    LaunchedEffect(offer) {
        delay(OFFER_MILLIS)
        session.dismissShapeEditOffer(offer)
    }
    FilledTonalButton(onClick = { onCommand(QuickShapeCommandIds.EDIT, emptyMap()) }) { Text(stringResource(R.string.shape_edit_button)) }
}

private const val OFFER_MILLIS = 5_000L
private const val REDRAW_FRAMES = 2
private val ROTATE_HANDLE_DISTANCE = 48.dp
