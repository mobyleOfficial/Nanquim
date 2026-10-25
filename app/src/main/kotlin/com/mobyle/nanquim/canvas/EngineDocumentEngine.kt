package com.mobyle.nanquim.canvas

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.session.DocumentEngine
import com.mobyle.nanquim.document.session.FillParameters
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.FillRequest
import com.mobyle.nanquim.document.session.SelectionCombine as DocumentSelectionCombine
import com.mobyle.nanquim.engine.SelectionCombine as EngineSelectionCombine

fun Engine.asDocumentEngine(
    display: SelectionDisplay,
    dpi: () -> Double = { DEFAULT_DPI },
): DocumentEngine = EngineDocumentEngine(this, display, dpi)

private const val DEFAULT_DPI = 72.0

private const val TEXT_RASTER_DELAY_MILLISECONDS = 50L

class SelectionDisplay(
    private val engine: Engine,
) {
    private var selectionId = 0
    private var dimOutside = false

    fun show(selectionId: Int?) {
        this.selectionId = selectionId ?: 0
        engine.setSelectionDisplay(this.selectionId, dimOutside)
    }

    fun setDimOutside(dim: Boolean) {
        if (dim == dimOutside) return
        dimOutside = dim
        engine.setSelectionDisplay(selectionId, dimOutside)
    }
}

private class EngineDocumentEngine(
    private val engine: Engine,
    private val display: SelectionDisplay,
    private val dpi: () -> Double,
) : DocumentEngine {
    private val handler = Handler(Looper.getMainLooper())
    private val pendingText = linkedMapOf<Int, String>()
    private val sendText =
        Runnable {
            val dpi = dpi()
            pendingText.forEach { (layerId, model) -> engine.setTextLayer(layerId, model, dpi) }
            pendingText.clear()
        }

    override fun setLayerTree(layerTreeJson: String) {
        engine.setLayerTree(layerTreeJson)?.let { Log.e(TAG, "Layer tree rejected: $it") }
    }

    override fun setTextLayer(
        layerId: Int,
        modelJson: String,
    ) {
        pendingText[layerId] = modelJson
        handler.removeCallbacks(sendText)
        handler.postDelayed(sendText, TEXT_RASTER_DELAY_MILLISECONDS)
    }

    override fun fillSurface(
        surfaceId: Int,
        color: RgbaColor,
        mask: Boolean,
        entry: Long,
    ) = engine.fillSurface(surfaceId, color.red, color.green, color.blue, color.alpha, mask, entry)

    override fun clearSurface(
        surfaceId: Int,
        entry: Long,
    ) = engine.clearSurface(surfaceId, entry)

    override fun invertSurface(
        surfaceId: Int,
        mask: Boolean,
        entry: Long,
    ) = engine.invertSurface(surfaceId, mask, entry)

    override fun copySurface(
        sourceId: Int,
        destinationId: Int,
    ) = engine.copySurface(sourceId, destinationId)

    override fun invertSelection(
        selectionId: Int,
        entry: Long,
    ) = engine.invertSelection(selectionId, entry)

    override fun selectContents(
        selectionId: Int,
        layerId: Int,
        entry: Long,
    ) = engine.selectContents(selectionId, layerId, entry)

    override fun showSelection(selectionId: Int?) = display.show(selectionId)

    override fun selectPolygon(
        selectionId: Int,
        polygon: FloatArray,
        combine: DocumentSelectionCombine,
        entry: Long,
    ) = engine.selectPolygon(selectionId, polygon, EngineSelectionCombine.valueOf(combine.name), entry)

    override fun beginFill(parameters: FillParameters) =
        with(parameters) {
            engine.beginFill(
                FillRequest(
                    sourceId = sourceId,
                    seedX = seedX,
                    seedY = seedY,
                    threshold = threshold,
                    target =
                        com.mobyle.nanquim.engine.FillTarget
                            .valueOf(target.name),
                    surfaceId = surfaceId,
                    red = color.red,
                    green = color.green,
                    blue = color.blue,
                    alpha = color.alpha,
                    selectionId = selectionId ?: 0,
                    combine = EngineSelectionCombine.valueOf(combine.name),
                    expand = expand,
                    whole = whole,
                    operation =
                        com.mobyle.nanquim.engine.FillOperation
                            .valueOf(operation.name),
                ),
            )
        }

    override fun setFillThreshold(threshold: Double) = engine.setFillThreshold(threshold)

    override fun remapSurface(
        surfaceId: Int,
        kind: Int,
        mesh: FloatArray,
        filter: Int,
        newWidth: Int,
        newHeight: Int,
        entry: Long,
    ) = engine.remapSurface(surfaceId, kind, mesh, com.mobyle.nanquim.engine.TransformFilter.entries[filter], newWidth, newHeight, entry)

    override fun setCanvasSize(
        widthPx: Int,
        heightPx: Int,
    ) = engine.setCanvasSize(widthPx, heightPx)

    override fun beginTransform(
        layerId: Int,
        selectionId: Int?,
        requestId: Int,
    ) = engine.beginTransform(layerId, selectionId ?: 0, requestId)

    override fun setTransformMesh(
        mesh: FloatArray,
        filter: Int,
    ) = engine.setTransformMesh(mesh, com.mobyle.nanquim.engine.TransformFilter.entries[filter])

    override fun endTransform(
        commit: Boolean,
        entry: Long,
    ) = engine.endTransform(commit, entry)

    override fun beginAdjustment(
        layerId: Int,
        selectionId: Int?,
        maskId: Int?,
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) = engine.beginAdjustment(
        layerId,
        selectionId ?: 0,
        maskId ?: 0,
        com.mobyle.nanquim.engine.AdjustmentFilter.entries[filter],
        values,
        table,
        amount,
    )

    override fun setAdjustment(
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) = engine.setAdjustment(com.mobyle.nanquim.engine.AdjustmentFilter.entries[filter], values, table, amount)

    override fun endAdjustment(
        commit: Boolean,
        entry: Long,
    ) = engine.endAdjustment(commit, entry)

    override fun copyMasked(
        sourceId: Int,
        destinationId: Int,
        selectionId: Int,
    ) = engine.copyMasked(sourceId, destinationId, selectionId)

    override fun endFill(
        commit: Boolean,
        entry: Long,
    ) = engine.endFill(commit, entry)

    override fun featherSelection(
        selectionId: Int,
        radius: Float,
        entry: Long,
    ) = engine.featherSelection(selectionId, radius, entry)

    override fun composeIntoSurface(
        targetId: Int,
        layerTreeJson: String,
        entry: Long,
    ) {
        engine.composeIntoSurface(targetId, layerTreeJson, entry)?.let { Log.e(TAG, "Merge tree rejected: $it") }
    }

    override fun discardSurfaces(surfaceIds: IntArray) = engine.discardSurfaces(surfaceIds)

    override fun applyHistory(
        entry: Long,
        undo: Boolean,
    ) = engine.applyHistory(entry, undo)

    override fun releaseHistory(entries: LongArray) = engine.releaseHistory(entries)

    private companion object {
        const val TAG = "EngineDocumentEngine"
    }
}
