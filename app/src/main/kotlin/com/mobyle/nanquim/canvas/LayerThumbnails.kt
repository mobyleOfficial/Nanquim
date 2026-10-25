package com.mobyle.nanquim.canvas

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.engine.Engine

class LayerThumbnails(
    private val engine: Engine,
    private val scope: CoroutineScope,
) {
    val images = mutableStateMapOf<Int, ImageBitmap>()

    private var pending: Job? = null
    private var lastRequestMillis = 0L
    private var requestInFlight = false
    private var again = false

    fun invalidate(state: DocumentState) {
        if (pending?.isActive == true) return
        pending =
            scope.launch {
                val wait = lastRequestMillis + MINIMUM_INTERVAL_MILLIS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                request(state)
            }
    }

    private fun request(state: DocumentState) {
        if (requestInFlight) {
            again = true
            return
        }
        val layers = state.rasterLayers()
        val surfaces = layers.flatMap { layer -> listOfNotNull(layer.id to false, layer.mask?.let { it.id to true }) }
        images.keys.retainAll(surfaces.map { it.first }.toSet())
        if (surfaces.isEmpty()) return
        requestInFlight = true
        lastRequestMillis = System.currentTimeMillis()
        engine.requestThumbnails(surfaces.map { it.first }.toIntArray(), surfaces.map { it.second }.toBooleanArray(), MAX_SIDE_PX) { render ->
            requestInFlight = false
            if (render.error == null && render.width > 0) {
                val cellWidth = render.width / surfaces.size
                surfaces.forEachIndexed { index, (surface, _) ->
                    images[surface] = cell(render.pixels, render.width, render.height, index * cellWidth, cellWidth)
                }
            }
            if (again) {
                again = false
                invalidate(state)
            }
        }
    }

    private fun cell(
        pixels: ByteArray,
        stripWidth: Int,
        height: Int,
        x: Int,
        width: Int,
    ): ImageBitmap {
        val colors = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val offset = (row * stripWidth + x + column) * 4
                val red = pixels[offset].toInt() and 0xFF
                val green = pixels[offset + 1].toInt() and 0xFF
                val blue = pixels[offset + 2].toInt() and 0xFF
                val alpha = pixels[offset + 3].toInt() and 0xFF
                colors[row * width + column] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private companion object {
        const val MINIMUM_INTERVAL_MILLIS = 500L
        const val MAX_SIDE_PX = 96
    }
}
