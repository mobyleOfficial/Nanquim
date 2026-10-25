package com.mobyle.nanquim.engine

import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class EngineConfig(
    val memoryBudgetBytes: Long,
    val scratchDirectory: String,
    val renderer: Renderer = Renderer.Gles,
)

enum class Renderer(
    internal val nativeValue: Int,
) {
    Gles(0),
    Vulkan(1),
}

data class ViewportStyle(
    val surroundRed: Float,
    val surroundGreen: Float,
    val surroundBlue: Float,
    val shadowStrength: Float = 0.35f,
    val shadowRadiusPx: Float = 14f,
    val showPixels: Boolean = false,
)

enum class StrokeTool(
    internal val nativeValue: Int,
) {
    Paint(0),
    Erase(1),

    Smudge(2),
}

class BrushData(
    val brushJson: String,
    val shapePng: ByteArray?,
    val grainPng: ByteArray?,
)

data class StrokeStyle(
    val sizePx: Float,
    val opacity: Float,
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val alpha: Float = 1f,
    val secondaryRed: Float = 1f,
    val secondaryGreen: Float = 1f,
    val secondaryBlue: Float = 1f,
    val maskTarget: Boolean = false,
    val historyEntry: Long = 0,
    val selectionId: Int = 0,
    val selectionTarget: Boolean = false,
    val clone: CloneSource? = null,
    val symmetry: List<AffineTransform> = emptyList(),
)

data class CloneSource(
    val composite: Boolean,
    val layerId: Int,
    val offsetX: Int,
    val offsetY: Int,
)

enum class SelectionCombine(
    internal val nativeValue: Int,
) {
    Replace(0),
    Add(1),
    Subtract(2),
    Intersect(3),
}

enum class FillTarget {
    Layer,
    Mask,
    Selection,
}

data class FillRequest(
    val sourceId: Int,
    val seedX: Double,
    val seedY: Double,
    val threshold: Double,
    val target: FillTarget,
    val surfaceId: Int,
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val alpha: Float = 1f,
    val selectionId: Int = 0,
    val combine: SelectionCombine = SelectionCombine.Replace,
    val expand: Boolean = true,
    val whole: Boolean = false,
    val operation: FillOperation = FillOperation.Color,
)

enum class FillOperation {
    Color,
    Clear,
    Invert,
}

enum class AdjustmentFilter(
    val commandName: String,
    val parameterNames: List<String>,
) {
    HueSaturationBrightness("hueSaturationBrightness", listOf("hue", "saturation", "brightness")),

    ColorBalance(
        "colorBalance",
        listOf("shadows", "midtones", "highlights").flatMap { range ->
            listOf("CyanRed", "MagentaGreen", "YellowBlue").map { axis -> range + axis }
        },
    ),

    Curves("curves", emptyList()),

    GradientMap("gradientMap", listOf("amount")),

    GaussianBlur("gaussianBlur", listOf("radius")),

    MotionBlur("motionBlur", listOf("amount", "angle")),

    PerspectiveBlur("perspectiveBlur", listOf("amount", "x", "y", "directional", "angle")),

    Sharpen("sharpen", listOf("amount")),

    Noise("noise", listOf("amount", "scale", "octaves", "turbulence", "type")),

    Bloom("bloom", listOf("size", "transition", "burn")),

    Glitch("glitch", listOf("amount", "blockSize", "zoom", "frequency", "type")),

    Halftone("halftone", listOf("dotSize", "style")),

    ChromaticAberration("chromaticAberration", listOf("amount", "x", "y", "displace", "angle", "falloff", "transparency")),

    Liquify("liquify", listOf("size", "pressure", "distortion", "momentum", "adjust", "liquifyMode")),
    ;

    val usesTable: Boolean get() = this == Curves || this == GradientMap
}

enum class LiquifyMode {
    Push,
    TwirlRight,
    TwirlLeft,
    Pinch,
    Expand,
    Crystals,
    Edge,
    Reconstruct,
}

enum class TransformFilter {
    Nearest,
    Bilinear,
    Bicubic,
}

class SceneRender(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val error: String?,
)

class Engine(
    config: EngineConfig,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = ConcurrentHashMap<Int, (String) -> Unit>()
    private val pendingSceneCallbacks = ConcurrentHashMap<Int, (SceneRender) -> Unit>()
    private val pendingSaveCallbacks = ConcurrentHashMap<Int, (DocumentSaveResult) -> Unit>()
    private val nextRequestId = AtomicInteger(1)
    private val mutableEvents = MutableSharedFlow<EngineEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val mutableUnrecoverableError = MutableStateFlow<EngineEvent.Error?>(null)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var nativeHandle: Long =
        nativeCreate(config.memoryBudgetBytes, config.scratchDirectory, config.renderer.nativeValue).also { handle ->
            check(handle != 0L) { "Native engine creation failed" }
        }

    val events: SharedFlow<EngineEvent> = mutableEvents.asSharedFlow()

    val unrecoverableError: StateFlow<EngineEvent.Error?> = mutableUnrecoverableError.asStateFlow()

    fun attachSurface(
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
    ) = nativeAttachSurface(requireHandle(), surface, widthPx, heightPx)

    fun detachSurface() = nativeDetachSurface(requireHandle())

    fun setClearColor(
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float = 1f,
    ) = nativeSetClearColor(requireHandle(), red, green, blue, alpha)

    fun setLatencyTestPoint(
        xPx: Float,
        yPx: Float,
    ) = nativeSetLatencyTestPoint(requireHandle(), xPx, yPx, true)

    fun hideLatencyTestPoint() = nativeSetLatencyTestPoint(requireHandle(), 0f, 0f, false)

    fun openTestDocument(
        widthPx: Int,
        heightPx: Int,
        layerCount: Int,
        background: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    ) = nativeOpenTestDocument(requireHandle(), widthPx, heightPx, layerCount, background[0], background[1], background[2], background[3])

    fun openDocument(
        widthPx: Int,
        heightPx: Int,
        layerTreeJson: String,
    ): String? = nativeOpenDocument(requireHandle(), widthPx, heightPx, layerTreeJson)

    fun setLayerTree(layerTreeJson: String): String? = nativeSetLayerTree(requireHandle(), layerTreeJson)

    fun openDocumentFolder(
        widthPx: Int,
        heightPx: Int,
        layerTreeJson: String,
        documentId: String,
        folder: String,
        surfaces: List<SurfaceFolder>,
    ): String? =
        nativeOpenDocumentFolder(
            requireHandle(),
            widthPx,
            heightPx,
            layerTreeJson,
            documentId,
            folder,
            IntArray(surfaces.size) { surfaces[it].surfaceId },
            Array(surfaces.size) { surfaces[it].folder },
            IntArray(surfaces.size) { surfaces[it].formatValue },
        )

    fun saveDocument(
        surfaces: List<SurfaceFolder>,
        files: Map<String, ByteArray>,
        callback: (DocumentSaveResult) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSaveCallbacks[requestId] = callback
        val names = files.keys.toTypedArray()
        nativeSaveDocument(
            requireHandle(),
            requestId,
            IntArray(surfaces.size) { surfaces[it].surfaceId },
            Array(surfaces.size) { surfaces[it].folder },
            IntArray(surfaces.size) { surfaces[it].formatValue },
            names,
            Array(names.size) { files.getValue(names[it]) },
        )
    }

    fun writeSurfaceTileRow(
        surfaceId: Int,
        tileRow: Int,
        premultipliedRgba: ByteArray,
    ) = nativeWriteSurfaceTileRow(requireHandle(), surfaceId, tileRow, premultipliedRgba)

    fun requestCompositeThumbnail(
        maxSide: Int,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeRequestCompositeThumbnail(requireHandle(), requestId, maxSide)
    }

    fun requestCompositePreview(
        maxSide: Int,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeRequestCompositePreview(requireHandle(), requestId, maxSide)
    }

    fun requestTimelapseFrame(
        maxSide: Int,
        excludedLayers: IntArray,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeRequestTimelapseFrame(requireHandle(), requestId, maxSide, excludedLayers)
    }

    fun closeDocument() = nativeCloseDocument(requireHandle())

    fun setLayerVisible(
        layerId: Int,
        visible: Boolean,
    ) = nativeSetLayerVisible(requireHandle(), layerId, visible)

    fun setLayerOpacity(
        layerId: Int,
        opacity: Float,
    ) = nativeSetLayerOpacity(requireHandle(), layerId, opacity)

    fun setActiveLayer(layerId: Int) = nativeSetActiveLayer(requireHandle(), layerId)

    fun setBackground(
        color: FloatArray,
        hidden: Boolean,
    ) = nativeSetBackground(requireHandle(), color[0], color[1], color[2], color[3], hidden)

    fun fillRandomTiles(
        layerId: Int,
        tileCount: Int,
        seed: Int,
    ) = nativeFillRandomTiles(requireHandle(), layerId, tileCount, seed)

    fun setCamera(canvasToScreen: AffineTransform) = with(canvasToScreen) { nativeSetCamera(requireHandle(), a, b, c, d, tx, ty) }

    fun setViewportStyle(style: ViewportStyle) =
        with(style) {
            nativeSetViewportStyle(
                requireHandle(),
                surroundRed,
                surroundGreen,
                surroundBlue,
                0f,
                0f,
                0f,
                shadowStrength,
                shadowRadiusPx,
                showPixels,
            )
        }

    fun setDisplayColor(
        documentProfile: ByteArray?,
        proofProfile: ByteArray?,
        displayP3: Boolean,
    ): String? = nativeSetDisplayColor(requireHandle(), documentProfile, proofProfile, displayP3)

    fun setDirtyTileOverlay(enabled: Boolean) = nativeSetDirtyTileOverlay(requireHandle(), enabled)

    fun keepGpuAwake(milliseconds: Int) = nativeKeepGpuAwake(requireHandle(), milliseconds)

    fun setMemoryBudget(bytes: Long) = nativeSetMemoryBudget(requireHandle(), bytes)

    fun requestGpuInfo(callback: (GpuInfo) -> Unit) = request(GpuInfo.serializer(), callback, ::nativeRequestGpuInfo)

    fun setBrush(
        tool: StrokeTool,
        brushJson: String?,
        shapePng: ByteArray? = null,
        grainPng: ByteArray? = null,
        dual: BrushData? = null,
    ): String? =
        nativeSetBrush(
            requireHandle(),
            tool.nativeValue,
            brushJson,
            shapePng,
            grainPng,
            dual?.brushJson,
            dual?.shapePng,
            dual?.grainPng,
        )

    fun setPressureCurve(points: List<Pair<Float, Float>>?) =
        nativeSetPressureCurve(requireHandle(), points?.flatMap { listOf(it.first, it.second) }?.toFloatArray())

    fun registerFont(
        fontId: String,
        path: String? = null,
        data: ByteArray? = null,
        faceIndex: Int = 0,
    ) {
        require(path != null || data != null) { "A font needs a path or its bytes" }
        nativeRegisterFont(requireHandle(), fontId, path, data, faceIndex)
    }

    fun setFontFallbacks(fontIds: List<String>) = nativeSetFontFallbacks(requireHandle(), fontIds.toTypedArray())

    fun setTextLayer(
        layerId: Int,
        modelJson: String?,
        dpi: Double,
    ) = nativeSetTextLayer(requireHandle(), layerId, modelJson, dpi)

    fun requestTextGeometry(
        modelJson: String,
        dpi: Double,
        callback: (TextGeometry) -> Unit,
    ) = request(TextGeometry.serializer(), callback) { handle, requestId ->
        nativeRequestTextGeometry(handle, requestId, modelJson, dpi)
    }

    fun setGlobalSmoothing(amount: Float) = nativeSetGlobalSmoothing(requireHandle(), amount.coerceIn(0f, 1f))

    fun setStylusRoll(reported: Boolean) = nativeSetStylusRoll(requireHandle(), reported)

    fun beginStroke(
        tool: StrokeTool,
        layerId: Int,
        style: StrokeStyle,
    ) = with(style) {
        nativeBeginStroke(
            requireHandle(),
            tool.nativeValue,
            layerId,
            sizePx,
            opacity,
            red,
            green,
            blue,
            alpha,
            secondaryRed,
            secondaryGreen,
            secondaryBlue,
            maskTarget,
            historyEntry,
            selectionId,
            selectionTarget,
            when {
                clone == null -> 0
                clone.composite -> 1
                else -> 2
            },
            clone?.layerId ?: 0,
            clone?.offsetX ?: 0,
            clone?.offsetY ?: 0,
            symmetry.takeIf { it.isNotEmpty() }?.flatMap { listOf(it.a, it.b, it.c, it.d, it.tx, it.ty) }?.toDoubleArray(),
        )
    }

    fun fillSurface(
        surfaceId: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        mask: Boolean,
        historyEntry: Long,
    ) = nativeFillSurface(requireHandle(), surfaceId, red, green, blue, alpha, mask, historyEntry)

    fun clearSurface(
        surfaceId: Int,
        historyEntry: Long,
    ) = nativeClearSurface(requireHandle(), surfaceId, historyEntry)

    fun invertSurface(
        surfaceId: Int,
        mask: Boolean,
        historyEntry: Long,
    ) = nativeInvertSurface(requireHandle(), surfaceId, mask, historyEntry)

    fun invertSelection(
        selectionId: Int,
        historyEntry: Long,
    ) = nativeInvertSelection(requireHandle(), selectionId, historyEntry)

    fun setSelectionDisplay(
        selectionId: Int,
        dimOutside: Boolean,
    ) = nativeSetSelectionDisplay(requireHandle(), selectionId, dimOutside)

    fun selectPolygon(
        selectionId: Int,
        polygon: FloatArray,
        combine: SelectionCombine,
        historyEntry: Long,
    ) = nativeSelectPolygon(requireHandle(), selectionId, polygon, combine.nativeValue, historyEntry)

    fun featherSelection(
        selectionId: Int,
        radius: Float,
        historyEntry: Long,
    ) = nativeFeatherSelection(requireHandle(), selectionId, radius.coerceIn(0f, 100f), historyEntry)

    fun beginFill(request: FillRequest) =
        with(request) {
            nativeBeginFill(
                requireHandle(),
                sourceId,
                seedX,
                seedY,
                threshold,
                expand,
                whole,
                target.ordinal,
                surfaceId,
                red,
                green,
                blue,
                alpha,
                selectionId,
                combine.nativeValue,
                operation.ordinal,
            )
        }

    fun setFillThreshold(threshold: Double) = nativeSetFillThreshold(requireHandle(), threshold.coerceIn(0.0, 1.0))

    fun endFill(
        commit: Boolean,
        historyEntry: Long,
    ) = nativeEndFill(requireHandle(), commit, historyEntry)

    fun copyMasked(
        sourceId: Int,
        destinationId: Int,
        selectionId: Int,
    ) = nativeCopyMasked(requireHandle(), sourceId, destinationId, selectionId)

    fun beginTransform(
        layerId: Int,
        selectionId: Int,
        requestId: Int,
    ) = nativeBeginTransform(requireHandle(), layerId, selectionId, requestId)

    fun setTransformMesh(
        mesh: FloatArray,
        filter: TransformFilter,
    ) = nativeSetTransformMesh(requireHandle(), mesh, filter.ordinal)

    fun beginAdjustment(
        layerId: Int,
        selectionId: Int,
        maskId: Int,
        filter: AdjustmentFilter,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) = nativeBeginAdjustment(requireHandle(), layerId, selectionId, maskId, filter.ordinal, values, table, amount)

    fun setAdjustment(
        filter: AdjustmentFilter,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) = nativeSetAdjustment(requireHandle(), filter.ordinal, values, table, amount)

    fun endAdjustment(
        commit: Boolean,
        historyEntry: Long,
    ) = nativeEndAdjustment(requireHandle(), commit, historyEntry)

    fun beginLiquifyStroke(
        mode: LiquifyMode,
        size: Float,
        pressure: Float,
        distortion: Float,
        momentum: Float,
        usePressure: Boolean,
        seed: Int,
    ) = nativeBeginLiquifyStroke(requireHandle(), mode.ordinal, size, pressure, distortion, momentum, usePressure, seed)

    fun addLiquifySamples(samples: FloatArray) = nativeAddLiquifySamples(requireHandle(), samples)

    fun endLiquifyStroke(cancel: Boolean = false) = nativeEndLiquifyStroke(requireHandle(), cancel)

    fun liquifyStep(undo: Boolean) = nativeLiquifyStep(requireHandle(), undo)

    fun scaleLiquify(factor: Float) = nativeScaleLiquify(requireHandle(), factor)

    fun endTransform(
        commit: Boolean,
        historyEntry: Long,
    ) = nativeEndTransform(requireHandle(), commit, historyEntry)

    fun remapSurface(
        surfaceId: Int,
        kind: Int,
        mesh: FloatArray,
        filter: TransformFilter,
        newWidth: Int,
        newHeight: Int,
        historyEntry: Long,
    ) = nativeRemapSurface(requireHandle(), surfaceId, kind, mesh, filter.ordinal, newWidth, newHeight, historyEntry)

    fun setCanvasSize(
        width: Int,
        height: Int,
    ) = nativeSetCanvasSize(requireHandle(), width, height)

    fun selectContents(
        selectionId: Int,
        layerId: Int,
        historyEntry: Long,
    ) = nativeSelectContents(requireHandle(), selectionId, layerId, historyEntry)

    fun copySurface(
        sourceId: Int,
        destinationId: Int,
    ) = nativeCopySurface(requireHandle(), sourceId, destinationId)

    fun composeIntoSurface(
        targetId: Int,
        layerTreeJson: String,
        historyEntry: Long,
    ): String? = nativeComposeIntoSurface(requireHandle(), targetId, layerTreeJson, historyEntry)

    fun discardSurfaces(surfaceIds: IntArray) = nativeDiscardSurfaces(requireHandle(), surfaceIds)

    fun prefetchSurfaces(surfaceIds: IntArray) = nativePrefetchSurfaces(requireHandle(), surfaceIds)

    fun applyHistory(
        historyEntry: Long,
        undo: Boolean,
    ) = nativeApplyHistory(requireHandle(), historyEntry, undo)

    fun releaseHistory(entries: LongArray) = nativeReleaseHistory(requireHandle(), entries)

    fun setHistoryRamBudget(bytes: Long) = nativeSetHistoryRamBudget(requireHandle(), bytes)

    fun addStrokeSamples(samples: StrokeSampleBuffer) {
        if (samples.count == 0) return
        nativeAddStrokeSamples(requireHandle(), samples.byteBuffer, samples.byteCount)
    }

    fun replaceStroke(samples: StrokeSampleBuffer) {
        if (samples.count == 0) return
        nativeReplaceStroke(requireHandle(), samples.byteBuffer, samples.byteCount)
    }

    fun endStroke() = nativeEndStroke(requireHandle())

    fun cancelStroke() = nativeCancelStroke(requireHandle())

    fun renderScene(
        sceneJson: String,
        files: Map<String, ByteArray>,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        val entries = files.entries.toList()
        nativeRenderScene(
            requireHandle(),
            requestId,
            sceneJson,
            entries.map { it.key }.toTypedArray(),
            entries.map { it.value }.toTypedArray(),
        )
    }

    fun readComposite(callback: (SceneRender) -> Unit) = readComposite(deep = false, callback)

    fun renderViewport(
        widthPixels: Int,
        heightPixels: Int,
        canvasToScreen: DoubleArray,
        showPixels: Boolean,
        selectionSurface: Long,
        dimOutsideSelection: Boolean,
        antsPhasePixels: Float,
        callback: (SceneRender) -> Unit,
    ) {
        require(canvasToScreen.size == 6) { "canvasToScreen needs a, b, c, d, tx, ty" }
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeRenderViewport(
            requireHandle(),
            requestId,
            widthPixels,
            heightPixels,
            canvasToScreen[0],
            canvasToScreen[1],
            canvasToScreen[2],
            canvasToScreen[3],
            canvasToScreen[4],
            canvasToScreen[5],
            showPixels,
            selectionSurface,
            dimOutsideSelection,
            antsPhasePixels,
        )
    }

    fun readComposite(
        deep: Boolean,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeReadComposite(requireHandle(), requestId, deep)
    }

    fun requestThumbnails(
        surfaceIds: IntArray,
        masks: BooleanArray,
        maxSide: Int,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeRequestThumbnails(requireHandle(), requestId, surfaceIds, masks, maxSide)
    }

    fun sampleColor(
        canvasX: Double,
        canvasY: Double,
        size: Int,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeSampleColor(requireHandle(), requestId, canvasX, canvasY, size)
    }

    fun sampleLayerAlphas(
        canvasX: Int,
        canvasY: Int,
        surfaceIds: IntArray,
        callback: (SceneRender) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingSceneCallbacks[requestId] = callback
        nativeSampleLayerAlphas(requireHandle(), requestId, canvasX, canvasY, surfaceIds)
    }

    fun setFrontBufferEnabled(enabled: Boolean) = nativeSetFrontBufferEnabled(requireHandle(), enabled)

    fun resetFrameStatistics() = nativeResetFrameStatistics(requireHandle())

    fun requestStats(callback: (EngineStats) -> Unit) = request(EngineStats.serializer(), callback, ::nativeRequestStats)

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        nativeDestroy(handle)
        pendingCallbacks.clear()
    }

    private fun <Result> request(
        serializer: KSerializer<Result>,
        callback: (Result) -> Unit,
        nativeRequest: (Long, Int) -> Unit,
    ) {
        val requestId = nextRequestId.getAndIncrement()
        pendingCallbacks[requestId] = { jsonText ->
            mainHandler.post {
                try {
                    callback(json.decodeFromString(serializer, jsonText))
                } catch (exception: SerializationException) {
                    mutableEvents.tryEmit(EngineEvent.Error(EngineErrorCode.InvalidReport, exception.message.orEmpty(), recoverable = true))
                }
            }
        }
        nativeRequest(requireHandle(), requestId)
    }

    private fun requireHandle(): Long = nativeHandle.also { handle -> check(handle != 0L) { "Engine is closed" } }

    @Suppress("unused")
    private fun onNativeGpuInfo(
        requestId: Int,
        jsonText: String,
    ) {
        pendingCallbacks.remove(requestId)?.invoke(jsonText)
    }

    @Suppress("unused")
    private fun onNativeStats(
        requestId: Int,
        jsonText: String,
    ) {
        pendingCallbacks.remove(requestId)?.invoke(jsonText)
    }

    @Suppress("unused")
    private fun onNativeSceneRendered(
        requestId: Int,
        width: Int,
        height: Int,
        pixels: ByteArray,
        error: String,
    ) {
        val callback = pendingSceneCallbacks.remove(requestId) ?: return
        val result = SceneRender(width, height, pixels, error.ifEmpty { null })
        mainHandler.post { callback(result) }
    }

    @Suppress("unused")
    private fun onNativeError(
        code: Int,
        message: String,
        recoverable: Boolean,
    ) {
        val error = EngineEvent.Error(EngineErrorCode.fromNative(code), message, recoverable)
        if (!recoverable) mutableUnrecoverableError.compareAndSet(null, error)
        mutableEvents.tryEmit(error)
    }

    @Suppress("unused")
    private fun onNativeEvent(json: String) {
        val event =
            runCatching {
                val fields = Json.parseToJsonElement(json).jsonObject
                when (fields["type"]?.jsonPrimitive?.content) {
                    "transformReady" -> {
                        val bounds = fields.getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
                        val centroid = fields.getValue("centroid").jsonArray.map { it.jsonPrimitive.double }
                        EngineEvent.TransformReady(
                            requestId = fields.getValue("requestId").jsonPrimitive.int,
                            x = bounds[0],
                            y = bounds[1],
                            width = bounds[2],
                            height = bounds[3],
                            centroidX = centroid[0],
                            centroidY = centroid[1],
                            principalAngle = fields.getValue("principalAngle").jsonPrimitive.double,
                        )
                    }
                    "historyEntryReady" ->
                        EngineEvent.HistoryEntryReady(
                            entry = fields.getValue("entry").jsonPrimitive.long,
                            bytes = fields.getValue("bytes").jsonPrimitive.long,
                        )
                    "documentSaved" -> {
                        val requestId = fields.getValue("requestId").jsonPrimitive.int
                        val result =
                            DocumentSaveResult(
                                ok = fields.getValue("ok").jsonPrimitive.boolean,
                                error = fields["error"]?.jsonPrimitive?.content?.ifEmpty { null },
                                tilesWritten = fields.getValue("tilesWritten").jsonPrimitive.long,
                                tilesRemoved = fields.getValue("tilesRemoved").jsonPrimitive.long,
                                milliseconds = fields.getValue("milliseconds").jsonPrimitive.double,
                            )
                        pendingSaveCallbacks.remove(requestId)?.let { callback -> mainHandler.post { callback(result) } }
                        EngineEvent.DocumentSaved(result)
                    }
                    "documentTilesCorrupt" -> EngineEvent.DocumentTilesCorrupt(fields.getValue("count").jsonPrimitive.long)
                    "documentShown" -> EngineEvent.DocumentShown
                    else -> null
                }
            }.getOrNull() ?: return
        mutableEvents.tryEmit(event)
    }

    private external fun nativeCreate(
        memoryBudgetBytes: Long,
        scratchDirectory: String,
        renderer: Int,
    ): Long

    private external fun nativeDestroy(handle: Long)

    private external fun nativeOpenDocumentFolder(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        layerTreeJson: String,
        documentId: String,
        folder: String,
        surfaceIds: IntArray,
        surfaceFolders: Array<String>,
        surfaceFormats: IntArray,
    ): String?

    private external fun nativeSaveDocument(
        handle: Long,
        requestId: Int,
        surfaceIds: IntArray,
        surfaceFolders: Array<String>,
        surfaceFormats: IntArray,
        fileNames: Array<String>,
        fileContents: Array<ByteArray>,
    )

    private external fun nativeWriteSurfaceTileRow(
        handle: Long,
        surfaceId: Int,
        tileRow: Int,
        pixels: ByteArray,
    )

    private external fun nativeRequestCompositeThumbnail(
        handle: Long,
        requestId: Int,
        maxSide: Int,
    )

    private external fun nativeRequestCompositePreview(
        handle: Long,
        requestId: Int,
        maxSide: Int,
    )

    private external fun nativeAttachSurface(
        handle: Long,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
    )

    private external fun nativeDetachSurface(handle: Long)

    private external fun nativeSetClearColor(
        handle: Long,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    )

    private external fun nativeSetLatencyTestPoint(
        handle: Long,
        xPx: Float,
        yPx: Float,
        visible: Boolean,
    )

    private external fun nativeRequestGpuInfo(
        handle: Long,
        requestId: Int,
    )

    private external fun nativeOpenTestDocument(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        layerCount: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    )

    private external fun nativeOpenDocument(
        handle: Long,
        widthPx: Int,
        heightPx: Int,
        layerTreeJson: String,
    ): String?

    private external fun nativeSetLayerTree(
        handle: Long,
        layerTreeJson: String,
    ): String?

    private external fun nativeCloseDocument(handle: Long)

    private external fun nativeSetLayerVisible(
        handle: Long,
        layerId: Int,
        visible: Boolean,
    )

    private external fun nativeSetLayerOpacity(
        handle: Long,
        layerId: Int,
        opacity: Float,
    )

    private external fun nativeSetActiveLayer(
        handle: Long,
        layerId: Int,
    )

    private external fun nativeSetBackground(
        handle: Long,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        hidden: Boolean,
    )

    private external fun nativeFillRandomTiles(
        handle: Long,
        layerId: Int,
        tileCount: Int,
        seed: Int,
    )

    private external fun nativeSetCamera(
        handle: Long,
        a: Double,
        b: Double,
        c: Double,
        d: Double,
        tx: Double,
        ty: Double,
    )

    private external fun nativeSetViewportStyle(
        handle: Long,
        surroundRed: Float,
        surroundGreen: Float,
        surroundBlue: Float,
        shadowRed: Float,
        shadowGreen: Float,
        shadowBlue: Float,
        shadowStrength: Float,
        shadowRadiusPx: Float,
        showPixels: Boolean,
    )

    private external fun nativeSetDisplayColor(
        handle: Long,
        documentProfile: ByteArray?,
        proofProfile: ByteArray?,
        displayP3: Boolean,
    ): String?

    private external fun nativeKeepGpuAwake(
        handle: Long,
        milliseconds: Int,
    )

    private external fun nativeSetDirtyTileOverlay(
        handle: Long,
        enabled: Boolean,
    )

    private external fun nativeSetMemoryBudget(
        handle: Long,
        bytes: Long,
    )

    private external fun nativeRequestStats(
        handle: Long,
        requestId: Int,
    )

    private external fun nativeBeginStroke(
        handle: Long,
        tool: Int,
        layerId: Int,
        sizePx: Float,
        opacity: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        secondaryRed: Float,
        secondaryGreen: Float,
        secondaryBlue: Float,
        maskTarget: Boolean,
        historyEntry: Long,
        selectionId: Int,
        selectionTarget: Boolean,
        cloneSource: Int,
        cloneLayer: Int,
        cloneOffsetX: Int,
        cloneOffsetY: Int,
        symmetry: DoubleArray?,
    )

    private external fun nativeFillSurface(
        handle: Long,
        surfaceId: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        mask: Boolean,
        historyEntry: Long,
    )

    private external fun nativeClearSurface(
        handle: Long,
        surfaceId: Int,
        historyEntry: Long,
    )

    private external fun nativeInvertSurface(
        handle: Long,
        surfaceId: Int,
        mask: Boolean,
        historyEntry: Long,
    )

    private external fun nativeSetSelectionDisplay(
        handle: Long,
        selectionId: Int,
        dimOutside: Boolean,
    )

    private external fun nativeSelectPolygon(
        handle: Long,
        selectionId: Int,
        polygon: FloatArray,
        combine: Int,
        historyEntry: Long,
    )

    private external fun nativeFeatherSelection(
        handle: Long,
        selectionId: Int,
        radius: Float,
        historyEntry: Long,
    )

    private external fun nativeBeginFill(
        handle: Long,
        source: Int,
        seedX: Double,
        seedY: Double,
        threshold: Double,
        expand: Boolean,
        whole: Boolean,
        target: Int,
        surface: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        selection: Int,
        combine: Int,
        operation: Int,
    )

    private external fun nativeSetFillThreshold(
        handle: Long,
        threshold: Double,
    )

    private external fun nativeEndFill(
        handle: Long,
        commit: Boolean,
        historyEntry: Long,
    )

    private external fun nativeCopyMasked(
        handle: Long,
        sourceId: Int,
        destinationId: Int,
        selectionId: Int,
    )

    private external fun nativeBeginTransform(
        handle: Long,
        layerId: Int,
        selectionId: Int,
        requestId: Int,
    )

    private external fun nativeSetTransformMesh(
        handle: Long,
        mesh: FloatArray,
        filter: Int,
    )

    private external fun nativeBeginAdjustment(
        handle: Long,
        layerId: Int,
        selectionId: Int,
        maskId: Int,
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    )

    private external fun nativeSetAdjustment(
        handle: Long,
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    )

    private external fun nativeBeginLiquifyStroke(
        handle: Long,
        mode: Int,
        size: Float,
        pressure: Float,
        distortion: Float,
        momentum: Float,
        usePressure: Boolean,
        seed: Int,
    )

    private external fun nativeAddLiquifySamples(
        handle: Long,
        samples: FloatArray,
    )

    private external fun nativeEndLiquifyStroke(
        handle: Long,
        cancel: Boolean,
    )

    private external fun nativeLiquifyStep(
        handle: Long,
        undo: Boolean,
    )

    private external fun nativeScaleLiquify(
        handle: Long,
        factor: Float,
    )

    private external fun nativeEndAdjustment(
        handle: Long,
        commit: Boolean,
        historyEntry: Long,
    )

    private external fun nativeEndTransform(
        handle: Long,
        commit: Boolean,
        historyEntry: Long,
    )

    private external fun nativeRemapSurface(
        handle: Long,
        surfaceId: Int,
        kind: Int,
        mesh: FloatArray,
        filter: Int,
        newWidth: Int,
        newHeight: Int,
        historyEntry: Long,
    )

    private external fun nativeSetCanvasSize(
        handle: Long,
        width: Int,
        height: Int,
    )

    private external fun nativeInvertSelection(
        handle: Long,
        selectionId: Int,
        historyEntry: Long,
    )

    private external fun nativeSelectContents(
        handle: Long,
        selectionId: Int,
        layerId: Int,
        historyEntry: Long,
    )

    private external fun nativeCopySurface(
        handle: Long,
        sourceId: Int,
        destinationId: Int,
    )

    private external fun nativeComposeIntoSurface(
        handle: Long,
        targetId: Int,
        layerTreeJson: String,
        historyEntry: Long,
    ): String?

    private external fun nativeRequestTimelapseFrame(
        handle: Long,
        requestId: Int,
        maxSide: Int,
        excludedLayers: IntArray,
    )

    private external fun nativeSampleLayerAlphas(
        handle: Long,
        requestId: Int,
        canvasX: Int,
        canvasY: Int,
        surfaceIds: IntArray,
    )

    private external fun nativeDiscardSurfaces(
        handle: Long,
        surfaceIds: IntArray,
    )

    private external fun nativePrefetchSurfaces(
        handle: Long,
        surfaceIds: IntArray,
    )

    private external fun nativeApplyHistory(
        handle: Long,
        historyEntry: Long,
        undo: Boolean,
    )

    private external fun nativeReleaseHistory(
        handle: Long,
        entries: LongArray,
    )

    private external fun nativeRequestThumbnails(
        handle: Long,
        requestId: Int,
        surfaceIds: IntArray,
        masks: BooleanArray,
        maxSide: Int,
    )

    private external fun nativeSampleColor(
        handle: Long,
        requestId: Int,
        canvasX: Double,
        canvasY: Double,
        size: Int,
    )

    private external fun nativeRenderViewport(
        handle: Long,
        requestId: Int,
        widthPixels: Int,
        heightPixels: Int,
        a: Double,
        b: Double,
        c: Double,
        d: Double,
        tx: Double,
        ty: Double,
        showPixels: Boolean,
        selectionSurface: Long,
        dimOutsideSelection: Boolean,
        antsPhasePixels: Float,
    )

    private external fun nativeReadComposite(
        handle: Long,
        requestId: Int,
        deep: Boolean,
    )

    private external fun nativeSetHistoryRamBudget(
        handle: Long,
        bytes: Long,
    )

    private external fun nativeSetBrush(
        handle: Long,
        tool: Int,
        brushJson: String?,
        shapePng: ByteArray?,
        grainPng: ByteArray?,
        dualJson: String?,
        dualShapePng: ByteArray?,
        dualGrainPng: ByteArray?,
    ): String?

    private external fun nativeSetStylusRoll(
        handle: Long,
        reported: Boolean,
    )

    private external fun nativeRegisterFont(
        handle: Long,
        fontId: String,
        path: String?,
        data: ByteArray?,
        faceIndex: Int,
    )

    private external fun nativeSetFontFallbacks(
        handle: Long,
        fontIds: Array<String>,
    )

    private external fun nativeRequestTextGeometry(
        handle: Long,
        requestId: Int,
        modelJson: String,
        dpi: Double,
    )

    private external fun nativeSetTextLayer(
        handle: Long,
        layerId: Int,
        modelJson: String?,
        dpi: Double,
    )

    private external fun nativeSetGlobalSmoothing(
        handle: Long,
        amount: Float,
    )

    private external fun nativeSetPressureCurve(
        handle: Long,
        points: FloatArray?,
    )

    private external fun nativeAddStrokeSamples(
        handle: Long,
        samples: java.nio.ByteBuffer,
        byteCount: Int,
    )

    private external fun nativeReplaceStroke(
        handle: Long,
        samples: java.nio.ByteBuffer,
        byteCount: Int,
    )

    private external fun nativeRenderScene(
        handle: Long,
        requestId: Int,
        sceneJson: String,
        names: Array<String>,
        contents: Array<ByteArray>,
    )

    private external fun nativeResetFrameStatistics(handle: Long)

    private external fun nativeSetFrontBufferEnabled(
        handle: Long,
        enabled: Boolean,
    )

    private external fun nativeEndStroke(handle: Long)

    private external fun nativeCancelStroke(handle: Long)

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64

        init {
            System.loadLibrary("nanquim_engine")
        }
    }
}
