package com.mobyle.nanquim.ui.transition

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.mobyle.nanquim.R
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.engine.Point
import kotlin.math.min
import kotlin.math.roundToInt

class ArtworkMotion(
    val artworkId: String,
    val from: Rect?,
    val image: ImageBitmap?,
    val restored: Boolean = false,
)

class ArtworkTransitions {
    private val cells = mutableStateMapOf<String, Rect>()
    private val images = mutableStateMapOf<String, ImageBitmap>()

    var opening: ArtworkMotion? by mutableStateOf(null)
        private set

    var closing: ArtworkMotion? by mutableStateOf(null)
        private set

    fun cellPlaced(
        artworkId: String,
        bounds: Rect,
    ) {
        cells[artworkId] = bounds
    }

    fun imageLoaded(
        artworkId: String,
        image: ImageBitmap,
    ) {
        images[artworkId] = image
    }

    fun picture(artworkId: String): Rect? {
        val bounds = cells[artworkId] ?: return null
        val image = images[artworkId] ?: return bounds
        return fitted(image.width.toFloat(), image.height.toFloat(), bounds)
    }

    fun image(artworkId: String): ImageBitmap? = images[artworkId]

    fun prepareOpen(artworkId: String) {
        closing = null
        opening = ArtworkMotion(artworkId, picture(artworkId), images[artworkId])
    }

    fun prepareRestore(
        artworkId: String,
        image: ImageBitmap,
    ) {
        closing = null
        opening = ArtworkMotion(artworkId, null, image, restored = true)
    }

    fun prepareClose(
        artworkId: String,
        canvas: Rect?,
        picture: ImageBitmap?,
    ) {
        opening = null
        closing = ArtworkMotion(artworkId, canvas, picture ?: images[artworkId])
    }

    fun finish(motion: ArtworkMotion) {
        if (opening === motion) opening = null
        if (closing === motion) closing = null
    }

    companion object {
        fun fitted(
            width: Float,
            height: Float,
            bounds: Rect,
        ): Rect {
            if (width <= 0f || height <= 0f) return bounds
            val scale = min(bounds.width / width, bounds.height / height)
            return Rect(
                Offset(bounds.center.x - width * scale / 2, bounds.center.y - height * scale / 2),
                Size(
                    width * scale,
                    height * scale,
                ),
            )
        }
    }
}

fun CanvasSession.canvasRectOnScreen(): Rect? {
    val document = document ?: return null
    val toScreen = viewport.canvasToScreen() ?: return null
    val corners =
        listOf(
            Point(0.0, 0.0),
            Point(document.widthPx.toDouble(), 0.0),
            Point(0.0, document.heightPx.toDouble()),
            Point(document.widthPx.toDouble(), document.heightPx.toDouble()),
        ).map(toScreen::apply)
    return Rect(
        corners.minOf { it.x }.toFloat(),
        corners.minOf { it.y }.toFloat(),
        corners.maxOf { it.x }.toFloat(),
        corners.maxOf { it.y }.toFloat(),
    )
}

@Composable
fun ArtworkOpenOverlay(
    transitions: ArtworkTransitions,
    session: CanvasSession,
    galleryColor: Color,
    surroundColor: Color,
) {
    val motion = transitions.opening ?: return
    val progress = remember(motion) { Animatable(if (motion.restored) 1f else 0f) }
    val alpha = remember(motion) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(motion) {
        progress.animateTo(1f, tween(OPEN_MILLIS, easing = FastOutSlowInEasing))
        merge(snapshotFlow { session.documentShown }.filter { it }, session.engineFailure.filterNotNull()).first()
        alpha.animateTo(0f, tween(FADE_MILLIS))
        transitions.finish(motion)
    }

    @Suppress("UNUSED_VARIABLE")
    val cameraVersion = session.viewport.cameraVersion
    val target = session.canvasRectOnScreen()
    val radius = with(LocalDensity.current) { CELL_CORNER.toPx() }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .pointerInput(motion) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    scope.launch {
                        progress.snapTo(1f)
                        alpha.snapTo(0f)
                        transitions.finish(motion)
                    }
                }
            },
    ) {
        val t = progress.value
        drawRect(lerp(galleryColor, surroundColor, t))
        val end = target ?: fittedTarget(motion, size, session)
        val start = motion.from ?: end.scaleAround(START_SCALE)
        drawPicture(motion.image, lerp(start, end, t), radius * (1 - t))
    }
}

@Composable
fun ArtworkLoadingIndicator(session: CanvasSession) = BlockingLoadingIndicator(session.loading)

@Composable
fun BlockingLoadingIndicator(loading: Boolean) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        shown = false
        if (loading) {
            delay(LOADING_DELAY_MILLIS)
            shown = true
        }
    }
    val description = stringResource(R.string.artwork_loading)
    val blockTouches =
        if (loading) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        } else {
            Modifier
        }
    Box(Modifier.fillMaxSize().then(blockTouches), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = loading && shown, enter = fadeIn(tween(FADE_MILLIS)), exit = fadeOut(tween(FADE_MILLIS))) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = LOADING_BACKDROP_ALPHA),
                modifier = Modifier.semantics { contentDescription = description },
            ) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(32.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
fun ArtworkClosingCover(
    transitions: ArtworkTransitions,
    surroundColor: Color,
) {
    val motion = transitions.closing ?: return
    Canvas(Modifier.fillMaxSize().pointerInput(motion) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }) {
        drawRect(surroundColor)
        motion.from?.let { drawPicture(motion.image, it, 0f) }
    }
}

@Composable
fun ArtworkCloseOverlay(
    transitions: ArtworkTransitions,
    surroundColor: Color,
) {
    val motion = transitions.closing ?: return
    val progress = remember(motion) { Animatable(0f) }
    var cell by remember(motion) { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(motion) {
        withTimeoutOrNull(CELL_WAIT_MILLIS) {
            snapshotFlow { transitions.picture(motion.artworkId) }.first { it != null }
        }
        cell = transitions.picture(motion.artworkId)
        progress.animateTo(1f, tween(CLOSE_MILLIS, easing = FastOutSlowInEasing))
        transitions.finish(motion)
    }
    val radius = with(LocalDensity.current) { CELL_CORNER.toPx() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(motion) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                scope.launch { transitions.finish(motion) }
            }
        },
    ) {
        val t = progress.value
        drawRect(surroundColor.copy(alpha = surroundColor.alpha * (1 - t)))
        val start = motion.from ?: Rect(Offset.Zero, size).scaleAround(1f - START_SCALE)
        val end = cell
        if (end != null) {
            drawPicture(motion.image, lerp(start, end, t), radius * t)
        } else {
            drawPicture(motion.image, start.scaleAround(1 - t * START_SCALE), 0f, alpha = 1 - t)
        }
    }
}

private fun fittedTarget(
    motion: ArtworkMotion,
    size: Size,
    session: CanvasSession,
): Rect {
    val document = session.document
    val width = document?.widthPx?.toFloat() ?: motion.image?.width?.toFloat() ?: size.width
    val height = document?.heightPx?.toFloat() ?: motion.image?.height?.toFloat() ?: size.height
    return ArtworkTransitions.fitted(width, height, Rect(Offset.Zero, size)).scaleAround(1f - FIT_MARGIN)
}

private fun Rect.scaleAround(scale: Float): Rect =
    Rect(
        Offset(center.x - width * scale / 2, center.y - height * scale / 2),
        Size(width * scale, height * scale),
    )

private fun DrawScope.drawPicture(
    image: ImageBitmap?,
    rect: Rect,
    cornerRadius: Float,
    alpha: Float = 1f,
) {
    val path =
        androidx.compose.ui.graphics.Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry
                    .RoundRect(rect, CornerRadius(cornerRadius)),
            )
        }
    clipPath(path) {
        if (image == null) {
            drawRect(Color.White.copy(alpha = alpha), rect.topLeft, rect.size)
        } else {
            drawImage(
                image,
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                alpha = alpha,
            )
        }
    }
}

private const val OPEN_MILLIS = 320
private const val CLOSE_MILLIS = 280
private const val FADE_MILLIS = 120
private const val LOADING_DELAY_MILLIS = 400L
private const val LOADING_BACKDROP_ALPHA = 0.85f
private const val CELL_WAIT_MILLIS = 400L
private const val START_SCALE = 0.85f
private const val FIT_MARGIN = 0.06f
private val CELL_CORNER = 8.dp
