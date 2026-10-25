package com.mobyle.nanquim.canvas

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.VideoLayerContent
import com.mobyle.nanquim.document.layer.videoLayers
import com.mobyle.nanquim.engine.Engine
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min

class VideoLayers(
    private val engine: Engine,
    private val scope: CoroutineScope,
    private val contentResolver: ContentResolver,
    private val folder: () -> File?,
) {
    private data class Shown(
        val content: VideoLayerContent,
        val width: Int,
        val height: Int,
    )

    private var shown: Map<Int, Shown> = emptyMap()
    private val jobs = HashMap<Int, Job>()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()

    private val frameTimes = HashMap<String, LongArray>()

    data class Imported(
        val content: VideoLayerContent,
        val name: String,
    )

    suspend fun import(
        uri: Uri,
        displayName: String,
    ): Imported? =
        withContext(Dispatchers.IO) {
            val videos = File(folder() ?: return@withContext null, VideoLayerContent.FOLDER).apply { mkdirs() }
            val extension = displayName.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "mp4"
            val file = File(videos, "${UUID.randomUUID()}.$extension")
            try {
                val input = contentResolver.openInputStream(uri) ?: return@withContext null
                input.use { stream ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAXIMUM_VIDEO_BYTES) throw IOException("Video too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                val retriever = MediaMetadataRetriever()
                val duration =
                    try {
                        retriever.setDataSource(file.path)
                        if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null
                        else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        retriever.release()
                    }
                if (duration == null) {
                    file.delete()
                    return@withContext null
                }
                Imported(VideoLayerContent(file.name, 0, duration * 1000), displayName.substringBeforeLast('.').ifEmpty { displayName })
            } catch (error: Exception) {
                file.delete()
                null
            }
        }

    fun onDocumentChanged(state: DocumentState?) {
        val videos = state?.videoLayers().orEmpty()
        val wanted = videos.mapValues { (_, content) -> Shown(content, state!!.widthPx, state.heightPx) }
        for ((id, target) in wanted) {
            if (shown[id] == target) continue
            jobs.remove(id)?.cancel()
            jobs[id] = scope.launch { if (write(id, target)) shown = shown + (id to target) }
        }
        shown = shown.filterKeys { it in wanted }
    }

    fun reset() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        shown = emptyMap()
        retrievers.values.forEach { runCatching { it.release() } }
        retrievers.clear()
        synchronized(frameTimes) { frameTimes.clear() }
    }

    suspend fun idle() {
        jobs.values.toList().forEach { it.join() }
    }

    private suspend fun write(
        id: Int,
        target: Shown,
    ): Boolean {
        val file = File(folder() ?: return false, VideoLayerContent.FOLDER + target.content.file)
        val frame =
            withContext(Dispatchers.IO) {
                if (!file.isFile) return@withContext null
                val retriever = retrievers.getOrPut(file.path) { MediaMetadataRetriever().apply { setDataSource(file.path) } }
                val times = synchronized(frameTimes) { frameTimes.getOrPut(file.path) { frameTimesOf(file) } }
                val index = times.binarySearch(target.content.timeMicroseconds).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
                val time = times.getOrElse(index) { target.content.timeMicroseconds }
                synchronized(retriever) { retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST) }
            } ?: return false
        withContext(Dispatchers.Default) {
            val scale = min(target.width.toFloat() / frame.width, target.height.toFloat() / frame.height)
            val left = (target.width - frame.width * scale) / 2
            val top = (target.height - frame.height * scale) / 2
            val band = Bitmap.createBitmap(target.width, TILE_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(band)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val pixels = ByteBuffer.allocate(target.width * TILE_SIZE * 4)
            for (row in 0 until (target.height + TILE_SIZE - 1) / TILE_SIZE) {
                band.eraseColor(0)
                canvas.drawBitmap(frame, Matrix().apply { setScale(scale, scale); postTranslate(left, top - row * TILE_SIZE) }, paint)
                pixels.clear()
                band.copyPixelsToBuffer(pixels)
                val rows = min(TILE_SIZE, target.height - row * TILE_SIZE)
                engine.writeSurfaceTileRow(id, row, pixels.array().copyOf(target.width * rows * 4))
            }
            band.recycle()
            frame.recycle()
        }
        return true
    }

    private fun frameTimesOf(file: File): LongArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return LongArray(0)
            extractor.selectTrack(track)
            val times = ArrayList<Long>()
            while (extractor.sampleTime >= 0) {
                times += extractor.sampleTime
                extractor.advance()
            }
            return times.toLongArray().also { it.sort() }
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val BUFFER_BYTES = 64 * 1024

        const val MAXIMUM_VIDEO_BYTES = 256L * 1024 * 1024
    }
}
