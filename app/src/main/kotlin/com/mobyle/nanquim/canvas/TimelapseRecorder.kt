package com.mobyle.nanquim.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.gallery.VideoFileEncoder
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.formats.nanquim.TimelapseResolution
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

class TimelapseRecorder(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val drawing: () -> Boolean = { false },
) {
    var framesRecorded: Int by mutableIntStateOf(0)
        private set

    var framesSkipped = 0
        private set

    private val encoding = Executors.newSingleThreadExecutor { Thread(it, "NanquimTimelapse") }.asCoroutineDispatcher()
    private var folder: File? = null
    private var settings: TimelapseSettings? = null
    private var segment: Segment? = null
    private var pending: Job? = null
    private var lastRequestMillis = 0L
    private var busy = false
    private var state: DocumentState? = null

    private class Segment(
        val file: File,
        val encoder: VideoFileEncoder,
        val width: Int,
        val height: Int,
        val canvasWidth: Int,
        val canvasHeight: Int,
        var frames: Int = 0,
    )

    fun attach(
        folder: File?,
        settings: TimelapseSettings?,
    ) {
        if (folder != this.folder || settings != this.settings) finishSegment()
        this.folder = folder
        this.settings = settings
    }

    fun detach() {
        pending?.cancel()
        finishSegment()
        folder = null
        settings = null
    }

    fun pause() = finishSegment()

    fun onDocumentChanged(newState: DocumentState?) {
        state = newState
        if (newState == null || settings?.enabled != true || folder == null) return
        if (pending?.isActive == true) return
        val wait = lastRequestMillis + MINIMUM_INTERVAL_MILLIS - SystemClock.uptimeMillis()
        pending =
            scope.launch {
                if (wait > 0) delay(wait)
                while (drawing()) delay(DRAWING_POLL_MILLIS)
                capture()
            }
    }

    fun segmentsOf(folder: File): List<File> =
        File(folder, TimelapseSettings.FOLDER)
            .listFiles { file -> SEGMENT.matches(file.name) }
            ?.sortedBy { SEGMENT.find(it.name)!!.groupValues[1].toInt() }
            .orEmpty()

    fun purge(folder: File) {
        if (folder == this.folder) finishSegment()
        File(folder, TimelapseSettings.FOLDER).deleteRecursively()
    }

    private fun capture() {
        val state = state ?: return
        val settings = settings ?: return
        val folder = folder ?: return
        if (busy) {
            framesSkipped++
            return
        }
        lastRequestMillis = SystemClock.uptimeMillis()
        val (videoWidth, videoHeight) = videoSize(settings, state.widthPx, state.heightPx) ?: return
        val excluded = state.rasterLayers().filter { it.isPrivate }.map { it.id }.toIntArray()
        busy = true
        val fit = min(videoWidth.toDouble() / state.widthPx, videoHeight.toDouble() / state.heightPx)
        val fittedSide = ceil(maxOf(state.widthPx, state.heightPx) * fit)
        engine.requestTimelapseFrame((fittedSide * LEVEL_REACH).toInt(), excluded) { render ->
            if (render.error != null || render.width <= 0) {
                busy = false
                return@requestTimelapseFrame
            }
            scope.launch {
                try {
                    framesRecorded = withContext(encoding) {
                        encode(folder, settings, state, videoWidth, videoHeight, render.pixels, render.width, render.height)
                        segment?.frames ?: 0
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    private fun encode(
        folder: File,
        settings: TimelapseSettings,
        state: DocumentState,
        videoWidth: Int,
        videoHeight: Int,
        premultiplied: ByteArray,
        width: Int,
        height: Int,
    ) {
        var current = segment
        if (current != null && (current.canvasWidth != state.widthPx || current.canvasHeight != state.heightPx || current.width != videoWidth || current.height != videoHeight)) {
            finishSegmentNow(current)
            current = null
        }
        if (current == null) {
            val directory = File(folder, TimelapseSettings.FOLDER).apply { mkdirs() }
            val next = (segmentsOf(folder).lastOrNull()?.let { SEGMENT.find(it.name)!!.groupValues[1].toInt() } ?: 0) + 1
            val file = File(directory, "segment-$next.mp4")
            val mime = if (settings.hevc && VideoFileEncoder.supports(VideoFileEncoder.HEVC, videoWidth, videoHeight, FRAMES_PER_SECOND)) VideoFileEncoder.HEVC else VideoFileEncoder.AVC
            val encoder = VideoFileEncoder.create(file.path, mime, videoWidth, videoHeight, FRAMES_PER_SECOND, webm = false) ?: return
            current = Segment(file, encoder, videoWidth, videoHeight, state.widthPx, state.heightPx)
            segment = current
        }
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(ByteBuffer.wrap(premultiplied))
        val frame = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        canvas.drawColor(Color.BLACK)
        val scale = min(videoWidth.toFloat() / width, videoHeight.toFloat() / height)
        val left = (videoWidth - width * scale) / 2
        val top = (videoHeight - height * scale) / 2
        canvas.drawBitmap(source, Matrix().apply { setScale(scale, scale); postTranslate(left, top) }, Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = ByteBuffer.allocate(videoWidth * videoHeight * 4)
        frame.copyPixelsToBuffer(pixels)
        source.recycle()
        frame.recycle()
        current.encoder.addFrame(pixels.array(), current.frames * FRAME_MICROSECONDS)
        current.frames++
    }

    private fun finishSegment() {
        framesRecorded = 0
        scope.launch { withContext(encoding) { segment?.let(::finishSegmentNow) } }
    }

    suspend fun flush() {
        pending?.cancel()
        framesRecorded = 0
        withContext(encoding) { segment?.let(::finishSegmentNow) }
    }

    private fun finishSegmentNow(current: Segment) {
        if (segment === current) segment = null
        val ok = current.frames > 0 && current.encoder.finish(current.frames * FRAME_MICROSECONDS)
        current.encoder.release()
        if (!ok) current.file.delete()
    }

    private fun videoSize(
        settings: TimelapseSettings,
        canvasWidth: Int,
        canvasHeight: Int,
    ): Pair<Int, Int>? {
        val landscape = canvasWidth >= canvasHeight
        return when (val resolution = settings.resolution) {
            TimelapseResolution.Canvas -> VideoFileEncoder.largestSize(if (settings.hevc) VideoFileEncoder.HEVC else VideoFileEncoder.AVC, canvasWidth - canvasWidth % 2, canvasHeight - canvasHeight % 2)
            else -> if (landscape) resolution.longSidePixels to resolution.shortSidePixels else resolution.shortSidePixels to resolution.longSidePixels
        }
    }

    companion object {
        const val FRAMES_PER_SECOND = 30
        private const val FRAME_MICROSECONDS = 1_000_000L / FRAMES_PER_SECOND
        private const val MINIMUM_INTERVAL_MILLIS = 250L
        private const val DRAWING_POLL_MILLIS = 50L

        private const val LEVEL_REACH = 1.85
        private val SEGMENT = Regex("""segment-(\d+)\.mp4""")
    }
}
