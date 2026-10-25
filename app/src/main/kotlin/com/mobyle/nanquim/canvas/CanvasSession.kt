package com.mobyle.nanquim.canvas

import android.content.ContentResolver
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.brush.ActiveBrushes
import com.mobyle.nanquim.brush.BrushLimits
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.brush.library.SliderState
import com.mobyle.nanquim.canvas.adjust.AdjustmentEditor
import com.mobyle.nanquim.canvas.adjust.AdjustmentMode
import com.mobyle.nanquim.canvas.adjust.GradientLibrary
import com.mobyle.nanquim.canvas.adjust.Histogram
import com.mobyle.nanquim.canvas.input.GestureBindings
import com.mobyle.nanquim.canvas.input.GestureConfiguration
import com.mobyle.nanquim.canvas.input.CanvasTool
import com.mobyle.nanquim.canvas.input.PalmSupport
import com.mobyle.nanquim.canvas.input.QuickShapeSnap
import com.mobyle.nanquim.canvas.input.StylusRollDetector
import com.mobyle.nanquim.canvas.input.assist
import com.mobyle.nanquim.canvas.reference.ReferenceWindowState
import com.mobyle.nanquim.canvas.selection.PendingSelection
import com.mobyle.nanquim.canvas.selection.SelectionEditor
import com.mobyle.nanquim.canvas.selection.SelectionGeometry
import com.mobyle.nanquim.canvas.selection.SelectionMode
import com.mobyle.nanquim.canvas.transform.LiftedContent
import com.mobyle.nanquim.canvas.transform.TransformEditor
import com.mobyle.nanquim.color.ProfileCmykConversion
import com.mobyle.nanquim.document.color.CmykConversion
import com.mobyle.nanquim.document.color.CmykInks
import com.mobyle.nanquim.engine.CmykColors
import com.mobyle.nanquim.color.ColorProfileLibrary
import com.mobyle.nanquim.color.PaletteLibrary
import com.mobyle.nanquim.document.ArtworkStorage
import com.mobyle.nanquim.document.DocumentPersistence
import com.mobyle.nanquim.document.LoadedArtwork
import com.mobyle.nanquim.document.OpenArtwork
import com.mobyle.nanquim.text.FontLibrary
import com.mobyle.nanquim.text.TextEditor
import com.mobyle.nanquim.document.color.ColorHarmony
import com.mobyle.nanquim.document.color.ColorState
import com.mobyle.nanquim.document.color.ColorValue
import com.mobyle.nanquim.document.guide.AssistGeometry
import com.mobyle.nanquim.document.guide.GuideSettings
import com.mobyle.nanquim.document.guide.StrokeAssist
import com.mobyle.nanquim.document.history.HistoryEntry
import com.mobyle.nanquim.document.history.HistoryLabel
import com.mobyle.nanquim.document.layer.CanvasOperation
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.PixelFormat
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.TextLayerContent
import com.mobyle.nanquim.document.layer.TextModelEditing
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.maximumLayers
import com.mobyle.nanquim.document.layer.textModels
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import com.mobyle.nanquim.document.session.ADJUSTMENT_MASK_SURFACE_ID
import com.mobyle.nanquim.document.session.DocumentSession
import com.mobyle.nanquim.document.session.LayerNames
import com.mobyle.nanquim.document.session.READBACK_SURFACE_ID
import com.mobyle.nanquim.document.session.SelectionCombine
import com.mobyle.nanquim.document.session.SelectionStroke
import com.mobyle.nanquim.document.shape.RecognizedShape
import com.mobyle.nanquim.document.shape.ShapeEditing
import com.mobyle.nanquim.document.shape.ShapeHandle
import com.mobyle.nanquim.document.shape.ShapePoint
import com.mobyle.nanquim.engine.AdjustmentFilter
import com.mobyle.nanquim.engine.AffineTransform
import com.mobyle.nanquim.engine.CloneSource
import com.mobyle.nanquim.engine.DocumentFolder
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.EngineEvent
import com.mobyle.nanquim.engine.FrameStats
import com.mobyle.nanquim.engine.LatencyStats
import com.mobyle.nanquim.engine.TextStats
import com.mobyle.nanquim.engine.LiquifyMode
import com.mobyle.nanquim.engine.Point
import com.mobyle.nanquim.engine.SceneRender
import com.mobyle.nanquim.engine.StrokeSample
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import com.mobyle.nanquim.engine.StrokeSampleFlags
import com.mobyle.nanquim.engine.StrokeStyle
import com.mobyle.nanquim.engine.StrokeTool
import com.mobyle.nanquim.engine.TextGeometry
import com.mobyle.nanquim.formats.nanquim.TimelapseSettings
import com.mobyle.nanquim.formats.nanquim.ArtistInfo
import com.mobyle.nanquim.formats.nanquim.DocumentColorProfile
import com.mobyle.nanquim.formats.nanquim.DocumentInfo
import com.mobyle.nanquim.formats.nanquim.LayerUuids
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class ToolSettings(
    val sizeSlider: Float,
    val opacitySlider: Float,
    val limits: BrushLimits = BrushLimits.DEFAULT,
) {
    val sizePx: Float get() = limits.sizeAt(sizeSlider)
    val opacity: Float get() = limits.opacityAt(opacitySlider)

    companion object {
        val DEFAULT = ToolSettings(sizeSlider = 0.3f, opacitySlider = 1f)
    }
}

enum class ColorPanelMode(
    val id: String,
    val available: Boolean,
) {
    Disc("disc", true),
    Classic("classic", true),
    Harmony("harmony", true),
    Value("value", true),
    Palettes("palettes", true),
    ;

    companion object {
        fun fromId(id: String): ColorPanelMode? = entries.firstOrNull { it.id == id }
    }
}

data class SrgbColor(
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    companion object {
        val BLACK = SrgbColor(0f, 0f, 0f)
        val WHITE = SrgbColor(1f, 1f, 1f)
    }
}

data class HistoryToast(
    val label: HistoryLabel,
    val undone: Boolean,
    val serial: Int,
)

data class LayerSelectToast(
    val layerName: String,
    val serial: Int,
)

data class EyedropperLoupe(
    val screenX: Float,
    val screenY: Float,
    val color: SrgbColor?,
    val previous: SrgbColor,
)

data class ThresholdIndicator(
    val screenX: Float,
    val screenY: Float,
    val threshold: Float,
)

data class StrokeStart(
    val layerId: Int,
    val style: StrokeStyle,
    val historyEntry: Long,
    val selectionStroke: SelectionStroke? = null,
    val adjustmentStroke: Boolean = false,
)

data class FinishedStroke(
    val start: StrokeStart,
    val tool: StrokeTool,
    val samples: List<StrokeSample>,
    val shape: RecognizedShape?,
)

sealed interface ShapeSnapResult {
    data class Applied(
        val shape: RecognizedShape,
    ) : ShapeSnapResult

    data class Unavailable(
        val reason: String,
    ) : ShapeSnapResult
}

class CanvasSession(
    private val engine: Engine,
    private val memoryBudgetPolicy: MemoryBudgetPolicy,
    val brushes: ActiveBrushes,
    val brushLibrary: BrushLibrary,
    val gradients: GradientLibrary,
    val palettes: PaletteLibrary,
    val colorProfiles: ColorProfileLibrary,
    private val layerNames: LayerNames,
    private val scope: CoroutineScope,
    val persistence: DocumentPersistence,
    contentResolver: ContentResolver,
) {
    var timelapseReplay: List<java.io.File>? by mutableStateOf(null)

    val timelapse = TimelapseRecorder(engine, scope) { strokeInProgress }

    fun changeTimelapse(settings: TimelapseSettings) {
        val open = artwork ?: return
        open.info = open.info.copy(timelapse = settings)
        persistence.documentChanged()
        timelapse.attach(open.folder, settings)
    }

    val videoLayers = VideoLayers(engine, scope, contentResolver) { artwork?.folder }

    val viewport = CanvasViewportController(engine)

    var hoverPoint: HoverPoint? by mutableStateOf(null)
        private set

    fun updateHover(point: HoverPoint?) {
        hoverPoint = point
    }

    var stylusRollReported: Boolean by mutableStateOf(false)

    var onStylusRollDetected: () -> Unit = {}

    var quickShapeDelay: () -> Int? = { null }

    var hapticsEnabled: () -> Boolean = { true }

    var onSnapped: () -> Unit = {}

    suspend fun compositePicture(maxSide: Int): android.graphics.Bitmap? {
        if (documentSession == null) return null
        val render =
            suspendCancellableCoroutine { continuation ->
                engine.requestCompositePreview(maxSide) { continuation.resume(it) }
            }
        if (render.error != null || render.width == 0) return null
        return com.mobyle.nanquim.document.RgbaImages
            .premultipliedToBitmap(render.width, render.height, render.pixels)
    }

    val engineFailure: StateFlow<EngineEvent.Error?> get() = engine.unrecoverableError

    var documentShown: Boolean by mutableStateOf(false)
        private set

    var converting: Boolean by mutableStateOf(false)
        private set

    val loading: Boolean get() = artwork != null && (!documentShown || converting)

    data class DisplayColor(
        val displayP3: Boolean = false,
        val converted: Boolean = false,
        val error: String? = null,
    )

    var displayColor: DisplayColor by mutableStateOf(DisplayColor())
        private set

    private val displayColorDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var displayColorRequest = 0

    private var loadedConversion: Pair<Pair<DocumentColorProfile?, DocumentColorProfile>, ProfileCmykConversion>? by mutableStateOf(null)

    val cmykConversion: CmykConversion?
        get() = loadedConversion?.takeIf { (key, _) -> key == colorProfile to cmykProfile }?.second

    private val conversionDispatcher = Dispatchers.Default.limitedParallelism(1)

    init {
        scope.launch {
            snapshotFlow { colorProfile to cmykProfile }.distinctUntilChanged().collect { (rgb, cmyk) ->
                val conversion =
                    cmyk?.let { withContext(conversionDispatcher) { CmykColors.create(rgb?.icc, it.icc)?.let(::ProfileCmykConversion) } }
                loadedConversion?.second?.close()
                loadedConversion = if (conversion != null && cmyk != null) (rgb to cmyk) to conversion else null
                if (conversion != null) {
                    changeColors(colors.withPrimary(conversion.printable(colors.primary)).withSecondary(conversion.printable(colors.secondary)))
                }
            }
        }
    }

    suspend fun awaitCmykConversion(): CmykConversion? {
        if (cmykProfile == null) return null
        return withTimeoutOrNull(CONVERSION_TIMEOUT_MILLIS) { snapshotFlow { cmykConversion }.filterNotNull().first() }
    }

    private var chosenInks: Pair<ColorValue, CmykInks>? by mutableStateOf(null)

    fun inksOf(color: ColorValue): CmykInks? {
        val conversion = cmykConversion ?: return null
        return chosenInks?.takeIf { (chosen, _) -> chosen.sameAs8Bit(color) }?.second ?: conversion.inksOf(color)
    }

    fun setInks(
        inks: CmykInks,
        secondary: Boolean = false,
    ): Boolean {
        val conversion = cmykConversion ?: return false
        val previous = if (secondary) colors.secondary else colors.primary
        val color = conversion.colorOf(inks, previous.alpha, previous)
        changeColors(if (secondary) colors.withSecondary(color) else colors.withPrimary(color))
        chosenInks = color to inks
        return true
    }

    fun updateDisplayColor(displayP3: Boolean) {
        val icc = colorProfile?.icc
        val cmykIcc = cmykProfile?.icc
        val request = ++displayColorRequest
        scope.launch {
            val error = withContext(displayColorDispatcher) { engine.setDisplayColor(icc, cmykIcc, displayP3) }
            if (request == displayColorRequest) {
                displayColor = DisplayColor(displayP3, converted = error == null && (icc != null || cmykIcc != null || displayP3), error = error)
            }
        }
    }

    var palmSupport: () -> PalmSupport = { PalmSupport.DEFAULT }

    var gestureConfiguration: () -> GestureConfiguration = { GestureConfiguration.defaults() }

    fun gestureBindings(): GestureBindings = gestureConfiguration().bindings

    var quickMenu: QuickMenuAnchor? by mutableStateOf(null)

    var modifierHeld: Boolean by mutableStateOf(false)
        private set

    private var modifierUsed = false

    var onModifierTap: (() -> Unit)? = null

    fun pressModifier(pressed: Boolean) {
        if (pressed) modifierUsed = false
        modifierHeld = pressed
    }

    fun useModifier() {
        modifierUsed = true
    }

    fun tapModifier() {
        if (!modifierUsed) onModifierTap?.invoke()
    }

    private val rollDetector = StylusRollDetector()

    fun observeStylus(
        tilt: Float,
        orientation: Float,
        ended: Boolean,
    ) {
        if (ended) {
            rollDetector.reset()
            return
        }
        if (!stylusRollReported && rollDetector.observe(tilt, orientation)) {
            stylusRollReported = true
            engine.setStylusRoll(true)
            onStylusRollDetected()
        }
    }

    var fillThreshold: Float by mutableFloatStateOf(DEFAULT_FILL_THRESHOLD)
        private set

    var thresholdIndicator: ThresholdIndicator? by mutableStateOf(null)
        private set

    var colorDropPoint: Point? by mutableStateOf(null)
        private set

    var continueFilling: Boolean by mutableStateOf(false)
        private set

    var canvasFingersDown: Int = 0

    private var thresholdAnchorX = 0f
    private var thresholdAtAnchor = 0f

    private var thresholdDragFills = false

    fun beginThresholdDrag(
        screenX: Float,
        screenY: Float,
        automaticSelection: Boolean,
        whole: Boolean = false,
    ): Boolean {
        val session = documentSession ?: return false
        val point = viewport.screenToCanvas()?.apply(Point(screenX.toDouble(), screenY.toDouble())) ?: return false
        try {
            if (automaticSelection) {
                session.beginAutomaticSelection(point.x, point.y, fillThreshold.toDouble(), selectionCombine)
            } else {
                val color = primaryColor
                session.beginFill(point.x, point.y, fillThreshold.toDouble(), RgbaColor(color.red, color.green, color.blue), whole)
            }
        } catch (exception: LayerOperationException) {
            return false
        }
        thresholdAnchorX = screenX
        thresholdAtAnchor = fillThreshold
        thresholdDragFills = !automaticSelection
        thresholdIndicator = ThresholdIndicator(screenX, screenY, fillThreshold).takeUnless { whole }
        return true
    }

    fun updateThresholdDrag(
        screenX: Float,
        screenY: Float,
    ) {
        if (thresholdIndicator == null) return
        fillThreshold = (thresholdAtAnchor + (screenX - thresholdAnchorX) / THRESHOLD_DRAG_PX).coerceIn(0f, 1f)
        thresholdIndicator = ThresholdIndicator(screenX, screenY, fillThreshold)
        documentSession?.setFillThreshold(fillThreshold.toDouble())
    }

    fun endThresholdDrag(commit: Boolean = true) {
        thresholdIndicator = null
        val session = documentSession ?: return
        session.endFill(commit)
        if (commit && thresholdDragFills) recordColorUse()
        thresholdDragFills = false
    }

    fun moveColorDrop(point: Point?) {
        colorDropPoint = point
    }

    fun startContinueFilling() {
        continueFilling = true
    }

    fun stopContinueFilling() {
        continueFilling = false
    }

    fun fillAt(
        screenX: Float,
        screenY: Float,
    ) {
        if (beginThresholdDrag(screenX, screenY, automaticSelection = false)) endThresholdDrag(commit = true)
    }

    var transformEditor: TransformEditor? = null
        private set

    var transformVersion: Int by mutableIntStateOf(0)
        private set

    val transformReady: Boolean get() = transformEditor?.state != null

    private var transformRequest = 0

    private fun startTransform(): Boolean {
        val session = documentSession ?: return false
        val document = document ?: return false
        try {
            session.beginTransform(++transformRequest)
        } catch (exception: LayerOperationException) {
            return false
        }
        transformEditor = TransformEditor(document.widthPx.toDouble(), document.heightPx.toDouble()) { onSnapped() }
        transformVersion++
        return true
    }

    private fun onTransformReady(event: EngineEvent.TransformReady) {
        val editor = transformEditor ?: return
        if (event.requestId != transformRequest) return
        if (event.width <= 0) {
            canvasTool = CanvasTool.Brush
            return
        }
        editor.initialize(
            LiftedContent(event.x, event.y, event.width, event.height, Point(event.centroidX, event.centroidY), event.principalAngle),
        )
        transformChanged()
    }

    fun transformChanged() {
        val editor = transformEditor ?: return
        editor.mesh()?.let { mesh -> documentSession?.setTransformMesh(mesh, editor.filter.ordinal) }
        transformVersion++
    }

    fun finishTransform(commit: Boolean) {
        val editor = transformEditor ?: return
        val session = documentSession
        val textLayer = (session?.state?.activeLayer as? RasterLayer)?.takeIf { it.text != null && !session.maskSelected }
        val affine = editor.state?.let(::affineOf)
        if (commit && session != null && textLayer != null && affine != null) {
            transformEditor = null
            session.endTransform(commit = false)
            val model = textLayer.text!!.model
            val moved = composeAffine(affine, TextModelEditing.transform(model))
            session.setTextContent(textLayer.id, TextLayerContent(TextModelEditing.setTransform(model, moved)), label = HistoryLabel.Transform)
            transformVersion++
            return
        }
        transformEditor = null
        documentSession?.endTransform(commit)
        transformVersion++
    }

    var adjustmentEditor: AdjustmentEditor? = null
        private set

    var adjustmentVersion: Int by mutableIntStateOf(0)
        private set

    var adjustmentMenu: Point? by mutableStateOf(null)
        private set

    fun startAdjustment(
        filter: AdjustmentFilter,
        mode: AdjustmentMode = AdjustmentMode.Layer,
    ): Boolean {
        val session = documentSession ?: return false
        finishAdjustment(commit = true)
        canvasTool = CanvasTool.Adjust
        val gradient = gradients.entry(lastGradientId ?: "") ?: gradients.entries.first()
        val editor = AdjustmentEditor(filter, mode, gradient.takeIf { filter == AdjustmentFilter.GradientMap })
        val layerId = session.state.activeLayerId
        adjustmentHistogram = null
        if (filter == AdjustmentFilter.Curves && layerId != null) {
            engine.requestThumbnails(intArrayOf(layerId), booleanArrayOf(false), HISTOGRAM_SIDE) { render ->
                if (adjustmentEditor === editor && render.error == null) {
                    adjustmentHistogram = Histogram.of(render.pixels)
                }
            }
        }
        try {
            session.beginAdjustment(
                filter.ordinal,
                editor.values,
                editor.tableData(),
                editor.amount,
                filter.historyLabel,
                pencil = mode == AdjustmentMode.Pencil,
            )
        } catch (exception: LayerOperationException) {
            canvasTool = CanvasTool.Brush
            return false
        }
        adjustmentEditor = editor
        adjustmentVersion++
        return true
    }

    var adjustmentHistogram: Histogram? by mutableStateOf(null)
        private set

    private var lastGradientId: String? = null

    fun adjustmentChanged() {
        val editor = adjustmentEditor ?: return
        documentSession?.setAdjustment(editor.filter.ordinal, editor.values, editor.tableData(), editor.amount)
        editor.state.gradientId?.let { lastGradientId = it }
        adjustmentVersion++
    }

    fun finishAdjustment(commit: Boolean) {
        if (adjustmentEditor == null) return
        adjustmentEditor = null
        adjustmentMenu = null
        documentSession?.endAdjustment(commit)
        adjustmentVersion++
        if (canvasToolState == CanvasTool.Adjust) canvasToolState = CanvasTool.Brush
    }

    val adjustmentPencil: Boolean get() = adjustmentEditor?.mode == AdjustmentMode.Pencil

    fun undoAdjustment(): Boolean = applyAdjustmentStep(adjustmentEditor?.undo(), undo = true)

    fun redoAdjustment(): Boolean = applyAdjustmentStep(adjustmentEditor?.redo(), undo = false)

    private fun applyAdjustmentStep(
        step: com.mobyle.nanquim.canvas.adjust.StepResult?,
        undo: Boolean,
    ): Boolean {
        step ?: return false
        step.maskEntry?.let { documentSession?.applyAdjustmentMaskEntry(it, undo) }
        if (step.liquify) engine.liquifyStep(undo)
        adjustmentChanged()
        return true
    }

    fun resetAdjustment() {
        val editor = adjustmentEditor ?: return
        if (editor.filter == AdjustmentFilter.Liquify) {
            editor.finishChange()
            engine.scaleLiquify(0f)
            editor.recordLiquifyStep()
            adjustmentChanged()
            return
        }
        val clear = if (editor.mode == AdjustmentMode.Pencil) documentSession?.clearAdjustmentMask() else null
        editor.reset(clear)
        adjustmentChanged()
    }

    val adjustmentPuck: Point?
        get() {
            val (x, y) = adjustmentEditor?.puck ?: return null
            val state = documentSession?.state ?: return null
            return Point(x.toDouble() * state.widthPx, y.toDouble() * state.heightPx)
        }

    fun moveAdjustmentPuck(point: Point) {
        val editor = adjustmentEditor ?: return
        val state = documentSession?.state ?: return
        editor.movePuck((point.x / state.widthPx).toFloat().coerceIn(0f, 1f), (point.y / state.heightPx).toFloat().coerceIn(0f, 1f))
        adjustmentChanged()
    }

    val liquifying: Boolean get() = adjustmentEditor?.filter == AdjustmentFilter.Liquify

    private var liquifySeed = 0

    fun beginLiquifyStroke(
        sample: FloatArray,
        usePressure: Boolean,
    ): Boolean {
        val editor = adjustmentEditor?.takeIf { it.filter == AdjustmentFilter.Liquify } ?: return false
        val values = editor.values
        engine.beginLiquifyStroke(
            LiquifyMode.entries[values[LIQUIFY_MODE].roundToInt().coerceIn(0, LiquifyMode.entries.size - 1)],
            values[0],
            values[1],
            values[2],
            values[3],
            usePressure,
            liquifySeed++,
        )
        engine.addLiquifySamples(sample)
        return true
    }

    fun addLiquifySamples(samples: FloatArray) {
        if (liquifying) engine.addLiquifySamples(samples)
    }

    fun endLiquifyStroke(cancel: Boolean) {
        val editor = adjustmentEditor?.takeIf { it.filter == AdjustmentFilter.Liquify } ?: return
        engine.endLiquifyStroke(cancel)
        if (!cancel) {
            editor.recordLiquifyStep()
            adjustmentVersion++
        }
    }

    fun liquifyPath(samples: FloatArray) {
        if (!beginLiquifyStroke(samples.copyOfRange(0, 4), usePressure = true)) return
        if (samples.size > 4) engine.addLiquifySamples(samples.copyOfRange(4, samples.size))
        endLiquifyStroke(cancel = false)
    }

    fun adjustLiquify(factor: Float) {
        val editor = adjustmentEditor?.takeIf { it.filter == AdjustmentFilter.Liquify } ?: return
        editor.cancelChange()
        if (factor < 1f) {
            engine.scaleLiquify(factor.coerceIn(0f, 1f))
            editor.recordLiquifyStep()
        }
        adjustmentChanged()
    }

    fun showAdjustmentMenu(at: Point?) {
        adjustmentMenu = at
    }

    val selectionDisplay = SelectionDisplay(engine)

    val selectionEditor = SelectionEditor()

    val cropEditor = SelectionEditor().apply { mode = SelectionMode.Rectangle }

    val boxEditor: SelectionEditor get() = if (canvasTool == CanvasTool.Crop) cropEditor else selectionEditor

    var cropBox: PendingSelection.Box? by mutableStateOf(null)
        private set

    var pendingSelection: PendingSelection? by mutableStateOf(null)
        private set

    var selectionMode: SelectionMode by mutableStateOf(SelectionMode.Freehand)
        private set

    var selectionCombine: SelectionCombine by mutableStateOf(SelectionCombine.Replace)

    fun changeSelectionMode(mode: SelectionMode) {
        applyPendingSelection()
        selectionMode = mode
        selectionEditor.mode = mode
        pendingSelectionChanged()
    }

    fun pendingSelectionChanged() {
        pendingSelection = selectionEditor.pending
        if (canvasTool == CanvasTool.Crop && cropEditor.pending == null) {
            documentSession?.state?.let { cropEditor.setPendingBox(0.0, 0.0, it.widthPx.toDouble(), it.heightPx.toDouble()) }
        }
        cropBox = cropEditor.pending as? PendingSelection.Box
    }

    fun applyCrop() {
        val box = cropEditor.pending as? PendingSelection.Box ?: return
        val left = kotlin.math.round(box.left).toInt()
        val top = kotlin.math.round(box.top).toInt()
        val width = kotlin.math.round(box.right).toInt() - left
        val height = kotlin.math.round(box.bottom).toInt() - top
        if (width < 1 || height < 1) return
        canvasOperation(CanvasOperation.Crop(left, top, width, height))
    }

    fun canvasOperation(operation: CanvasOperation) {
        documentSession?.canvasOperation(operation)
        resetCropBox()
    }

    private fun resetCropBox() {
        val state = documentSession?.state ?: return
        cropEditor.setPendingBox(0.0, 0.0, state.widthPx.toDouble(), state.heightPx.toDouble())
        pendingSelectionChanged()
    }

    fun layerLimitFor(
        widthPx: Int,
        heightPx: Int,
        format: PixelFormat = documentSession?.state?.pixelFormat ?: PixelFormat.Rgba8,
    ): Int = maximumLayers(memoryBudgetPolicy.maximumBytes, widthPx, heightPx, format.bytesPerPixel)

    var guides: GuideSettings by mutableStateOf(GuideSettings())
        private set

    val reference = ReferenceWindowState(engine, scope) { persistence.documentChanged() }

    var strokeInProgress: Boolean by mutableStateOf(false)
        private set

    fun changeGuides(settings: GuideSettings) {
        if (settings == guides) return
        guides = settings
        val open = artwork ?: return
        open.info = open.info.copy(guides = settings)
        persistence.documentChanged()
    }

    var selectedVanishingPoint: Int? by mutableStateOf(null)
        private set

    fun selectVanishingPoint(index: Int?) {
        selectedVanishingPoint = index?.takeIf { it in guides.perspective.points.indices }
    }

    fun setDpi(dpi: Double) {
        val open = artwork ?: return
        open.info = open.info.copy(dpi = dpi)
        documentSession?.rasterizeTextAgain()
        persistence.documentChanged()
    }

    val colorProfile: DocumentColorProfile? get() = artwork?.info?.colorProfile

    val cmykProfile: DocumentColorProfile? get() = artwork?.info?.cmykProfile

    fun assignColorProfile(profile: DocumentColorProfile?) {
        val open = artwork ?: return
        if (open.info.colorProfile == profile) return
        open.info = open.info.copy(colorProfile = profile)
        documentVersion++
        persistence.documentChanged()
    }

    fun assignCmykProfile(profile: DocumentColorProfile?) {
        val open = artwork ?: return
        if (open.info.cmykProfile == profile) return
        open.info = open.info.copy(cmykProfile = profile)
        documentVersion++
        persistence.documentChanged()
    }

    fun changePixelFormat(
        format: PixelFormat,
        onDone: (String?) -> Unit = {},
    ) = convertArtwork(format, colorProfile, onDone)

    fun convertColorProfile(
        profile: DocumentColorProfile?,
        onDone: (String?) -> Unit = {},
    ) = convertArtwork(documentSession?.state?.pixelFormat ?: PixelFormat.Rgba8, profile, onDone)

    private fun convertArtwork(
        format: PixelFormat,
        profile: DocumentColorProfile?,
        onDone: (String?) -> Unit,
    ) {
        val open = artwork
        val session = documentSession
        if (open == null || session == null || converting) {
            onDone("No open artwork")
            return
        }
        val sourceProfile = open.info.colorProfile
        if (session.state.pixelFormat == format && sourceProfile == profile) {
            onDone(null)
            return
        }
        val limit = layerLimitFor(session.state.widthPx, session.state.heightPx, format)
        if (session.state.layerCount > limit) {
            onDone("The artwork has ${session.state.layerCount} layers; ${format.bitsPerChannel}-bit documents of this size allow $limit")
            return
        }
        finishAdjustment(commit = true)
        leaveShapeEdit()
        session.commitPendingSessions()
        lastStroke = null
        converting = true

        fun fail(reason: String) {
            converting = false
            onDone(reason)
        }
        persistence.saveNow(withThumbnail = false) { saved ->
            val state = documentSession?.state
            if (!saved || state == null || artwork !== open) {
                fail(persistence.lastError ?: "The artwork could not be saved")
                return@saveNow
            }
            val layerIds = state.rasterLayers().map { it.id }
            val renewed = open.uuids.assignedFor(state).renewed(layerIds)
            val folderOf = { uuids: LayerUuids, id: Int -> File(open.folder, "layers/${uuids.uuidOf(id)}") }
            persistence.pause()
            scope.launch {
                val error =
                    withContext(Dispatchers.IO) {
                        val errors =
                            coroutineScope {
                                layerIds
                                    .map { id ->
                                        async {
                                            DocumentFolder.convertLayer(
                                                folderOf(open.uuids, id).path,
                                                folderOf(renewed, id).path,
                                                fromDeep = state.pixelFormat == PixelFormat.Rgba16f,
                                                toDeep = format == PixelFormat.Rgba16f,
                                                sourceProfile = sourceProfile?.icc,
                                                destinationProfile = profile?.icc,
                                            )
                                        }
                                    }.awaitAll()
                            }
                        errors.firstNotNullOfOrNull { it }?.also {
                            layerIds.forEach { id -> folderOf(renewed, id).deleteRecursively() }
                        }
                    }
                persistence.resume()
                if (error != null || artwork !== open) {
                    fail(error ?: "The artwork was closed")
                    return@launch
                }
                open.uuids = renewed
                open.info = open.info.copy(colorProfile = profile)
                openDocument(state.copy(pixelFormat = format), open, keepView = true)
                persistence.saveNow(withThumbnail = true) { ok ->
                    converting = false
                    onDone(if (ok) null else persistence.lastError ?: "The artwork could not be saved")
                }
            }
        }
    }

    fun applyPendingSelection(): Boolean {
        val pending = selectionEditor.takePending()
        pendingSelectionChanged()
        val polygon = pending?.polygon() ?: return false
        applyShape(polygon)
        return true
    }

    fun applyReplacedSelection() {
        selectionEditor.replaced?.polygon()?.let(::applyShape)
    }

    fun discardPendingSelection() {
        selectionEditor.takePending()
        pendingSelectionChanged()
    }

    fun applyShape(
        polygon: List<Point>,
        combine: SelectionCombine = selectionCombine,
    ) {
        val session = documentSession ?: return
        session.selectShape(SelectionGeometry.toFloatArray(polygon), combine)
    }

    var documentSession: DocumentSession? = null
        private set

    var artwork: OpenArtwork? by mutableStateOf(null)
        private set

    var document: DocumentState? by mutableStateOf(null)
        private set

    var documentVersion: Int by mutableIntStateOf(0)
        private set

    val layerThumbnails = LayerThumbnails(engine, scope)

    val playback = AnimationPlayback(engine) { documentSession }

    var layersPanelVisible: Boolean by mutableStateOf(false)
        private set

    var panelsOneAtATime: Boolean = false

    var closeBrushLibrary: () -> Unit = {}

    fun showLayersPanel(visible: Boolean) {
        layersPanelVisible = visible
        if (visible) {
            closeBrushLibrary()
            if (panelsOneAtATime) colorPanelVisible = false
            document?.let(layerThumbnails::invalidate)
        }
    }

    var exportSheetRequests: Int by mutableIntStateOf(0)
        private set

    fun requestExportSheet() {
        exportSheetRequests++
    }

    private val editingTime = EditingTime()

    private fun noteEdit() {
        val open = artwork ?: return
        val seconds = editingTime.edit(SystemClock.uptimeMillis())
        if (seconds > 0) open.info = open.info.copy(timeSpentSeconds = open.info.timeSpentSeconds + seconds)
    }

    var canvasInfoVisible: Boolean by mutableStateOf(false)

    fun setArtist(artist: ArtistInfo) {
        val open = artwork ?: return
        open.info = open.info.copy(artist = artist.takeUnless { it.isEmpty })
        persistence.documentChanged()
    }

    var projectedDisplay: String? by mutableStateOf(null)

    var spacePanning: Boolean by mutableStateOf(false)

    var fullScreen: Boolean by mutableStateOf(false)
        private set

    fun updateFullScreen(enabled: Boolean) {
        fullScreen = enabled
        if (enabled) {
            layersPanelVisible = false
            colorPanelVisible = false
            copyPasteMenu = null
        }
    }

    var copyPasteMenu: Point? by mutableStateOf(null)
        private set

    fun showCopyPasteMenu(at: Point?) {
        copyPasteMenu = at
    }

    var layerSelectToast: LayerSelectToast? by mutableStateOf(null)
        private set

    fun dismissLayerSelectToast(serial: Int) {
        if (layerSelectToast?.serial == serial) layerSelectToast = null
    }

    fun selectLayerAt(
        canvasX: Double,
        canvasY: Double,
        onDone: (Int?) -> Unit = {},
    ) {
        val session = documentSession ?: return onDone(null)
        val state = session.state
        val candidates =
            state.rasterLayers().reversed().filter { layer ->
                !layer.hidden && state.ancestorsOf(layer.id).none { it.hidden }
            }
        if (candidates.isEmpty()) return onDone(null)
        engine.sampleLayerAlphas(canvasX.toInt(), canvasY.toInt(), candidates.map { it.id }.toIntArray()) { render ->
            val index = if (render.error == null) render.pixels.indexOfFirst { (it.toInt() and 0xFF) > LAYER_SELECT_ALPHA } else -1
            val layer = candidates.getOrNull(index)
            if (layer != null && documentSession === session) {
                runCatching { session.select(layer.id) }
                layerSelectToast = LayerSelectToast(layer.name, ++toastSerial)
            }
            onDone(layer?.id)
        }
    }

    var historyToast: HistoryToast? by mutableStateOf(null)
        private set

    private var toastSerial = 0

    fun undo(): HistoryEntry? {
        leaveShapeEdit()
        if (adjustmentEditor != null) {
            undoAdjustment()
            return null
        }
        transformEditor?.let { editor ->
            if (editor.undo()) transformChanged()
            return null
        }
        return documentSession?.undo()?.also { historyToast = HistoryToast(it.label, true, ++toastSerial) }
    }

    fun redo(): HistoryEntry? {
        leaveShapeEdit()
        if (adjustmentEditor != null) {
            redoAdjustment()
            return null
        }
        transformEditor?.let { editor ->
            if (editor.redo()) transformChanged()
            return null
        }
        return documentSession?.redo()?.also { historyToast = HistoryToast(it.label, false, ++toastSerial) }
    }

    fun dismissHistoryToast(serial: Int) {
        if (historyToast?.serial == serial) historyToast = null
    }

    var eyedropper: EyedropperLoupe? by mutableStateOf(null)
        private set

    var eyedropperArmed: Boolean by mutableStateOf(false)
        private set

    fun armEyedropper(armed: Boolean) {
        eyedropperArmed = armed
    }

    fun disarmEyedropper() {
        eyedropperArmed = false
    }

    fun eyedropperAt(
        screenX: Float,
        screenY: Float,
        sampleSize: Int = 1,
    ) {
        val point = viewport.screenToCanvas()?.apply(Point(screenX.toDouble(), screenY.toDouble())) ?: return
        eyedropper = EyedropperLoupe(screenX, screenY, eyedropper?.color, eyedropper?.previous ?: primaryColor)
        engine.sampleColor(point.x, point.y, sampleSize) { render ->
            val current = eyedropper ?: return@sampleColor
            if (current.screenX != screenX || current.screenY != screenY || render.error != null) return@sampleColor
            eyedropper = current.copy(color = render.toColor())
        }
    }

    fun endEyedropper(pick: Boolean): Point? {
        val loupe = eyedropper ?: return null
        eyedropper = null
        if (!pick) return null
        return viewport.screenToCanvas()?.apply(Point(loupe.screenX.toDouble(), loupe.screenY.toDouble()))
    }

    fun pickColor(
        canvasX: Double,
        canvasY: Double,
        sampleSize: Int,
        onPicked: (SrgbColor?) -> Unit,
    ) = engine.sampleColor(canvasX, canvasY, sampleSize) { render ->
        val color = if (render.error == null) render.toColor() else null
        if (color != null && (render.pixels[3].toInt() and 0xFF) > 0) primaryColor = color
        onPicked(color)
    }

    private fun SceneRender.toColor() =
        SrgbColor(
            (pixels[0].toInt() and 0xFF) / 255f,
            (pixels[1].toInt() and 0xFF) / 255f,
            (pixels[2].toInt() and 0xFF) / 255f,
        )

    init {
        scope.launch {
            engine.events.collect { event ->
                if (event is EngineEvent.HistoryEntryReady) documentSession?.onPixelEntrySize(event.entry, event.bytes)
                if (event is EngineEvent.TransformReady) onTransformReady(event)
                if (event is EngineEvent.DocumentShown) documentShown = true
            }
        }
    }

    var memoryHudVisible: Boolean by mutableStateOf(false)
        private set

    var dirtyTileOverlayVisible: Boolean by mutableStateOf(false)
        private set

    var debugPanelVisible: Boolean by mutableStateOf(false)

    private var canvasToolState: CanvasTool by mutableStateOf(CanvasTool.Brush)

    var canvasTool: CanvasTool
        get() = canvasToolState
        set(value) {
            leaveShapeEdit()
            if (value != CanvasTool.Selection) runCatching { applyPendingSelection() }
            continueFilling = false
            if (value != CanvasTool.Transform) finishTransform(commit = true)
            if (value != CanvasTool.Adjust) finishAdjustment(commit = true)
            if (value != CanvasTool.Text) textEditor.finish()
            if (value == CanvasTool.Transform && canvasToolState != CanvasTool.Transform && !startTransform()) return
            canvasToolState = value
            if (value == CanvasTool.Crop) resetCropBox()
            selectionDisplay.setDimOutside(value == CanvasTool.Selection)
        }

    val paintsSelection: Boolean get() = canvasTool == CanvasTool.Selection && selectionMode == SelectionMode.Paint

    var colors: ColorState by mutableStateOf(ColorState())
        private set

    var onColorsChanged: (ColorState) -> Unit = {}

    fun restoreColors(state: ColorState) {
        colors = state
    }

    private fun changeColors(state: ColorState) {
        if (state == colors) return
        colors = state
        onColorsChanged(state)
    }

    var primaryColor: SrgbColor
        get() = colors.primary.toSrgb()
        set(value) = setPrimaryColor(value.toColorValue(colors.primary))

    var secondaryColor: SrgbColor
        get() = colors.secondary.toSrgb()
        set(value) = setSecondaryColor(value.toColorValue(colors.secondary))

    fun setPrimaryColor(color: ColorValue) = changeColors(colors.withPrimary(printable(color)))

    fun setSecondaryColor(color: ColorValue) = changeColors(colors.withSecondary(printable(color)))

    private fun printable(color: ColorValue): ColorValue = cmykConversion?.printable(color) ?: color

    fun swapColors() = changeColors(colors.swapped())

    fun recordColorUse() = changeColors(colors.recordUse())

    fun clearColorHistory() = changeColors(colors.withoutHistory())

    var colorPanelVisible: Boolean by mutableStateOf(false)
        private set

    var colorPanelMode: ColorPanelMode by mutableStateOf(ColorPanelMode.Disc)
        private set

    fun showColorPanel(visible: Boolean) {
        colorPanelVisible = visible
        if (visible && panelsOneAtATime) {
            closeBrushLibrary()
            layersPanelVisible = false
        }
    }

    fun selectColorPanelMode(mode: ColorPanelMode) {
        colorPanelMode = mode
    }

    var colorHarmony: ColorHarmony by mutableStateOf(ColorHarmony.Complementary)
        private set

    fun selectColorHarmony(harmony: ColorHarmony) {
        colorHarmony = harmony
    }

    private fun ColorValue.toSrgb() = SrgbColor(red, green, blue)

    private fun SrgbColor.toColorValue(previous: ColorValue) = ColorValue.fromRgb(red, green, blue, previous.alpha, previous)

    var sliderPresetsVersion: Int by mutableIntStateOf(0)
        private set

    fun sliderPresetsChanged() {
        sliderPresetsVersion++
    }

    val currentBrushTool: BrushTool
        get() =
            when (canvasTool) {
                CanvasTool.Eraser -> BrushTool.Erase
                CanvasTool.Smudge -> BrushTool.Smudge
                else -> BrushTool.Paint
            }

    val activeLayerId: Int? get() = document?.activeLayerId

    var cloneSourcePoint: Point? by mutableStateOf(null)
        private set

    var cloneAligned: Boolean by mutableStateOf(true)
        private set

    var cloneFromComposite: Boolean by mutableStateOf(true)
        private set

    private var cloneOffset: Pair<Int, Int>? = null

    fun setCloneSource(point: Point) {
        cloneSourcePoint = point
        cloneOffset = null
    }

    fun setCloneOptions(
        aligned: Boolean = cloneAligned,
        fromComposite: Boolean = cloneFromComposite,
    ) {
        cloneAligned = aligned
        cloneFromComposite = fromComposite
        cloneOffset = null
    }

    private fun cloneFor(start: Point?): CloneSource? {
        val source = cloneSourcePoint ?: return null
        val layerId = documentSession?.state?.activeLayerId ?: return null
        val origin = start ?: source
        val offset =
            cloneOffset.takeIf { cloneAligned }
                ?: (kotlin.math.round(source.x - origin.x).toInt() to kotlin.math.round(source.y - origin.y).toInt())
        if (cloneAligned) cloneOffset = offset
        return CloneSource(cloneFromComposite, layerId, offset.first, offset.second)
    }

    fun beginStroke(
        tool: StrokeTool,
        start: Point? = null,
    ): StrokeStart? {
        leaveShapeEdit()
        val stroke = startStroke(tool, start) ?: return null
        strokeInProgress = true
        val copies = strokeSymmetry(stroke)
        return if (copies.isEmpty()) stroke else stroke.copy(style = stroke.style.copy(symmetry = copies))
    }

    private fun strokeSymmetry(stroke: StrokeStart): List<AffineTransform> {
        if (stroke.adjustmentStroke || stroke.style.clone != null) return emptyList()
        val layer = documentSession?.state?.raster(stroke.layerId) ?: return emptyList()
        if (!layer.assisted) return emptyList()
        return AssistGeometry.symmetry(guides).map { AffineTransform(it.a, it.d, it.b, it.e, it.c, it.f) }
    }

    private fun startStroke(
        tool: StrokeTool,
        start: Point?,
    ): StrokeStart? {
        val session = documentSession ?: return null
        if (playback.playing) return null
        (activeTextLayerId ?: activeVideoLayerId)?.let { layerId ->
            askToRasterize(layerId)
            return null
        }
        if (canvasTool == CanvasTool.Clone && tool == StrokeTool.Paint) {
            if (!session.canPaint) return null
            val clone = cloneFor(start) ?: return null
            val layerId = session.state.activeLayerId ?: return null
            val entry = session.beginStroke()
            val style =
                strokeStyle(tool).copy(
                    maskTarget = session.maskSelected,
                    historyEntry = entry,
                    selectionId = session.selectionId ?: 0,
                    clone = clone,
                )
            return StrokeStart(layerId, style, entry)
        }
        if (canvasTool == CanvasTool.Adjust && session.adjustmentPencil) {
            if (tool == StrokeTool.Smudge) return null
            val entry = session.beginAdjustmentMaskEntry()
            val style =
                strokeStyle(tool).copy(historyEntry = entry, selectionId = ADJUSTMENT_MASK_SURFACE_ID, selectionTarget = true)
            return StrokeStart(session.state.activeLayerId ?: 0, style, entry, adjustmentStroke = true)
        }
        if (paintsSelection) {
            val stroke = session.beginSelectionStroke()
            val style = strokeStyle(tool).copy(historyEntry = stroke.entry, selectionId = stroke.selectionId, selectionTarget = true)
            return StrokeStart(session.state.activeLayerId ?: 0, style, stroke.entry, stroke)
        }
        if (!session.canPaint) return null
        val layerId = session.state.activeLayerId ?: return null
        val entry = session.beginStroke()
        val style =
            strokeStyle(tool).copy(maskTarget = session.maskSelected, historyEntry = entry, selectionId = session.selectionId ?: 0)
        return StrokeStart(layerId, style, entry)
    }

    var lastStroke: FinishedStroke? = null
        private set

    fun endStroke(
        start: StrokeStart,
        tool: StrokeTool,
        end: Point? = null,
        samples: List<StrokeSample> = emptyList(),
        shape: RecognizedShape? = null,
        edit: Boolean = false,
    ) {
        strokeInProgress = false
        val clone = start.style.clone
        if (clone != null && cloneAligned && end != null) cloneSourcePoint = Point(end.x + clone.offsetX, end.y + clone.offsetY)
        lastStroke = null
        start.selectionStroke?.let { stroke ->
            documentSession?.recordSelectionStroke(stroke)
            return
        }
        if (start.adjustmentStroke) {
            adjustmentEditor?.recordMaskStroke(start.historyEntry)
            adjustmentVersion++
            return
        }
        if (samples.isNotEmpty()) lastStroke = FinishedStroke(start, tool, samples, shape)
        if (shape != null && samples.isNotEmpty() && !edit) shapeEditOffer = ++shapeOfferSerial
        val label =
            when (tool) {
                StrokeTool.Erase -> HistoryLabel.EraseStroke
                StrokeTool.Smudge -> HistoryLabel.SmudgeStroke
                StrokeTool.Paint -> HistoryLabel.BrushStroke
            }
        documentSession?.recordStroke(start.historyEntry, label)
        if (edit) return
        artwork?.let { it.info = it.info.copy(strokeCount = it.info.strokeCount + 1) }
        if (tool == StrokeTool.Paint && start.style.clone == null) recordColorUse()
    }

    fun cancelStroke(start: StrokeStart) {
        strokeInProgress = false
        start.selectionStroke?.let { documentSession?.cancelSelectionStroke(it) }
    }

    val currentToolSettings: ToolSettings get() = toolSettings(currentBrushTool)

    fun toolSettings(tool: BrushTool): ToolSettings {
        val brush = brushes[tool] ?: return ToolSettings.DEFAULT
        return ToolSettings(brush.sliders.sizeSlider, brush.sliders.opacitySlider, brush.limits)
    }

    fun updateCurrentToolSettings(settings: ToolSettings) =
        brushes.updateSliders(currentBrushTool, SliderState(settings.sizeSlider, settings.opacitySlider))

    fun strokeStyle(tool: StrokeTool): StrokeStyle {
        val settings =
            toolSettings(
                when (tool) {
                    StrokeTool.Erase -> BrushTool.Erase
                    StrokeTool.Smudge -> BrushTool.Smudge
                    StrokeTool.Paint -> BrushTool.Paint
                },
            )
        val color = primaryColor
        val secondary = secondaryColor
        return StrokeStyle(
            sizePx = settings.sizePx,
            opacity = settings.opacity,
            red = color.red,
            green = color.green,
            blue = color.blue,
            secondaryRed = secondary.red,
            secondaryGreen = secondary.green,
            secondaryBlue = secondary.blue,
        )
    }

    fun strokeAssist(
        start: StrokeStart,
        first: Point,
    ): StrokeAssist? {
        if (start.adjustmentStroke) return null
        val layer = documentSession?.state?.raster(start.layerId) ?: return null
        if (!layer.assisted || !AssistGeometry.constrains(guides)) return null
        val zoom = viewport.canvasToScreen()?.scaleFactor ?: 1.0
        return StrokeAssist(guides, first.x, first.y, StrokeAssist.DEFAULT_DECIDE_DISTANCE / zoom)
    }

    fun strokePath(
        tool: StrokeTool,
        samples: StrokeSampleBuffer,
    ): Boolean {
        val first = samples.firstPoint()
        val start = beginStroke(tool, first) ?: return false
        if (first != null) strokeAssist(start, first)?.let { assist -> samples.assist(assist) }
        engine.beginStroke(tool, start.layerId, start.style)
        engine.addStrokeSamples(samples)
        engine.endStroke()
        val real =
            (0 until samples.count)
                .map(samples::sampleAt)
                .filter { it.flags and (StrokeSampleFlags.PREDICTED or StrokeSampleFlags.CANCELED) == 0 }
        endStroke(start, tool, samples.lastPoint(), real)
        return true
    }

    fun applyShapeToLastStroke(): ShapeSnapResult {
        val stroke = lastStroke ?: return ShapeSnapResult.Unavailable("No stroke to change")
        lastStrokeProblem(stroke)?.let { return ShapeSnapResult.Unavailable(it) }
        if (stroke.shape != null) return ShapeSnapResult.Unavailable("The stroke is already a shape")
        val canvasPerScreenPixel = viewport.screenToCanvas()?.scaleFactor ?: 1.0
        val snap =
            QuickShapeSnap.recognize(stroke.samples, canvasPerScreenPixel)
                ?: return ShapeSnapResult.Unavailable("The stroke is not a shape")
        val shape = checkNotNull(snap.morph(constrain = false, frameCount = 1))
        val start = redrawLastStroke(stroke)
        engine.replaceStroke(shape.frames.single())
        engine.endStroke()
        endStroke(start, stroke.tool, Point(shape.end.x, shape.end.y), stroke.samples, shape.shape)
        return ShapeSnapResult.Applied(shape.shape)
    }

    private fun lastStrokeProblem(stroke: FinishedStroke): String? {
        val session = documentSession ?: return "No open document"
        if (adjustmentEditor != null || transformEditor != null) return "An edit is open"
        val last = session.history.nextUndo
        if (last == null || last.before != null || last.pixelEntryIds != listOf(stroke.start.historyEntry)) {
            return "The last step is not that stroke"
        }
        if (!session.canPaint) return "The layer cannot be painted"
        return null
    }

    private fun redrawLastStroke(stroke: FinishedStroke): StrokeStart {
        val session = checkNotNull(documentSession)
        session.undo()
        val entry = session.beginStroke()
        val start = stroke.start.copy(style = stroke.start.style.copy(historyEntry = entry), historyEntry = entry)
        engine.beginStroke(stroke.tool, start.layerId, start.style)
        return start
    }

    var shapeEditOffer: Int? by mutableStateOf(null)
        private set

    private var shapeOfferSerial = 0

    fun dismissShapeEditOffer(serial: Int) {
        if (shapeEditOffer == serial) shapeEditOffer = null
    }

    var editedShape: RecognizedShape? by mutableStateOf(null)
        private set

    var shapeRotateHandleScreenDistance: Double = 300.0

    fun shapeHandles(rotateScreenDistance: Double = shapeRotateHandleScreenDistance): List<ShapeHandle> {
        val shape = editedShape ?: return emptyList()
        val zoom = viewport.canvasToScreen()?.scaleFactor ?: 1.0
        return ShapeEditing.handles(shape, rotateScreenDistance / zoom)
    }

    private var shapeEditSnap: QuickShapeSnap? = null
    private var shapeDrag: StrokeStart? = null

    fun beginShapeEdit(): ShapeSnapResult {
        val stroke = lastStroke ?: return ShapeSnapResult.Unavailable("No stroke to edit")
        val shape = stroke.shape ?: return ShapeSnapResult.Unavailable("The last stroke is not a shape")
        lastStrokeProblem(stroke)?.let { return ShapeSnapResult.Unavailable(it) }
        shapeEditOffer = null
        shapeEditSnap = QuickShapeSnap(stroke.samples, shape)
        editedShape = shape
        return ShapeSnapResult.Applied(shape)
    }

    fun startShapeDrag(): Boolean {
        if (shapeDrag != null) return true
        val stroke = lastStroke
        val shape = editedShape
        val snap = shapeEditSnap
        if (stroke == null || shape == null || snap == null || lastStrokeProblem(stroke) != null) {
            endShapeEdit()
            return false
        }
        shapeDrag = redrawLastStroke(stroke)
        engine.replaceStroke(snap.samplesFor(shape))
        return true
    }

    fun dragShapeHandle(
        index: Int,
        to: Point,
    ) {
        if (shapeDrag == null) return
        val shape = editedShape ?: return
        val snap = shapeEditSnap ?: return
        val moved = ShapeEditing.moved(shape, index, ShapePoint(to.x, to.y))
        if (moved == shape) return
        editedShape = moved
        engine.replaceStroke(snap.samplesFor(moved))
    }

    fun endShapeDrag() {
        val start = shapeDrag ?: return
        shapeDrag = null
        val stroke = checkNotNull(lastStroke)
        engine.endStroke()
        endStroke(start, stroke.tool, samples = stroke.samples, shape = editedShape, edit = true)
    }

    fun moveShapeHandle(
        index: Int,
        to: Point,
    ): ShapeSnapResult {
        val shape = editedShape ?: return ShapeSnapResult.Unavailable("No shape is edited")
        if (index !in ShapeEditing.handles(shape, 0.0).indices) return ShapeSnapResult.Unavailable("No handle $index")
        if (!startShapeDrag()) return ShapeSnapResult.Unavailable("The shape's stroke is no longer the last step")
        dragShapeHandle(index, to)
        endShapeDrag()
        return ShapeSnapResult.Applied(checkNotNull(editedShape))
    }

    fun endShapeEdit() {
        endShapeDrag()
        editedShape = null
        shapeEditSnap = null
    }

    private fun leaveShapeEdit() {
        shapeEditOffer = null
        if (editedShape != null) endShapeEdit()
    }

    fun openArtwork(loaded: LoadedArtwork) {
        val open = OpenArtwork(loaded.id, loaded.folder, loaded.info, loaded.uuids, loaded.manifest)
        openDocument(loaded.state, open)
        checkFonts(loaded.state, loaded.folder)
    }

    var rasterizePrompt: RasterizePrompt? by mutableStateOf(null)
        private set

    data class RasterizePrompt(
        val layerId: Int,
        val then: (() -> Unit)? = null,
    )

    fun askToRasterize(
        layerId: Int,
        then: (() -> Unit)? = null,
    ) {
        rasterizePrompt = RasterizePrompt(layerId, then)
    }

    fun answerRasterizePrompt(rasterize: Boolean) {
        val prompt = rasterizePrompt ?: return
        rasterizePrompt = null
        if (!rasterize) return
        if (textEditor.editing?.layerId == prompt.layerId) textEditor.finish()
        runCatching { rasterizeContent(prompt.layerId) }.onSuccess { prompt.then?.invoke() }
    }

    fun rasterizeContent(layerId: Int) {
        val session = documentSession ?: return
        if (session.state.raster(layerId)?.video != null) session.rasterizeVideo(layerId) else session.rasterizeText(layerId)
    }

    val rasterizePromptIsVideo: Boolean
        get() = rasterizePrompt?.let { documentSession?.state?.raster(it.layerId)?.video != null } == true

    val activeVideoLayerId: Int?
        get() {
            val session = documentSession ?: return null
            return (session.state.activeLayer as? RasterLayer)?.takeIf { it.video != null && !session.maskSelected }?.id
        }

    val activeTextLayerId: Int?
        get() {
            val session = documentSession ?: return null
            return (session.state.activeLayer as? RasterLayer)?.takeIf { it.text != null && !session.maskSelected }?.id
        }

    var fontLibrary: FontLibrary? = null

    val textEditor = TextEditor(engine, { documentSession }, { artwork?.info?.dpi ?: DocumentInfo.DEFAULT_DPI })

    fun tapText(
        x: Double,
        y: Double,
    ) {
        val session = documentSession ?: return
        scope.launch {
            val editing = textEditor.editing
            editing?.geometry?.let { geometry ->
                textPoint(geometry, x, y)?.let { (u, v) ->
                    textEditor.setSelection(caretIndex(editing.text, geometry, u, v), caretIndex(editing.text, geometry, u, v))
                    return@launch
                }
            }
            val layers =
                session.state
                    .allNodes()
                    .filterIsInstance<RasterLayer>()
                    .filter { it.text != null && !it.hidden }
                    .toList()
                    .asReversed()
            for (layer in layers) {
                val model = layer.text!!.model
                val geometry = textEditor.geometryOf(model)
                val point = textPoint(geometry, x, y) ?: continue
                textEditor.edit(layer.id, caretIndex(TextModelEditing.plainText(model), geometry, point.first, point.second))
                return@launch
            }
            val color = primaryColor
            textEditor.place(x, y, RgbaColor(color.red, color.green, color.blue, 1f))
        }
    }

    private fun textPoint(
        geometry: TextGeometry,
        x: Double,
        y: Double,
    ): Pair<Float, Float>? {
        val t = geometry.transform
        val determinant = t[0] * t[3] - t[1] * t[2]
        if (determinant == 0.0) return null
        val dx = x - t[4]
        val dy = y - t[5]
        val u = ((t[3] * dx - t[2] * dy) / determinant).toFloat()
        val v = ((-t[1] * dx + t[0] * dy) / determinant).toFloat()
        val room = TEXT_TAP_ROOM_PX
        val inside = u >= -room && v >= -room && u <= geometry.width + room && v <= geometry.height + room
        return if (inside) u to v else null
    }

    private fun caretIndex(
        text: String,
        geometry: TextGeometry,
        u: Float,
        v: Float,
    ): Int {
        val codePoint = geometry.hitTest(u, v).coerceIn(0, text.codePointCount(0, text.length))
        return text.offsetByCodePoints(0, codePoint)
    }

    var missingFonts: List<String> by mutableStateOf(emptyList())
        private set

    fun dismissMissingFonts() {
        missingFonts = emptyList()
    }

    private fun checkFonts(
        state: DocumentState,
        folder: File,
    ) {
        val library = fontLibrary ?: return
        val used =
            state
                .textModels()
                .values
                .flatMap { TextLayerContent(it).fontIds }
                .toSet()
        if (used.isEmpty()) return
        scope.launch {
            library.awaitLoaded()
            library.useDocumentFonts(folder)
            if (artwork?.folder == folder) missingFonts = used.filter { library.find(it) == null }.sorted()
        }
    }

    fun createArtwork(
        folder: File,
        initial: DocumentState,
        info: DocumentInfo,
    ) {
        val open = OpenArtwork(info.id, folder, info, LayerUuids.EMPTY, manifest = null)
        openDocument(initial, open)
        persistence.saveNow(withThumbnail = true)
    }

    fun closeArtwork(onClosed: (Boolean) -> Unit = {}) {
        if (documentSession == null) {
            onClosed(true)
            return
        }
        finishAdjustment(commit = true)
        leaveShapeEdit()
        fullScreen = false
        copyPasteMenu = null
        lastStroke = null
        playback.stop()
        persistence.saveNow(withThumbnail = true) { saved ->
            persistence.detach()
            textEditor.finish()
            engine.closeDocument()
            videoLayers.reset()
            timelapse.detach()
            documentSession = null
            reference.close()
            artwork = null
            document = null
            documentVersion++
            onClosed(saved)
        }
    }

    private fun openDocument(
        initial: DocumentState,
        open: OpenArtwork? = null,
        keepView: Boolean = false,
    ) {
        persistence.detach()
        playback.stop()
        videoLayers.reset()
        documentShown = false
        leaveShapeEdit()
        lastStroke = null
        adjustmentEditor = null
        adjustmentMenu = null
        if (canvasToolState == CanvasTool.Adjust) canvasToolState = CanvasTool.Brush
        selectionEditor.takePending()
        pendingSelectionChanged()
        val treeJson = initial.toEngineLayerTree().toString()
        val error =
            if (open == null) {
                engine.openDocument(initial.widthPx, initial.heightPx, treeJson)
            } else {
                open.uuids = open.uuids.assignedFor(initial)
                engine.openDocumentFolder(
                    initial.widthPx,
                    initial.heightPx,
                    treeJson,
                    open.id,
                    open.folder.path,
                    ArtworkStorage.surfaceFolders(initial, open.uuids),
                )
            }
        check(error == null) { "Invalid layer tree: $error" }
        val maximumLayers = {
            val state = documentSession?.state ?: initial
            maximumLayers(memoryBudgetPolicy.maximumBytes, state.widthPx, state.heightPx, state.pixelFormat.bytesPerPixel)
        }
        documentSession =
            DocumentSession(engine.asDocumentEngine(selectionDisplay) { artwork?.info?.dpi ?: 72.0 }, initial, layerNames, maximumLayers) {
                val state = documentSession?.state
                if (state != null && (state.widthPx != document?.widthPx || state.heightPx != document?.heightPx)) {
                    viewport.onCanvasChanged(state.widthPx, state.heightPx)
                }
                document = state
                documentVersion++
                textEditor.onDocumentChanged()
                if ((layersPanelVisible || state?.animation?.enabled == true) && state != null) layerThumbnails.invalidate(state)
                videoLayers.onDocumentChanged(state)
                timelapse.onDocumentChanged(state)
                noteEdit()
                persistence.documentChanged()
            }.also { it.onContentLayerPixels = { layerId -> askToRasterize(layerId) } }
        artwork = open
        editingTime.reset()
        timelapse.attach(open?.folder, open?.info?.timelapse)
        if (open != null) persistence.attach(open) { documentSession?.state }
        reference.open(open)
        guides = open?.info?.guides ?: GuideSettings.forCanvas(initial.widthPx, initial.heightPx)
        selectedVanishingPoint = null
        document = initial
        documentVersion++
        if (!keepView) viewport.onCanvasChanged(initial.widthPx, initial.heightPx)
    }

    fun openTestDocument(
        widthPx: Int,
        heightPx: Int,
        layerCount: Int,
        fillTiles: Int,
    ) {
        var initial = DocumentState(widthPx, heightPx)
        repeat(layerCount) { index -> initial = initial.addRasterLayer(layerNames.layer(index + 1)).first }
        openDocument(initial)
        if (fillTiles != 0) engine.fillRandomTiles(layerId = 0, tileCount = fillTiles, seed = DEFAULT_FILL_SEED)
    }

    fun fillRandomTiles(
        layerId: Int,
        tileCount: Int,
        seed: Int,
    ) = engine.fillRandomTiles(layerId, tileCount, seed)

    fun resetFrameStatistics() = engine.resetFrameStatistics()

    fun keepGpuAwake(milliseconds: Int) = engine.keepGpuAwake(milliseconds)

    suspend fun readSelectedPixels(): Triple<IntArray, Int, Int>? {
        val documentSession = documentSession ?: return null
        documentSession.copySelectionForReadback()
        val render =
            try {
                suspendCancellableCoroutine { continuation ->
                    engine.requestThumbnails(intArrayOf(READBACK_SURFACE_ID), booleanArrayOf(false), Int.MAX_VALUE) {
                        continuation.resume(it)
                    }
                }
            } finally {
                documentSession.releaseReadback()
            }
        if (render.error != null || render.width == 0 || render.height == 0) return null
        val bytes = render.pixels
        val pixels =
            IntArray(render.width * render.height) { index ->
                val offset = index * 4
                ((bytes[offset + 3].toInt() and 0xFF) shl 24) or ((bytes[offset].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset + 2].toInt() and 0xFF)
            }
        return Triple(pixels, render.width, render.height)
    }

    suspend fun frameStatistics(): FrameStats =
        suspendCancellableCoroutine { continuation -> engine.requestStats { continuation.resume(it.frames) } }

    suspend fun textStatistics(): TextStats? =
        suspendCancellableCoroutine { continuation -> engine.requestStats { continuation.resume(it.text) } }

    suspend fun latencyStatistics(): LatencyStats? =
        suspendCancellableCoroutine { continuation -> engine.requestStats { continuation.resume(it.latency) } }

    var frontBufferEnabled: Boolean by mutableStateOf(true)
        private set

    fun enableFrontBuffer(enabled: Boolean) {
        frontBufferEnabled = enabled
        engine.setFrontBufferEnabled(enabled)
    }

    fun showMemoryHud(visible: Boolean) {
        memoryHudVisible = visible
    }

    fun showDirtyTileOverlay(visible: Boolean) {
        dirtyTileOverlayVisible = visible
        engine.setDirtyTileOverlay(visible)
    }

    fun setMemoryBudget(bytes: Long) = engine.setMemoryBudget(memoryBudgetPolicy.setMaximum(bytes))

    companion object {
        const val DEFAULT_FILL_THRESHOLD = 0.1f

        const val THRESHOLD_DRAG_PX = 800f
        const val FILL_ALL_TILES = -1
        const val DEFAULT_FILL_SEED = 20261014
    }
}

val AdjustmentFilter.historyLabel: HistoryLabel
    get() =
        when (this) {
            AdjustmentFilter.HueSaturationBrightness -> HistoryLabel.HueSaturationBrightness
            AdjustmentFilter.ColorBalance -> HistoryLabel.ColorBalance
            AdjustmentFilter.Curves -> HistoryLabel.Curves
            AdjustmentFilter.GradientMap -> HistoryLabel.GradientMap
            AdjustmentFilter.GaussianBlur -> HistoryLabel.GaussianBlur
            AdjustmentFilter.MotionBlur -> HistoryLabel.MotionBlur
            AdjustmentFilter.PerspectiveBlur -> HistoryLabel.PerspectiveBlur
            AdjustmentFilter.Sharpen -> HistoryLabel.Sharpen
            AdjustmentFilter.Noise -> HistoryLabel.Noise
            AdjustmentFilter.Bloom -> HistoryLabel.Bloom
            AdjustmentFilter.Glitch -> HistoryLabel.Glitch
            AdjustmentFilter.Halftone -> HistoryLabel.Halftone
            AdjustmentFilter.ChromaticAberration -> HistoryLabel.ChromaticAberration
            AdjustmentFilter.Liquify -> HistoryLabel.Liquify
        }

private const val LIQUIFY_MODE = 5

private const val HISTOGRAM_SIDE = 512

private const val TEXT_TAP_ROOM_PX = 12f

private fun affineOf(state: com.mobyle.nanquim.canvas.transform.TransformState): List<Double>? {
    if (state.warpGrid != null) return null
    val source = state.sourceQuad
    val target = state.targetQuad
    val fourthX = target[1].x + target[3].x - target[0].x
    val fourthY = target[1].y + target[3].y - target[0].y
    if (kotlin.math.hypot(target[2].x - fourthX, target[2].y - fourthY) > AFFINE_TOLERANCE_PX) return null
    val ux = source[1].x - source[0].x
    val uy = source[1].y - source[0].y
    val vx = source[3].x - source[0].x
    val vy = source[3].y - source[0].y
    val determinant = ux * vy - uy * vx
    if (kotlin.math.abs(determinant) < 1e-9) return null
    val px = target[1].x - target[0].x
    val py = target[1].y - target[0].y
    val qx = target[3].x - target[0].x
    val qy = target[3].y - target[0].y
    val a = (px * vy - qx * uy) / determinant
    val c = (qx * ux - px * vx) / determinant
    val b = (py * vy - qy * uy) / determinant
    val d = (qy * ux - py * vx) / determinant
    val tx = target[0].x - (a * source[0].x + c * source[0].y)
    val ty = target[0].y - (b * source[0].x + d * source[0].y)
    return listOf(a, b, c, d, tx, ty)
}

private fun composeAffine(
    first: List<Double>,
    second: List<Double>,
): List<Double> =
    listOf(
        first[0] * second[0] + first[2] * second[1],
        first[1] * second[0] + first[3] * second[1],
        first[0] * second[2] + first[2] * second[3],
        first[1] * second[2] + first[3] * second[3],
        first[0] * second[4] + first[2] * second[5] + first[4],
        first[1] * second[4] + first[3] * second[5] + first[5],
    )

private const val AFFINE_TOLERANCE_PX = 0.5

private const val LAYER_SELECT_ALPHA = 25

data class QuickMenuAnchor(
    val x: Float?,
    val y: Float?,
)

private const val CONVERSION_TIMEOUT_MILLIS = 5_000L
