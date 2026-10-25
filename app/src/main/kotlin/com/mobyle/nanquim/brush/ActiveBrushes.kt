package com.mobyle.nanquim.brush

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.brush.library.SliderState
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.engine.BrushData
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.StrokeTool
import kotlin.math.sqrt

data class BrushLimits(
    val minimumSizePx: Float,
    val maximumSizePx: Float,
    val minimumOpacity: Float,
    val maximumOpacity: Float,
) {
    fun sizeAt(slider: Float): Float = minimumSizePx + (maximumSizePx - minimumSizePx) * slider * slider

    fun sliderForSize(sizePx: Float): Float =
        sqrt(((sizePx - minimumSizePx) / (maximumSizePx - minimumSizePx).coerceAtLeast(MINIMUM_RANGE)).coerceIn(0f, 1f))

    fun opacityAt(slider: Float): Float = minimumOpacity + (maximumOpacity - minimumOpacity) * slider

    fun sliderForOpacity(opacity: Float): Float =
        ((opacity - minimumOpacity) / (maximumOpacity - minimumOpacity).coerceAtLeast(MINIMUM_RANGE)).coerceIn(0f, 1f)

    companion object {
        private const val MINIMUM_RANGE = 1e-6f

        val DEFAULT = BrushLimits(1f, 500f, 0f, 1f)

        fun of(document: BrushDocument): BrushLimits {
            val minimumSize = (document.number("limits.minSize") ?: 1.0).toFloat().coerceAtLeast(0.1f)
            val minimumOpacity = (document.number("limits.minOpacity") ?: 0.0).toFloat().coerceIn(0f, 1f)
            return BrushLimits(
                minimumSizePx = minimumSize,
                maximumSizePx = (document.number("limits.maxSize") ?: 500.0).toFloat().coerceAtLeast(minimumSize),
                minimumOpacity = minimumOpacity,
                maximumOpacity = (document.number("limits.maxOpacity") ?: 1.0).toFloat().coerceIn(minimumOpacity, 1f),
            )
        }
    }
}

data class ActiveBrush(
    val brushId: String,
    val document: BrushDocument,
    val sliders: SliderState,
) {
    val name: String get() = document.name
    val limits: BrushLimits get() = BrushLimits.of(document)
    val sizePx: Float get() = limits.sizeAt(sliders.sizeSlider)
    val opacity: Float get() = limits.opacityAt(sliders.opacitySlider)
}

class ActiveBrushes(
    private val engine: Engine,
    private val library: BrushLibrary,
    private val scope: CoroutineScope,
) {
    private val active = mutableStateMapOf<BrushTool, ActiveBrush>()

    var lastError: String? by mutableStateOf(null)
        private set

    private val saveJobs = mutableMapOf<BrushTool, Job>()

    operator fun get(tool: BrushTool): ActiveBrush? = active[tool]

    fun start(): Job =
        scope.launch {
            try {
                library.initialize()
                for (tool in PAINTING_TOOLS) {
                    library.brushFor(tool)?.let { activate(tool, it) }
                }
            } catch (exception: Exception) {
                report("Brush library failed to load", exception)
            }
        }

    fun select(
        tool: BrushTool,
        brushId: String,
    ): Job =
        scope.launch {
            try {
                library.select(tool, brushId)
                activate(tool, brushId)
            } catch (exception: Exception) {
                report("Cannot select brush $brushId", exception)
            }
        }

    fun reload(brushId: String): Job =
        scope.launch {
            for ((tool, brush) in active.toMap()) {
                if (brush.brushId == brushId) activate(tool, brushId)
            }
        }

    suspend fun preview(
        tool: BrushTool,
        brushPackage: BrushPackage,
    ): String? {
        val error = sendToEngine(tool, brushPackage)
        if (error == null) {
            active[tool]?.let { active[tool] = it.copy(document = brushPackage.document) }
        }
        return error
    }

    fun updateSliders(
        tool: BrushTool,
        sliders: SliderState,
    ) {
        val current = active[tool] ?: return
        val clamped = SliderState(sliders.sizeSlider.coerceIn(0f, 1f), sliders.opacitySlider.coerceIn(0f, 1f))
        active[tool] = current.copy(sliders = clamped)
        saveJobs[tool]?.cancel()
        saveJobs[tool] = scope.launch { library.saveSliderState(current.brushId, tool, clamped) }
    }

    private suspend fun activate(
        tool: BrushTool,
        brushId: String,
    ) {
        val brushPackage = library.loadPackage(brushId)
        val error = sendToEngine(tool, brushPackage)
        if (error != null) {
            report("Brush ${brushPackage.document.name} cannot be used: $error", null)
            return
        }
        val sliders = library.sliderState(brushId, tool)
        active[tool] = ActiveBrush(brushId, brushPackage.document, sliders)
        lastError = null
    }

    private suspend fun sendToEngine(
        tool: BrushTool,
        brushPackage: BrushPackage,
    ): String? {
        val strokeTool = strokeToolOf(tool)
        val dual = dualBrushOf(brushPackage)
        return withContext(Dispatchers.Default) {
            engine.setBrush(
                strokeTool,
                brushPackage.document.toJsonText(),
                brushPackage.shapeTextureBytes,
                brushPackage.grainTextureBytes,
                dual,
            )
        }
    }

    private suspend fun dualBrushOf(brushPackage: BrushPackage): BrushData? {
        val document = brushPackage.document
        if (document.boolean("dual.enabled") == false) return null
        val dualId = document.string("dual.brushId")?.takeIf { it.isNotEmpty() && it != document.id } ?: return null
        val dual =
            try {
                library.loadPackage(dualId)
            } catch (exception: NoSuchElementException) {
                report("Dual brush $dualId is not in the library", exception)
                return null
            }
        return BrushData(dual.document.toJsonText(), dual.shapeTextureBytes, dual.grainTextureBytes)
    }

    private fun report(
        message: String,
        exception: Exception?,
    ) {
        Log.e(TAG, message, exception)
        lastError = exception?.message?.let { "$message: $it" } ?: message
    }

    companion object {
        private const val TAG = "ActiveBrushes"

        val PAINTING_TOOLS = listOf(BrushTool.Paint, BrushTool.Smudge, BrushTool.Erase)

        fun strokeToolOf(tool: BrushTool): StrokeTool =
            when (tool) {
                BrushTool.Paint -> StrokeTool.Paint
                BrushTool.Erase -> StrokeTool.Erase
                BrushTool.Smudge -> StrokeTool.Smudge
            }
    }
}
