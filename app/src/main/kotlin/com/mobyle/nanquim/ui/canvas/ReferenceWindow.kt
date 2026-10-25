package com.mobyle.nanquim.ui.canvas

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.command.ReferenceCommandIds
import com.mobyle.nanquim.document.reference.ReferenceGeometry
import com.mobyle.nanquim.document.reference.ReferenceMode
import com.mobyle.nanquim.document.reference.ReferenceSettings
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ReferenceWindow(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reference = session.reference
    val settings = reference.settings
    if (!settings.visible || session.document == null) return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current.density.toDouble()
        val areaWidth = constraints.maxWidth.toDouble()
        val areaHeight = constraints.maxHeight.toDouble()
        val window = ReferenceGeometry.window(settings, areaWidth, areaHeight, density)
        var dragOffset by remember { mutableStateOf<Offset?>(null) }
        var resizeTo by remember { mutableStateOf<Offset?>(null) }
        val currentSettings by rememberUpdatedState(settings)
        val currentWindow by rememberUpdatedState(window)
        val left = window.left + (dragOffset?.x ?: 0f)
        val top = window.top + (dragOffset?.y ?: 0f)
        val width = resizeTo?.x?.toDouble() ?: window.width
        val height = resizeTo?.y?.toDouble() ?: window.height
        Surface(
            modifier =
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .size(with(LocalDensity.current) { width.toFloat().toDp() }, with(LocalDensity.current) { height.toFloat().toDp() })
                    .graphicsLayer { alpha = settings.opacity },
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                val moveLabel = stringResource(R.string.reference_move)
                ReferenceTitleBar(
                    settings = settings,
                    onCommand = onCommand,
                    modifier =
                        Modifier
                            .semantics { contentDescription = moveLabel }
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { dragOffset = Offset.Zero },
                                    onDragEnd = {
                                        val offset = dragOffset ?: Offset.Zero
                                        dragOffset = null
                                        val moved =
                                            ReferenceGeometry.moved(
                                                currentSettings,
                                                currentWindow.left + offset.x,
                                                currentWindow.top + offset.y,
                                                areaWidth,
                                                areaHeight,
                                                density,
                                            )
                                        onCommand(ReferenceCommandIds.SET, moved.placement())
                                    },
                                    onDragCancel = { dragOffset = null },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset = (dragOffset ?: Offset.Zero) + amount
                                }
                            },
                )
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                    when (settings.mode) {
                        ReferenceMode.Canvas -> CanvasContent(session)
                        ReferenceMode.Image -> ImageContent(session, onCommand)
                    }
                    ResizeGrip(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        onResize = { amount ->
                            val current = resizeTo ?: Offset(currentWindow.width.toFloat(), currentWindow.height.toFloat())
                            resizeTo = current + amount
                        },
                        onDone = {
                            val size = resizeTo
                            resizeTo = null
                            if (size != null) {
                                val resized = ReferenceGeometry.resized(currentSettings, size.x.toDouble(), size.y.toDouble(), density)
                                onCommand(ReferenceCommandIds.SET, mapOf("width" to resized.width, "height" to resized.height))
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun ReferenceSettings.placement(): Map<String, Any> = docked?.let { mapOf("docked" to it.id) } ?: mapOf("x" to x, "y" to y)

@Composable
private fun ReferenceTitleBar(
    settings: ReferenceSettings,
    onCommand: (String, Map<String, Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onCommand(ReferenceCommandIds.IMPORT_IMAGE, mapOf("uri" to uri.toString()))
        }
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (mode in ReferenceMode.entries) {
            val selected = mode == settings.mode
            TextButton(onClick = { onCommand(ReferenceCommandIds.SET, mapOf("mode" to mode.id)) }) {
                Text(
                    stringResource(if (mode == ReferenceMode.Canvas) R.string.reference_mode_canvas else R.string.reference_mode_image),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                )
            }
        }
        Box(Modifier.weight(1f))
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋯") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Column(Modifier.width(240.dp).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.reference_opacity), style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = settings.opacity,
                        valueRange = ReferenceSettings.MINIMUM_OPACITY..1f,
                        onValueChange = { onCommand(ReferenceCommandIds.SET, mapOf("opacity" to it.toDouble())) },
                    )
                    TextButton(onClick = {
                        menuOpen = false
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.reference_choose_image)) }
                }
            }
        }
        val closeLabel = stringResource(R.string.reference_close)
        TextButton(
            modifier = Modifier.semantics { contentDescription = closeLabel },
            onClick = { onCommand(ReferenceCommandIds.TOGGLE, mapOf("visible" to false)) },
        ) { Text("×") }
    }
}

@Composable
private fun CanvasContent(session: CanvasSession) {
    val reference = session.reference
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val longSide = max(constraints.maxWidth, constraints.maxHeight)
        val wanted = (longSide * reference.settings.canvasView.zoom).roundToInt().coerceIn(MINIMUM_COMPOSITE, MAXIMUM_COMPOSITE)
        val maxSide = Integer.highestOneBit(wanted - 1).shl(1).coerceIn(MINIMUM_COMPOSITE, MAXIMUM_COMPOSITE)
        LaunchedEffect(session.documentVersion, maxSide) { reference.refreshCanvas(maxSide) }
        LaunchedEffect(session.strokeInProgress, maxSide) {
            while (session.strokeInProgress) {
                delay(STROKE_REFRESH_MILLIS)
                reference.refreshCanvas(maxSide)
            }
        }
        @Suppress("UNUSED_VARIABLE")
        val version = reference.canvasVersion
        ZoomableImage(session, reference.canvasImage, onPick = null)
    }
}

@Composable
private fun ImageContent(
    session: CanvasSession,
    onCommand: (String, Map<String, Any>) -> Unit,
) {
    val image = session.reference.image
    if (image == null) {
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) onCommand(ReferenceCommandIds.IMPORT_IMAGE, mapOf("uri" to uri.toString()))
            }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.reference_no_image), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(stringResource(R.string.reference_choose_image))
            }
        }
        return
    }
    ZoomableImage(session, image) { x, y ->
        onCommand(ReferenceCommandIds.PICK_COLOR, mapOf("x" to x, "y" to y))
        session.disarmEyedropper()
    }
}

@Composable
private fun ZoomableImage(
    session: CanvasSession,
    image: Bitmap?,
    onPick: ((Double, Double) -> Unit)?,
) {
    val reference = session.reference
    val view = reference.settings.view
    val currentImage by rememberUpdatedState(image)
    val currentView by rememberUpdatedState(view)
    val currentOnPick by rememberUpdatedState(onPick)
    val imageBitmap = remember(image, reference.canvasVersion) { image?.asImageBitmap() }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun contentPoint(position: Offset): Pair<Double, Double>? {
        val bitmap = currentImage ?: return null
        return ReferenceGeometry.contentPoint(
            currentView,
            bitmap.width.toDouble(),
            bitmap.height.toDouble(),
            size.width.toDouble(),
            size.height.toDouble(),
            position.x.toDouble(),
            position.y.toDouble(),
        )
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val bitmap = currentImage ?: return@detectTransformGestures
                    val changed =
                        ReferenceGeometry.transformed(
                            currentView,
                            bitmap.width.toDouble(),
                            bitmap.height.toDouble(),
                            this.size.width.toDouble(),
                            this.size.height.toDouble(),
                            pan.x.toDouble(),
                            pan.y.toDouble(),
                            zoom.toDouble(),
                            centroid.x.toDouble(),
                            centroid.y.toDouble(),
                        )
                    reference.changeView(changed)
                }
            }.pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { position -> contentPoint(position)?.let { (x, y) -> currentOnPick?.invoke(x, y) } },
                    onTap = { position ->
                        if (session.eyedropperArmed) contentPoint(position)?.let { (x, y) -> currentOnPick?.invoke(x, y) }
                    },
                )
            },
    ) {
        size = IntSize(this.size.width.toInt(), this.size.height.toInt())
        val bitmap = imageBitmap ?: return@Canvas
        val contentWidth = bitmap.width.toDouble()
        val contentHeight = bitmap.height.toDouble()
        val scale = ReferenceGeometry.scale(view, contentWidth, contentHeight, this.size.width.toDouble(), this.size.height.toDouble())
        val left = this.size.width / 2 - view.centerX * contentWidth * scale
        val top = this.size.height / 2 - view.centerY * contentHeight * scale
        drawImage(
            bitmap,
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize((contentWidth * scale).roundToInt(), (contentHeight * scale).roundToInt()),
            filterQuality = if (scale >= 1) FilterQuality.None else FilterQuality.Medium,
        )
    }
}

@Composable
private fun ResizeGrip(
    modifier: Modifier,
    onResize: (Offset) -> Unit,
    onDone: () -> Unit,
) {
    val label = stringResource(R.string.reference_resize)
    Box(
        modifier
            .size(GRIP_SIZE)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, amount ->
                    change.consume()
                    onResize(amount)
                }
            },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(
            "◢",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp),
        )
    }
}

private const val STROKE_REFRESH_MILLIS = 250L
private const val MINIMUM_COMPOSITE = 256
private const val MAXIMUM_COMPOSITE = 2048
private val GRIP_SIZE = 32.dp
