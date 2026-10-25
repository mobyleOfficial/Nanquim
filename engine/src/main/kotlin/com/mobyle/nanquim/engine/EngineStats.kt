package com.mobyle.nanquim.engine

import kotlinx.serialization.Serializable

@Serializable
data class EngineStats(
    val frames: FrameStats,
    val document: DocumentStats? = null,
    val memoryBudgetBytes: Long,
    val tiles: TileStats? = null,
    val composite: CompositeStats? = null,
    val stroke: StrokeStats? = null,
    val latency: LatencyStats? = null,
    val text: TextStats? = null,
    val history: HistoryStats? = null,
    val pendingSurfaceWrites: Int = 0,
)

@Serializable
data class HistoryStats(
    val entries: Long,
    val ramBytes: Long,
    val diskBytes: Long,
    val capturesInFlight: Long,
    val ramBudgetBytes: Long,
)

@Serializable
data class LatencyStats(
    val sampleCount: Int,
    val inputToDisplayMedianMilliseconds: Double,
    val inputToDisplayP95Milliseconds: Double,
    val frontBufferSampleCount: Int = 0,
    val inputToFrontBufferMedianMilliseconds: Double = 0.0,
    val inputToFrontBufferP95Milliseconds: Double = 0.0,
    val firstFrontBufferSampleCount: Int = 0,
    val inputToFirstFrontBufferMedianMilliseconds: Double = 0.0,
    val inputToFirstFrontBufferMaxMilliseconds: Double = 0.0,
    val frontBufferSupported: Boolean = false,
)

@Serializable
data class FrameStats(
    val sampleCount: Int,
    val intervalMedianMilliseconds: Double,
    val intervalP99Milliseconds: Double,
    val intervalMaxMilliseconds: Double,
    val droppedFrames: Int,
    val incompleteFrames: Int = 0,
    val cpuMedianMilliseconds: Double,
    val cpuP99Milliseconds: Double,
)

@Serializable
data class DocumentStats(
    val widthPixels: Int,
    val heightPixels: Int,
    val layerCount: Int,
)

@Serializable
data class TileStats(
    val count: Long,
    val gpuTiles: Long,
    val gpuBytes: Long,
    val gpuBudgetBytes: Long,
    val atlasPages: Long,
    val ramTiles: Long,
    val ramBytes: Long,
    val ramBudgetBytes: Long,
    val diskTiles: Long,
    val scratchFileBytes: Long,
    val loadsInFlight: Long,
    val readbacksInFlight: Long,
    val spillsInFlight: Long,
)

@Serializable
data class StrokeStats(
    val active: Boolean,
    val bufferTiles: Long,
    val bufferBytes: Long,
    val halfFloatBuffer: Boolean,
    val shaderVariantsCompiled: Int = 0,
    val shaderVariantsLoaded: Int = 0,
    val provisionalTilesRendered: Long,
)

@Serializable
data class CompositeStats(
    val dirtyTiles: Long,
    val tilesComposedLastFrame: Long,
    val pyramidTilesBuiltLastFrame: Long,
    val compositeBytes: Long,
    val cacheBytes: Long,
    val levelCount: Int,
    val lastSettleMilliseconds: Double,
    val lastVisibleResultMilliseconds: Double,
    val layerTileBudget: Long,
)

@Serializable
data class TextStats(
    val rasterCount: Int = 0,
    val rasterMedianMilliseconds: Double = 0.0,
    val rasterMaxMilliseconds: Double = 0.0,
)
