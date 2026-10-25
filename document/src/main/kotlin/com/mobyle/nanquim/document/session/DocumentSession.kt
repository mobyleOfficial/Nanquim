package com.mobyle.nanquim.document.session

import kotlinx.serialization.json.JsonObject
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.animation.animationAppearance
import com.mobyle.nanquim.document.animation.withAnimation
import com.mobyle.nanquim.document.history.HistoryEntry
import com.mobyle.nanquim.document.history.HistoryLabel
import com.mobyle.nanquim.document.history.UndoHistory
import com.mobyle.nanquim.document.layer.Background
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.CanvasOperation
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerNode
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.SavedSelection
import com.mobyle.nanquim.document.layer.TextLayerContent
import com.mobyle.nanquim.document.layer.ContentLayerPixelsException
import com.mobyle.nanquim.document.layer.VideoLayerContent
import com.mobyle.nanquim.document.layer.addVideoLayer
import com.mobyle.nanquim.document.layer.rasterizeVideo
import com.mobyle.nanquim.document.layer.setVideoTime
import com.mobyle.nanquim.document.layer.setPrivate
import com.mobyle.nanquim.document.layer.addTextLayer
import com.mobyle.nanquim.document.layer.rasterizeText
import com.mobyle.nanquim.document.layer.setTextContent
import com.mobyle.nanquim.document.layer.textModels
import com.mobyle.nanquim.document.layer.addMask
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.combineDown
import com.mobyle.nanquim.document.layer.delete
import com.mobyle.nanquim.document.layer.duplicate
import com.mobyle.nanquim.document.layer.flattenDocument
import com.mobyle.nanquim.document.layer.flattenGroup
import com.mobyle.nanquim.document.layer.groupLayers
import com.mobyle.nanquim.document.layer.mergeDown
import com.mobyle.nanquim.document.layer.mergeLayers
import com.mobyle.nanquim.document.layer.move
import com.mobyle.nanquim.document.layer.nodeBelow
import com.mobyle.nanquim.document.layer.removeMask
import com.mobyle.nanquim.document.layer.rename
import com.mobyle.nanquim.document.layer.setAlphaLock
import com.mobyle.nanquim.document.layer.setAssisted
import com.mobyle.nanquim.document.layer.setBackground
import com.mobyle.nanquim.document.layer.setBlend
import com.mobyle.nanquim.document.layer.setClipping
import com.mobyle.nanquim.document.layer.setCollapsed
import com.mobyle.nanquim.document.layer.setHidden
import com.mobyle.nanquim.document.layer.setLocked
import com.mobyle.nanquim.document.layer.setMaskEnabled
import com.mobyle.nanquim.document.layer.setMaskInverted
import com.mobyle.nanquim.document.layer.setOpacity
import com.mobyle.nanquim.document.layer.setReference
import com.mobyle.nanquim.document.layer.toEngineLayerTree
import com.mobyle.nanquim.document.layer.ungroup
import com.mobyle.nanquim.document.layer.withActiveLayer

interface LayerNames {
    fun layer(number: Int): String

    fun group(number: Int): String

    fun copyOf(name: String): String

    fun flattened(): String

    fun selection(number: Int): String
}

data class SelectionStroke(
    val selectionId: Int,
    val entry: Long,
    val newSurface: Boolean,
)

enum class SelectionCombine {
    Replace,
    Add,
    Subtract,
    Intersect,
}

private const val MINIMUM_POLYGON_VALUES = 6
private const val MAXIMUM_CANVAS_SIDE = 16384
private const val SURFACE_LAYER = 0
private const val SURFACE_MASK = 1
private const val SURFACE_SELECTION = 2
private const val FILTER_NEAREST = 0
private const val FILTER_BICUBIC = 2

const val CLIPBOARD_SURFACE_ID = 0x7FFFFFF0

const val READBACK_SURFACE_ID = 0x7FFFFFF1

const val ADJUSTMENT_MASK_SURFACE_ID = 0x7FFFFFF2
private const val MAXIMUM_FEATHER_RADIUS = 100f

class DocumentSession(
    private val engine: DocumentEngine,
    initial: DocumentState,
    private val names: LayerNames,
    private val maximumLayers: () -> Int = { Int.MAX_VALUE },
    maximumSteps: Int = UndoHistory.DEFAULT_MAXIMUM_STEPS,
    private val onChanged: () -> Unit = {},
) {
    private val releasedPixelEntries = mutableListOf<Long>()

    val history =
        UndoHistory(maximumSteps = maximumSteps, onDropped = { entries ->
            entries.forEach {
                releasedPixelEntries +=
                    it.pixelEntryIds
            }
        })

    var state: DocumentState = initial
        private set

    var soloLayerId: Int? = null
        private set

    var playbackFrameId: Int? = null
        set(value) {
            if (field == value) return
            field = value
            sendTree()
        }

    var exportHidesPrivate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sendTree()
        }

    var exportHidesBackground: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            sendTree()
        }

    var maskSelected: Boolean = false
        private set

    var selectedIds: Set<Int> = setOfNotNull(initial.activeLayerId)
        private set

    private var knownSurfaces: Set<Int> = initial.pixelSurfaceIds()

    private var sentTextModels: Map<Int, JsonObject> = initial.textModels()

    private data class TreeKey(
        val frameId: Int?,
        val onion: Boolean,
        val soloLayerId: Int?,
        val hideBackground: Boolean,
        val hidePrivate: Boolean,
    )

    private var treeCacheState: DocumentState? = null
    private val treeCache = HashMap<TreeKey, String>()
    private var nextPixelEntry = 1L

    private var pendingChangeStart: DocumentState? = null
    private var pendingChangeLabel: HistoryLabel? = null

    init {
        sendTree()
        engine.showSelection(state.selectionId)
    }

    val activeLayer: LayerNode? get() = state.activeLayer

    val activeSurfaceId: Int?
        get() {
            val layer = state.activeLayer as? RasterLayer ?: return null
            return if (maskSelected) layer.mask?.id else layer.id
        }

    val selectionId: Int? get() = state.selectionId

    val canPaint: Boolean get() = activeSurfaceId != null && !state.isEffectivelyLocked(state.activeLayerId!!)

    val layerLimitReached: Boolean get() = state.layerCount >= maximumLayers()

    fun select(
        id: Int,
        mask: Boolean = false,
    ) {
        finishPendingChange()
        val newState = state.withActiveLayer(id)
        maskSelected = mask && (newState.find(id) as? RasterLayer)?.mask != null
        selectedIds = setOf(id)
        update(newState)
    }

    fun toggleSelected(id: Int) {
        if (state.find(id) == null) throw LayerOperationException("No layer $id")
        selectedIds = if (id in selectedIds && id != state.activeLayerId) selectedIds - id else selectedIds + id
        onChanged()
    }

    fun setSolo(id: Int?) {
        if (id != null && state.raster(id) == null) throw LayerOperationException("Layer $id is not a raster layer")
        soloLayerId = id
        sendTree()
        onChanged()
    }

    fun setCollapsed(
        groupId: Int,
        collapsed: Boolean,
    ) = update(state.setCollapsed(groupId, collapsed))

    fun newLayer(): Int {
        requireRoomForLayers(1)
        val (newState, id) = state.addRasterLayer(names.layer(state.layerCount + 1))
        maskSelected = false
        selectedIds = setOf(id)
        record(HistoryLabel.NewLayer, newState)
        return id
    }

    fun duplicate(id: Int): Int {
        val node = state.find(id) ?: throw LayerOperationException("No layer $id")
        requireRoomForLayers(DocumentState(state.widthPx, state.heightPx, layers = listOf(node)).layerCount)
        val result = state.duplicate(id, names::copyOf)
        result.surfaceCopies.forEach { (source, copy) -> engine.copySurface(source, copy) }
        selectedIds = setOf(result.newNodeId)
        record(HistoryLabel.DuplicateLayer, result.state)
        return result.newNodeId
    }

    fun delete(ids: Collection<Int>) {
        val newState = state.delete(ids)
        selectedIds = setOfNotNull(newState.activeLayerId)
        if (soloLayerId != null && newState.raster(soloLayerId!!) == null) soloLayerId = null
        record(HistoryLabel.DeleteLayer, newState)
    }

    fun rename(
        id: Int,
        name: String,
    ) = record(HistoryLabel.RenameLayer, state.rename(id, name))

    fun setOpacity(
        id: Int,
        opacity: Float,
        final: Boolean = true,
    ) = continuousChange(HistoryLabel.LayerOpacity, state.setOpacity(id, opacity), final)

    fun setBlend(
        id: Int,
        blend: BlendMode,
    ) = record(HistoryLabel.LayerBlendMode, state.setBlend(id, blend))

    fun setHidden(
        id: Int,
        hidden: Boolean,
    ) = record(HistoryLabel.LayerVisibility, state.setHidden(id, hidden))

    fun setLocked(
        id: Int,
        locked: Boolean,
    ) = record(HistoryLabel.LockLayer, state.setLocked(id, locked))

    fun setPrivate(
        id: Int,
        isPrivate: Boolean,
    ) = record(HistoryLabel.Private, state.setPrivate(id, isPrivate))

    fun setAlphaLock(
        id: Int,
        alphaLock: Boolean,
    ) = record(HistoryLabel.AlphaLock, state.setAlphaLock(id, alphaLock))

    fun setAssisted(
        id: Int,
        assisted: Boolean,
    ) = record(HistoryLabel.DrawingAssist, state.setAssisted(id, assisted))

    fun addTextLayer(
        content: TextLayerContent,
        final: Boolean = true,
    ): Int {
        val (newState, id) = state.addTextLayer(content)
        continuousChange(HistoryLabel.EditText, newState, final)
        return id
    }

    fun setTextContent(
        id: Int,
        content: TextLayerContent,
        final: Boolean = true,
        label: HistoryLabel = HistoryLabel.EditText,
    ) {
        requireUnlocked(id)
        continuousChange(label, state.setTextContent(id, content), final)
    }

    fun setAnimation(
        settings: AnimationSettings,
        final: Boolean = true,
    ) = continuousChange(HistoryLabel.Animation, state.withAnimation(settings), final)

    val currentFrameId: Int?
        get() {
            val active = state.activeLayerId ?: return null
            return state.layers.firstOrNull { it.id == active || state.ancestorsOf(active).any { ancestor -> ancestor.id == it.id } }?.id
        }

    fun commitTextEdit() {
        if (pendingChangeLabel != HistoryLabel.EditText) return
        finishPendingChange()
        onChanged()
    }

    fun revertTextEdit() {
        if (pendingChangeLabel != HistoryLabel.EditText) return
        val start = pendingChangeStart ?: return
        pendingChangeStart = null
        pendingChangeLabel = null
        restore(start)
        onChanged()
    }

    fun rasterizeText(id: Int) {
        requireUnlocked(id)
        record(HistoryLabel.RasterizeText, state.rasterizeText(id))
    }

    fun addVideoLayer(
        name: String,
        content: VideoLayerContent,
    ): Int {
        requireRoomForLayers(1)
        val (newState, id) = state.addVideoLayer(name, content)
        maskSelected = false
        selectedIds = setOf(id)
        record(HistoryLabel.NewLayer, newState)
        return id
    }

    fun setVideoTime(
        id: Int,
        timeMicroseconds: Long,
        final: Boolean = true,
    ) = continuousChange(HistoryLabel.VideoFrame, state.setVideoTime(id, timeMicroseconds), final)

    fun rasterizeVideo(id: Int) {
        requireUnlocked(id)
        record(HistoryLabel.RasterizeVideo, state.rasterizeVideo(id))
    }

    fun setClipping(
        id: Int,
        clipping: Boolean,
    ) = record(HistoryLabel.ClippingMask, state.setClipping(id, clipping))

    fun setReference(
        id: Int,
        reference: Boolean,
    ) = record(HistoryLabel.ReferenceLayer, state.setReference(id, reference))

    fun setBackground(background: Background) = record(HistoryLabel.BackgroundColor, state.setBackground(background))

    fun move(
        id: Int,
        parentId: Int?,
        index: Int,
    ) = record(HistoryLabel.MoveLayer, state.move(id, parentId, index))

    fun group(ids: Collection<Int>): Int {
        val (newState, groupId) = state.groupLayers(ids, names.group(groupCount() + 1))
        selectedIds = setOf(groupId)
        maskSelected = false
        record(HistoryLabel.GroupLayers, newState)
        return groupId
    }

    fun ungroup(groupId: Int) {
        val newState = state.ungroup(groupId)
        selectedIds = setOfNotNull(newState.activeLayerId)
        record(HistoryLabel.Ungroup, newState)
    }

    fun combineDown(id: Int): Int {
        val (newState, groupId) = state.combineDown(id, names.group(groupCount() + 1))
        selectedIds = setOf(groupId)
        record(HistoryLabel.CombineDown, newState)
        return groupId
    }

    fun addMask(layerId: Int): Int {
        val (newState, maskId) = state.addMask(layerId)
        record(HistoryLabel.AddMask, newState.withActiveLayer(layerId))
        maskSelected = true
        onChanged()
        return maskId
    }

    fun deleteMask(layerId: Int) {
        val (newState, _) = state.removeMask(layerId)
        if (state.activeLayerId == layerId) maskSelected = false
        record(HistoryLabel.DeleteMask, newState)
    }

    fun applyMask(layerId: Int) {
        commitPendingSessions()
        val layer = state.raster(layerId) ?: throw LayerOperationException("Layer $layerId is not a raster layer")
        val mask = layer.mask ?: throw LayerOperationException("Layer $layerId has no mask")
        requireUnlocked(layerId)
        val entry = newPixelEntry()
        val baked = layer.copy(opacity = 1f, blend = BlendMode.Normal, hidden = false, clipping = false, mask = mask.copy(enabled = true))
        engine.composeIntoSurface(layerId, treeOf(listOf(baked)), entry)
        if (state.activeLayerId == layerId) maskSelected = false
        record(HistoryLabel.ApplyMask, state.removeMask(layerId).first, listOf(entry))
    }

    fun setMaskEnabled(
        layerId: Int,
        enabled: Boolean,
    ) = record(HistoryLabel.MaskSettings, state.setMaskEnabled(layerId, enabled))

    fun setMaskInverted(
        layerId: Int,
        inverted: Boolean,
    ) = record(HistoryLabel.MaskSettings, state.setMaskInverted(layerId, inverted))

    fun mergeDown(id: Int) {
        commitPendingSessions()
        val upper = state.raster(id) ?: throw LayerOperationException("Layer $id is not a raster layer")
        val lower = state.nodeBelow(id) as? RasterLayer ?: throw LayerOperationException("There is no raster layer below layer $id")
        val result = state.mergeDown(id)
        val entry = newPixelEntry()
        val lowerPart = lower.copy(blend = BlendMode.Normal, hidden = false, clipping = false)
        engine.composeIntoSurface(lower.id, treeOf(listOf(lowerPart, upper)), entry)
        selectedIds = setOf(lower.id)
        maskSelected = false
        record(HistoryLabel.MergeDown, result.state, listOf(entry))
    }

    fun mergeLayers(ids: Collection<Int>) {
        commitPendingSessions()
        val result = state.mergeLayers(ids)
        val layers = state.rasterLayers().filter { it.id in ids }
        val target = layers.first()
        val entry = newPixelEntry()
        val parts =
            listOf(target.copy(blend = BlendMode.Normal, hidden = false, clipping = false)) +
                layers.drop(1).map { it.copy(clipping = false) }
        engine.composeIntoSurface(target.id, treeOf(parts), entry)
        selectedIds = setOf(target.id)
        maskSelected = false
        record(HistoryLabel.MergeLayers, result.state, listOf(entry))
    }

    fun flattenGroup(groupId: Int) {
        val group = state.group(groupId) ?: throw LayerOperationException("Layer $groupId is not a group")
        val result = state.flattenGroup(groupId)
        engine.composeIntoSurface(result.targetLayerId, treeOf(group.children), 0)
        selectedIds = setOf(result.targetLayerId)
        maskSelected = false
        record(HistoryLabel.FlattenGroup, result.state)
    }

    fun flattenDocument() {
        val result = state.flattenDocument(names.flattened())
        engine.composeIntoSurface(result.targetLayerId, treeOf(state.layers), 0)
        selectedIds = setOf(result.targetLayerId)
        maskSelected = false
        soloLayerId = null
        record(HistoryLabel.Flatten, result.state)
    }

    fun clear(layerId: Int? = null) = clear(layerId, HistoryLabel.ClearLayer)

    private fun clear(
        layerId: Int?,
        label: HistoryLabel,
    ) {
        val (surface, mask) = pixelTarget(layerId)
        val entry = newPixelEntry()
        if (!withinSelection(surface, mask, FillOperation.Clear, RgbaColor.BLACK, entry)) engine.clearSurface(surface, entry)
        recordPixels(label, entry)
    }

    private var transformEntry: Long? = null

    val transformActive: Boolean get() = transformEntry != null

    fun beginTransform(requestId: Int) {
        val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
        requireUnlocked(layer.id)
        if (layer.video != null) requireNotContentLayer(layer.id)
        finishPendingChange()
        endFill(commit = true)
        endTransform(commit = true)
        endAdjustment(commit = true)
        engine.beginTransform(layer.id, state.selectionId, requestId)
        transformEntry = newPixelEntry()
    }

    fun setTransformMesh(
        mesh: FloatArray,
        filter: Int,
    ) {
        if (transformEntry != null) engine.setTransformMesh(mesh, filter)
    }

    fun endTransform(commit: Boolean) {
        val entry = transformEntry ?: return
        transformEntry = null
        engine.endTransform(commit, if (commit) entry else 0)
        if (!commit) return
        if (state.selectionId != null) {
            record(HistoryLabel.Transform, state.copy(selectionId = null), listOf(entry))
        } else {
            recordPixels(HistoryLabel.Transform, entry)
        }
    }

    private data class ActiveAdjustment(
        val entry: Long,
        val label: HistoryLabel,
        val pencil: Boolean,
        val maskEntries: MutableList<Long> = mutableListOf(),
    )

    private var activeAdjustment: ActiveAdjustment? = null

    val adjustmentActive: Boolean get() = activeAdjustment != null

    fun beginAdjustment(
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
        label: HistoryLabel,
        pencil: Boolean = false,
    ) {
        val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
        if (maskSelected) throw LayerOperationException("Adjustments apply to the layer, not its mask")
        requireUnlocked(layer.id)
        requireNotContentLayer(layer.id)
        finishPendingChange()
        endFill(commit = true)
        endTransform(commit = true)
        endAdjustment(commit = true)
        engine.beginAdjustment(layer.id, state.selectionId, ADJUSTMENT_MASK_SURFACE_ID.takeIf { pencil }, filter, values, table, amount)
        activeAdjustment = ActiveAdjustment(newPixelEntry(), label, pencil)
    }

    val adjustmentPencil: Boolean get() = activeAdjustment?.pencil == true

    fun beginAdjustmentMaskEntry(): Long {
        val adjustment = activeAdjustment?.takeIf { it.pencil } ?: throw LayerOperationException("No Pencil adjustment is open")
        return newPixelEntry().also(adjustment.maskEntries::add)
    }

    fun clearAdjustmentMask(): Long = beginAdjustmentMaskEntry().also { engine.clearSurface(ADJUSTMENT_MASK_SURFACE_ID, it) }

    fun applyAdjustmentMaskEntry(
        entry: Long,
        undo: Boolean,
    ) {
        if (activeAdjustment?.maskEntries?.contains(entry) == true) engine.applyHistory(entry, undo)
    }

    fun setAdjustment(
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) {
        if (activeAdjustment != null) engine.setAdjustment(filter, values, table, amount)
    }

    fun endAdjustment(commit: Boolean) {
        val adjustment = activeAdjustment ?: return
        activeAdjustment = null
        engine.endAdjustment(commit, if (commit) adjustment.entry else 0)
        if (adjustment.pencil) {
            engine.discardSurfaces(intArrayOf(ADJUSTMENT_MASK_SURFACE_ID))
            if (adjustment.maskEntries.isNotEmpty()) engine.releaseHistory(adjustment.maskEntries.toLongArray())
        }
        if (commit) recordPixels(adjustment.label, adjustment.entry)
    }

    fun canvasOperation(operation: CanvasOperation) {
        finishPendingChange()
        endFill(commit = true)
        endTransform(commit = true)
        endAdjustment(commit = true)
        val mapping = operation.mapping(state.widthPx, state.heightPx)
        if (mapping.width !in 1..MAXIMUM_CANVAS_SIDE || mapping.height !in 1..MAXIMUM_CANVAS_SIDE) {
            throw LayerOperationException("The canvas size must be 1-$MAXIMUM_CANVAS_SIDE px")
        }
        val mesh = mapping.mesh(state.widthPx, state.heightPx)
        val filter = if (mapping.resample) FILTER_BICUBIC else FILTER_NEAREST
        val surfaces =
            state.rasterLayers().flatMap { layer -> listOfNotNull(layer.id to SURFACE_LAYER, layer.mask?.let { it.id to SURFACE_MASK }) } +
                state.selectionSurfaceIds().map { it to SURFACE_SELECTION }
        val entries =
            surfaces.map { (surface, kind) ->
                val entry = newPixelEntry()
                engine.remapSurface(surface, kind, mesh, filter, mapping.width, mapping.height, entry)
                entry
            }
        val label =
            when (operation) {
                is CanvasOperation.Crop -> HistoryLabel.CropCanvas
                is CanvasOperation.Resize -> HistoryLabel.ResizeCanvas
                is CanvasOperation.Rotate -> HistoryLabel.RotateCanvas
                is CanvasOperation.Flip -> HistoryLabel.FlipCanvas
            }
        val newState = state.copy(widthPx = mapping.width, heightPx = mapping.height)
        if (entries.isEmpty() && newState == state) return
        if (entries.isEmpty()) record(label, newState) else record(label, newState, entries, forceStructure = true)
    }

    fun commitPendingSessions() {
        finishPendingChange()
        endFill(commit = true)
        endTransform(commit = true)
        endAdjustment(commit = true)
    }

    var hasClipboard: Boolean = false
        private set

    fun copy(merged: Boolean = false) {
        finishPendingChange()
        val selection = state.selectionId
        if (merged) {
            engine.composeIntoSurface(CLIPBOARD_SURFACE_ID, treeOf(state.layers), 0)
            if (selection != null) engine.copyMasked(CLIPBOARD_SURFACE_ID, CLIPBOARD_SURFACE_ID, selection)
        } else {
            val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
            if (selection != null) {
                engine.copyMasked(layer.id, CLIPBOARD_SURFACE_ID, selection)
            } else {
                engine.clearSurface(CLIPBOARD_SURFACE_ID, 0)
                engine.copySurface(layer.id, CLIPBOARD_SURFACE_ID)
            }
        }
        hasClipboard = true
        onChanged()
    }

    fun copySelectionForReadback() {
        finishPendingChange()
        val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
        val selection = state.selectionId
        if (selection != null) {
            engine.copyMasked(layer.id, READBACK_SURFACE_ID, selection)
        } else {
            engine.clearSurface(READBACK_SURFACE_ID, 0)
            engine.copySurface(layer.id, READBACK_SURFACE_ID)
        }
    }

    fun releaseReadback() = engine.discardSurfaces(intArrayOf(READBACK_SURFACE_ID))

    fun cut() {
        val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
        requireUnlocked(layer.id)
        copy()
        clear(layer.id, HistoryLabel.Cut)
    }

    fun paste(): Int {
        if (!hasClipboard) throw LayerOperationException("Nothing to paste")
        requireRoomForLayers(1)
        finishPendingChange()
        val (newState, id) = state.addRasterLayer(names.layer(state.layerCount + 1))
        engine.copySurface(CLIPBOARD_SURFACE_ID, id)
        maskSelected = false
        selectedIds = setOf(id)
        record(HistoryLabel.Paste, newState)
        return id
    }

    fun copyToNewLayer(): Int {
        val layer = state.activeLayer as? RasterLayer ?: throw LayerOperationException("The active layer is not a raster layer")
        requireRoomForLayers(1)
        finishPendingChange()
        val (newState, id) = state.addRasterLayer(names.copyOf(layer.name))
        val selection = state.selectionId
        if (selection != null) engine.copyMasked(layer.id, id, selection) else engine.copySurface(layer.id, id)
        maskSelected = false
        selectedIds = setOf(id)
        record(HistoryLabel.CopyToNewLayer, newState)
        return id
    }

    fun fill(
        color: RgbaColor,
        layerId: Int? = null,
    ) {
        val (surface, mask) = pixelTarget(layerId)
        val entry = newPixelEntry()
        if (!withinSelection(surface, mask, FillOperation.Color, color, entry)) engine.fillSurface(surface, color, mask, entry)
        recordPixels(HistoryLabel.FillLayer, entry)
    }

    fun invert(layerId: Int? = null) {
        val (surface, mask) = pixelTarget(layerId)
        val entry = newPixelEntry()
        if (!withinSelection(surface, mask, FillOperation.Invert, RgbaColor.BLACK, entry)) engine.invertSurface(surface, mask, entry)
        recordPixels(HistoryLabel.InvertLayer, entry)
    }

    private fun withinSelection(
        surface: Int,
        mask: Boolean,
        operation: FillOperation,
        color: RgbaColor,
        entry: Long,
    ): Boolean {
        val selection = state.selectionId ?: return false
        val target = if (mask) FillTarget.Mask else FillTarget.Layer
        engine.beginFill(FillParameters(surface, 0.0, 0.0, 0.0, target, surface, color, selection, whole = true, operation = operation))
        engine.endFill(commit = true, entry = entry)
        return true
    }

    private data class ActiveFill(
        val entry: Long,
        val label: HistoryLabel,
        val newSelectionId: Int?,
    )

    private var activeFill: ActiveFill? = null

    val fillSourceId: Int? get() = state.referenceLayerId ?: (state.activeLayer as? RasterLayer)?.id

    fun beginFill(
        seedX: Double,
        seedY: Double,
        threshold: Double,
        color: RgbaColor,
        whole: Boolean = false,
    ) {
        val (surface, mask) = pixelTarget(null)
        val source = fillSourceId ?: throw LayerOperationException("There is no layer to fill from")
        endFill(commit = true)
        val target = if (mask) FillTarget.Mask else FillTarget.Layer
        engine.beginFill(FillParameters(source, seedX, seedY, threshold, target, surface, color, state.selectionId, whole = whole))
        activeFill = ActiveFill(newPixelEntry(), HistoryLabel.FloodFill, null)
    }

    fun beginAutomaticSelection(
        seedX: Double,
        seedY: Double,
        threshold: Double,
        combine: SelectionCombine,
    ) {
        finishPendingChange()
        val source = fillSourceId ?: throw LayerOperationException("There is no layer to select from")
        endFill(commit = true)
        val current = state.selectionId
        val replaces =
            current == null || combine == SelectionCombine.Replace || state.savedSelections.any { it.id == current }
        if (current == null && combine != SelectionCombine.Replace && combine != SelectionCombine.Add) {
            throw LayerOperationException("There is no selection")
        }
        val surface = if (replaces) state.nextId else current!!
        if (replaces && current != null && combine != SelectionCombine.Replace) engine.copySurface(current, surface)
        val engineCombine = if (replaces && combine == SelectionCombine.Replace) SelectionCombine.Add else combine
        if (replaces) engine.showSelection(surface)
        engine.beginFill(FillParameters(source, seedX, seedY, threshold, FillTarget.Selection, surface, combine = engineCombine))
        activeFill = ActiveFill(newPixelEntry(), HistoryLabel.AutomaticSelection, surface.takeIf { replaces })
    }

    val fillActive: Boolean get() = activeFill != null

    fun setFillThreshold(threshold: Double) {
        if (activeFill != null) engine.setFillThreshold(threshold.coerceIn(0.0, 1.0))
    }

    fun endFill(commit: Boolean) {
        val fill = activeFill ?: return
        activeFill = null
        engine.endFill(commit, if (commit) fill.entry else 0)
        val newSelection = fill.newSelectionId
        if (!commit) {
            if (newSelection != null) {
                engine.discardSurfaces(intArrayOf(newSelection))
                engine.showSelection(state.selectionId)
            }
            return
        }
        if (newSelection != null) {
            record(fill.label, state.copy(nextId = newSelection + 1, selectionId = newSelection), listOf(fill.entry))
        } else {
            recordPixels(fill.label, fill.entry)
        }
    }

    fun beginStroke(): Long {
        finishPendingChange()
        if (!canPaint) throw LayerOperationException("The active layer cannot be painted")
        if (!maskSelected) state.activeLayerId?.let(::requireNotContentLayer)
        return newPixelEntry()
    }

    fun recordStroke(
        entry: Long,
        label: HistoryLabel = HistoryLabel.BrushStroke,
    ) = recordPixels(label, entry)

    fun selectAll() {
        val (id, newState) = newSelectionSurface()
        engine.fillSurface(id, RgbaColor.WHITE, mask = true, entry = 0)
        record(HistoryLabel.SelectAll, newState)
    }

    fun deselect() {
        if (state.selectionId == null) return
        record(HistoryLabel.Deselect, state.copy(selectionId = null))
    }

    fun invertSelection() = changeSelection(HistoryLabel.InvertSelection) { id, entry -> engine.invertSelection(id, entry) }

    fun selectShape(
        polygon: FloatArray,
        combine: SelectionCombine,
    ) {
        if (polygon.size < MINIMUM_POLYGON_VALUES) throw LayerOperationException("A shape needs at least 3 points")
        if (combine == SelectionCombine.Replace || (combine == SelectionCombine.Add && state.selectionId == null)) {
            val (id, newState) = newSelectionSurface()
            engine.selectPolygon(id, polygon, SelectionCombine.Add, entry = 0)
            record(HistoryLabel.SelectShape, newState)
            return
        }
        changeSelection(HistoryLabel.SelectShape) { id, entry -> engine.selectPolygon(id, polygon, combine, entry) }
    }

    fun featherSelection(radius: Float) {
        if (radius !in 0f..MAXIMUM_FEATHER_RADIUS) throw LayerOperationException("The feather radius is 0-100 px")
        changeSelection(HistoryLabel.FeatherSelection) { id, entry -> engine.featherSelection(id, radius, entry) }
    }

    fun beginSelectionStroke(): SelectionStroke {
        finishPendingChange()
        val current = state.selectionId
        val shared = current != null && state.savedSelections.any { it.id == current }
        val entry = newPixelEntry()
        if (current != null && !shared) return SelectionStroke(current, entry, newSurface = false)
        val id = state.nextId
        if (current != null) engine.copySurface(current, id)
        engine.showSelection(id)
        return SelectionStroke(id, entry, newSurface = true)
    }

    fun recordSelectionStroke(stroke: SelectionStroke) {
        if (stroke.newSurface) {
            val newState = state.copy(nextId = stroke.selectionId + 1, selectionId = stroke.selectionId)
            record(HistoryLabel.PaintSelection, newState, listOf(stroke.entry))
        } else {
            recordPixels(HistoryLabel.PaintSelection, stroke.entry)
        }
    }

    fun cancelSelectionStroke(stroke: SelectionStroke) {
        if (!stroke.newSurface) return
        engine.discardSurfaces(intArrayOf(stroke.selectionId))
        engine.showSelection(state.selectionId)
    }

    private fun changeSelection(
        label: HistoryLabel,
        change: (selectionId: Int, entry: Long) -> Unit,
    ) {
        finishPendingChange()
        val id = state.selectionId ?: throw LayerOperationException("There is no selection")
        if (state.savedSelections.any { it.id == id }) {
            val (copy, newState) = newSelectionSurface()
            engine.copySurface(id, copy)
            change(copy, 0)
            record(label, newState)
            return
        }
        val entry = newPixelEntry()
        change(id, entry)
        recordPixels(label, entry)
    }

    fun selectContents(layerId: Int? = null) {
        val layer =
            (layerId ?: state.activeLayerId)?.let(state::raster)
                ?: throw LayerOperationException("Select contents needs a raster layer")
        val (id, newState) = newSelectionSurface()
        engine.selectContents(id, layer.id, entry = 0)
        record(HistoryLabel.SelectContents, newState)
    }

    fun saveSelection(name: String? = null): Int {
        finishPendingChange()
        val selection = state.selectionId ?: throw LayerOperationException("There is no selection")
        val id = state.nextId
        engine.copySurface(selection, id)
        val saved = SavedSelection(id, name ?: names.selection(state.savedSelections.size + 1))
        record(HistoryLabel.SaveSelection, state.copy(nextId = id + 1, savedSelections = state.savedSelections + saved))
        return id
    }

    fun loadSelection(savedId: Int) {
        if (state.savedSelections.none { it.id == savedId }) throw LayerOperationException("No saved selection $savedId")
        val (id, newState) = newSelectionSurface()
        engine.copySurface(savedId, id)
        record(HistoryLabel.LoadSelection, newState)
    }

    fun deleteSavedSelection(savedId: Int) {
        if (state.savedSelections.none { it.id == savedId }) throw LayerOperationException("No saved selection $savedId")
        record(HistoryLabel.DeleteSavedSelection, state.copy(savedSelections = state.savedSelections.filter { it.id != savedId }))
    }

    private fun newSelectionSurface(): Pair<Int, DocumentState> {
        finishPendingChange()
        val id = state.nextId
        return id to state.copy(nextId = id + 1, selectionId = id)
    }

    fun undo(): HistoryEntry? {
        finishPendingChange()
        val entry = history.undo() ?: return null
        entry.pixelEntryIds.asReversed().forEach { engine.applyHistory(it, undo = true) }
        entry.before?.let { restore(it) }
        onChanged()
        return entry
    }

    fun redo(): HistoryEntry? {
        finishPendingChange()
        val entry = history.redo() ?: return null
        entry.pixelEntryIds.forEach { engine.applyHistory(it, undo = false) }
        entry.after?.let { restore(it) }
        onChanged()
        return entry
    }

    fun onPixelEntrySize(
        entry: Long,
        bytes: Long,
    ) {
        history.setPixelBytes(entry, bytes)
        releaseDropped()
    }

    private fun groupCount(): Int = state.allNodes().count { it is LayerGroup }

    private fun requireRoomForLayers(count: Int) {
        if (state.layerCount + count > maximumLayers()) throw LayerOperationException("The layer limit (${maximumLayers()}) is reached")
    }

    private fun requireNotContentLayer(layerId: Int) {
        val layer = state.raster(layerId) ?: return
        if (layer.text == null && layer.video == null) return
        onContentLayerPixels(layerId)
        throw ContentLayerPixelsException(layerId)
    }

    var onContentLayerPixels: (Int) -> Unit = {}

    private fun requireUnlocked(layerId: Int) {
        if (state.isEffectivelyLocked(layerId)) throw LayerOperationException("Layer $layerId is locked")
    }

    private fun pixelTarget(layerId: Int?): Pair<Int, Boolean> {
        finishPendingChange()
        if (layerId != null) {
            val layer = state.raster(layerId) ?: throw LayerOperationException("Layer $layerId is not a raster layer")
            requireUnlocked(layerId)
            requireNotContentLayer(layer.id)
            return layer.id to false
        }
        if (!maskSelected) state.activeLayerId?.let(::requireNotContentLayer)
        val surface = activeSurfaceId ?: throw LayerOperationException("The active layer is not a raster layer")
        requireUnlocked(state.activeLayerId!!)
        return surface to maskSelected
    }

    private fun newPixelEntry(): Long = nextPixelEntry++

    private fun treeOf(layers: List<LayerNode>): String =
        DocumentState(state.widthPx, state.heightPx, layers = layers).toEngineLayerTree().toString()

    fun rasterizeTextAgain() {
        sentTextModels = emptyMap()
        sendTree()
    }

    private fun sendTree() {
        if (treeCacheState !== state) {
            treeCacheState = state
            treeCache.clear()
        }
        val shownFrame = playbackFrameId ?: currentFrameId
        val key = TreeKey(shownFrame, playbackFrameId == null, soloLayerId, exportHidesBackground, exportHidesPrivate)
        val tree =
            treeCache.getOrPut(key) {
                val frames = state.animationAppearance(shownFrame, onion = playbackFrameId == null)
                state.toEngineLayerTree(soloLayerId, frames, hideBackground = exportHidesBackground, hidePrivate = exportHidesPrivate).toString()
            }
        if (treeCache.size > MAXIMUM_CACHED_TREES) treeCache.clear()
        engine.setLayerTree(tree)
        val models = state.textModels()
        for ((id, model) in models) {
            if (sentTextModels[id] != model) engine.setTextLayer(id, model.toString())
        }
        sentTextModels = models
    }

    private fun sendSelection(previous: DocumentState) {
        if (previous.widthPx != state.widthPx || previous.heightPx != state.heightPx) engine.setCanvasSize(state.widthPx, state.heightPx)
        if (previous.selectionId != state.selectionId) engine.showSelection(state.selectionId)
    }

    private fun update(newState: DocumentState) {
        if (newState != state) {
            val previous = state
            state = newState
            sendTree()
            sendSelection(previous)
        }
        onChanged()
    }

    private fun restore(newState: DocumentState) {
        val previous = state
        state = newState
        sendSelection(previous)
        if (soloLayerId != null && state.raster(soloLayerId!!) == null) soloLayerId = null
        selectedIds = selectedIds.filter { state.find(it) != null }.toSet().ifEmpty { setOfNotNull(state.activeLayerId) }
        if (maskSelected && (state.activeLayer as? RasterLayer)?.mask == null) maskSelected = false
        sendTree()
    }

    private fun continuousChange(
        label: HistoryLabel,
        newState: DocumentState,
        final: Boolean,
    ) {
        if (pendingChangeLabel != null && pendingChangeLabel != label) finishPendingChange()
        val start = pendingChangeStart ?: state
        if (!final) {
            pendingChangeStart = start
            pendingChangeLabel = label
            update(newState)
            return
        }
        pendingChangeStart = null
        pendingChangeLabel = null
        val previous = state
        state = newState
        sendTree()
        sendSelection(previous)
        if (newState != start) addEntry(HistoryEntry(label, start, newState))
        onChanged()
    }

    private fun finishPendingChange() {
        val start = pendingChangeStart ?: return
        val label = pendingChangeLabel!!
        pendingChangeStart = null
        pendingChangeLabel = null
        if (state != start) addEntry(HistoryEntry(label, start, state))
    }

    private fun record(
        label: HistoryLabel,
        newState: DocumentState,
        pixelEntries: List<Long> = emptyList(),
        forceStructure: Boolean = false,
    ) {
        finishPendingChange()
        val before = state
        val changed = newState != before || forceStructure
        if (!changed && pixelEntries.isEmpty()) return
        state = newState
        if (changed) {
            sendTree()
            sendSelection(before)
        }
        addEntry(HistoryEntry(label, before.takeIf { changed }, newState.takeIf { changed }, pixelEntries))
        onChanged()
    }

    private fun recordPixels(
        label: HistoryLabel,
        entry: Long,
    ) {
        addEntry(HistoryEntry(label, pixelEntryIds = listOf(entry)))
        onChanged()
    }

    private fun addEntry(entry: HistoryEntry) {
        history.record(entry)
        releaseDropped()
    }

    private fun releaseDropped() {
        if (releasedPixelEntries.isNotEmpty()) {
            engine.releaseHistory(releasedPixelEntries.toLongArray())
            releasedPixelEntries.clear()
        }
        val referenced = state.pixelSurfaceIds().toMutableSet()
        for (entry in history.entries()) {
            entry.before?.let { referenced += it.pixelSurfaceIds() }
            entry.after?.let { referenced += it.pixelSurfaceIds() }
        }
        val unreferenced = (knownSurfaces - referenced)
        if (unreferenced.isNotEmpty()) engine.discardSurfaces(unreferenced.toIntArray())
        knownSurfaces = referenced
    }
}

private const val MAXIMUM_CACHED_TREES = 512
