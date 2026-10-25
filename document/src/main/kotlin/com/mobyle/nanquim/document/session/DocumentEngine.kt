package com.mobyle.nanquim.document.session

import com.mobyle.nanquim.document.layer.RgbaColor

enum class FillTarget {
    Layer,
    Mask,
    Selection,
}

enum class FillOperation {
    Color,
    Clear,
    Invert,
}

data class FillParameters(
    val sourceId: Int,
    val seedX: Double,
    val seedY: Double,
    val threshold: Double,
    val target: FillTarget,
    val surfaceId: Int,
    val color: RgbaColor = RgbaColor.BLACK,
    val selectionId: Int? = null,
    val combine: SelectionCombine = SelectionCombine.Replace,
    val whole: Boolean = false,
    val operation: FillOperation = FillOperation.Color,
    val expand: Boolean = true,
)

interface DocumentEngine {
    fun setLayerTree(layerTreeJson: String)

    fun setTextLayer(
        layerId: Int,
        modelJson: String,
    ) {}

    fun fillSurface(
        surfaceId: Int,
        color: RgbaColor,
        mask: Boolean,
        entry: Long,
    )

    fun clearSurface(
        surfaceId: Int,
        entry: Long,
    )

    fun invertSurface(
        surfaceId: Int,
        mask: Boolean,
        entry: Long,
    )

    fun copySurface(
        sourceId: Int,
        destinationId: Int,
    )

    fun invertSelection(
        selectionId: Int,
        entry: Long,
    )

    fun selectContents(
        selectionId: Int,
        layerId: Int,
        entry: Long,
    )

    fun selectPolygon(
        selectionId: Int,
        polygon: FloatArray,
        combine: SelectionCombine,
        entry: Long,
    )

    fun featherSelection(
        selectionId: Int,
        radius: Float,
        entry: Long,
    )

    fun beginFill(parameters: FillParameters)

    fun setFillThreshold(threshold: Double)

    fun endFill(
        commit: Boolean,
        entry: Long,
    )

    fun copyMasked(
        sourceId: Int,
        destinationId: Int,
        selectionId: Int,
    )

    fun beginTransform(
        layerId: Int,
        selectionId: Int?,
        requestId: Int,
    )

    fun setTransformMesh(
        mesh: FloatArray,
        filter: Int,
    )

    fun endTransform(
        commit: Boolean,
        entry: Long,
    )

    fun beginAdjustment(
        layerId: Int,
        selectionId: Int?,
        maskId: Int?,
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    )

    fun setAdjustment(
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    )

    fun endAdjustment(
        commit: Boolean,
        entry: Long,
    )

    fun remapSurface(
        surfaceId: Int,
        kind: Int,
        mesh: FloatArray,
        filter: Int,
        newWidth: Int,
        newHeight: Int,
        entry: Long,
    )

    fun setCanvasSize(
        widthPx: Int,
        heightPx: Int,
    )

    fun showSelection(selectionId: Int?)

    fun composeIntoSurface(
        targetId: Int,
        layerTreeJson: String,
        entry: Long,
    )

    fun discardSurfaces(surfaceIds: IntArray)

    fun applyHistory(
        entry: Long,
        undo: Boolean,
    )

    fun releaseHistory(entries: LongArray)
}
