package com.mobyle.nanquim.document.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.mobyle.nanquim.document.history.HistoryLabel
import com.mobyle.nanquim.document.layer.BlendMode
import com.mobyle.nanquim.document.layer.CanvasOperation
import com.mobyle.nanquim.document.layer.DocumentState
import com.mobyle.nanquim.document.layer.LayerGroup
import com.mobyle.nanquim.document.layer.LayerOperationException
import com.mobyle.nanquim.document.layer.RasterLayer
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.TextLayerContent
import com.mobyle.nanquim.document.layer.VideoLayerContent
import com.mobyle.nanquim.document.layer.ContentLayerPixelsException
import com.mobyle.nanquim.document.layer.addRasterLayer
import com.mobyle.nanquim.document.layer.nodeBelow
import kotlin.random.Random

private class FakeEngine : DocumentEngine {
    val pixels = mutableMapOf<Int, String>()
    var tree = ""
    var treeUpdates = 0
    val discarded = mutableListOf<Int>()
    val released = mutableListOf<Long>()
    private val before = mutableMapOf<Long, MutableMap<Int, String?>>()
    private val after = mutableMapOf<Long, MutableMap<Int, String?>>()

    fun write(
        entry: Long,
        surface: Int,
        value: String?,
    ) {
        if (entry != 0L) {
            before.getOrPut(entry) { mutableMapOf() }.putIfAbsent(surface, pixels[surface])
            after.getOrPut(entry) { mutableMapOf() }[surface] = value
        }
        if (value == null) pixels.remove(surface) else pixels[surface] = value
    }

    override fun setLayerTree(layerTreeJson: String) {
        tree = layerTreeJson
        treeUpdates++
    }

    val textLayers = mutableListOf<Pair<Int, String>>()

    override fun setTextLayer(
        layerId: Int,
        modelJson: String,
    ) {
        textLayers += layerId to modelJson
    }

    override fun fillSurface(
        surfaceId: Int,
        color: RgbaColor,
        mask: Boolean,
        entry: Long,
    ) = write(entry, surfaceId, "fill($color)")

    override fun clearSurface(
        surfaceId: Int,
        entry: Long,
    ) = write(entry, surfaceId, null)

    override fun invertSurface(
        surfaceId: Int,
        mask: Boolean,
        entry: Long,
    ) = write(entry, surfaceId, "invert(${pixels[surfaceId]})")

    override fun copySurface(
        sourceId: Int,
        destinationId: Int,
    ) {
        pixels[sourceId]?.let { pixels[destinationId] = it }
    }

    override fun composeIntoSurface(
        targetId: Int,
        layerTreeJson: String,
        entry: Long,
    ) {
        write(entry, targetId, "compose(${pixels.toSortedMap()}, ${layerTreeJson.hashCode()})")
    }

    var shownSelection: Int? = null

    override fun invertSelection(
        selectionId: Int,
        entry: Long,
    ) = write(entry, selectionId, "invertSelection(${pixels[selectionId]})")

    override fun selectContents(
        selectionId: Int,
        layerId: Int,
        entry: Long,
    ) = write(entry, selectionId, "alpha(${pixels[layerId]})")

    override fun showSelection(selectionId: Int?) {
        shownSelection = selectionId
    }

    override fun selectPolygon(
        selectionId: Int,
        polygon: FloatArray,
        combine: SelectionCombine,
        entry: Long,
    ) = write(entry, selectionId, "$combine(${polygon.toList()}, ${pixels[selectionId]})")

    override fun copyMasked(
        sourceId: Int,
        destinationId: Int,
        selectionId: Int,
    ) {
        val source = pixels[sourceId]
        if (source == null) pixels.remove(destinationId) else pixels[destinationId] = "masked($source, ${pixels[selectionId]})"
    }

    var canvasSize = 256 to 256

    override fun remapSurface(
        surfaceId: Int,
        kind: Int,
        mesh: FloatArray,
        filter: Int,
        newWidth: Int,
        newHeight: Int,
        entry: Long,
    ) = write(entry, surfaceId, "remap($kind, ${newWidth}x$newHeight, ${pixels[surfaceId]})")

    override fun setCanvasSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        canvasSize = widthPx to heightPx
    }

    private var transform: Pair<Int, Int?>? = null
    private var mesh: String = ""

    override fun beginTransform(
        layerId: Int,
        selectionId: Int?,
        requestId: Int,
    ) {
        transform = layerId to selectionId
        mesh = "identity"
    }

    override fun setTransformMesh(
        mesh: FloatArray,
        filter: Int,
    ) {
        this.mesh = "${mesh.toList()}/$filter"
    }

    override fun endTransform(
        commit: Boolean,
        entry: Long,
    ) {
        val (layer, selection) = transform ?: return
        transform = null
        if (commit) write(entry, layer, "transform(${pixels[layer]}, $mesh, ${selection?.let { pixels[it] }})")
    }

    private var adjustment: Pair<Int, Int?>? = null
    private var adjustmentValues: String = ""

    override fun beginAdjustment(
        layerId: Int,
        selectionId: Int?,
        maskId: Int?,
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) {
        adjustment = layerId to selectionId
        setAdjustment(filter, values, table, amount)
    }

    override fun setAdjustment(
        filter: Int,
        values: FloatArray,
        table: FloatArray,
        amount: Float,
    ) {
        adjustmentValues = "$filter/${values.toList()}${if (table.isEmpty()) "" else "/${table.toList()}"}/$amount"
    }

    override fun endAdjustment(
        commit: Boolean,
        entry: Long,
    ) {
        val (layer, selection) = adjustment ?: return
        adjustment = null
        if (commit) write(entry, layer, "adjust(${pixels[layer]}, $adjustmentValues, ${selection?.let { pixels[it] }})")
    }

    private var fill: FillParameters? = null
    private var fillThreshold = 0.0

    override fun beginFill(parameters: FillParameters) {
        fill = parameters
        fillThreshold = parameters.threshold
    }

    override fun setFillThreshold(threshold: Double) {
        fillThreshold = threshold
    }

    override fun endFill(
        commit: Boolean,
        entry: Long,
    ) {
        val parameters = fill ?: return
        fill = null
        if (!commit) return
        val surface = parameters.surfaceId
        val within = parameters.selectionId?.let { ", in ${pixels[it]}" }.orEmpty()
        val source = pixels[parameters.sourceId]
        write(entry, surface, "${parameters.operation}(${parameters.target}, $fillThreshold, $source$within, ${pixels[surface]})")
    }

    override fun featherSelection(
        selectionId: Int,
        radius: Float,
        entry: Long,
    ) = write(entry, selectionId, "feather($radius, ${pixels[selectionId]})")

    override fun discardSurfaces(surfaceIds: IntArray) {
        surfaceIds.forEach { pixels.remove(it) }
        discarded += surfaceIds.toList()
    }

    override fun applyHistory(
        entry: Long,
        undo: Boolean,
    ) {
        val states = (if (undo) before[entry] else after[entry]) ?: return
        states.forEach { (surface, value) -> if (value == null) pixels.remove(surface) else pixels[surface] = value }
    }

    override fun releaseHistory(entries: LongArray) {
        released += entries.toList()
        entries.forEach {
            before.remove(it)
            after.remove(it)
        }
    }
}

private object TestNames : LayerNames {
    override fun layer(number: Int) = "Layer $number"

    override fun group(number: Int) = "Group $number"

    override fun copyOf(name: String) = "$name copy"

    override fun flattened() = "Flattened"

    override fun selection(number: Int) = "Selection $number"
}

class DocumentSessionTest {
    private fun session(
        engine: FakeEngine,
        maximumLayers: Int = Int.MAX_VALUE,
        steps: Int = 250,
    ): DocumentSession {
        val initial = DocumentState(256, 256).addRasterLayer("Layer 1").first
        return DocumentSession(engine, initial, TestNames, maximumLayers = { maximumLayers }, maximumSteps = steps)
    }

    private fun textContent(text: String) =
        TextLayerContent(JsonObject(mapOf("content" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(text))))))))

    @Test
    fun textModelsReachTheEngineWhenTheyChange() {
        val engine = FakeEngine()
        val session = session(engine)
        val id = session.addTextLayer(textContent("Hello"))
        assertEquals("Hello", session.state.raster(id)!!.name)
        assertEquals(listOf(id), engine.textLayers.map { it.first })
        session.setOpacity(id, 0.5f)
        assertEquals(1, engine.textLayers.size)
        session.setTextContent(id, textContent("Hello there"))
        assertTrue(engine.textLayers.last().second.contains("Hello there"))
        session.undo()
        session.undo()
        assertTrue(engine.textLayers.last().second.contains("\"Hello\""))
        assertEquals(3, engine.textLayers.size)
        session.rasterizeText(id)
        assertEquals(null, session.state.raster(id)!!.text)
        assertEquals(3, engine.textLayers.size)
        assertThrows<LayerOperationException> { session.setTextContent(id, textContent("x")) }
        session.undo()
        val above = session.newLayer()
        session.mergeDown(above)
        assertEquals(null, session.state.raster(id)!!.text)
    }

    @Test
    fun textLayersRefusePixelOperationsUntilRasterized() {
        val engine = FakeEngine()
        val session = session(engine)
        val id = session.addTextLayer(textContent("Ink"))
        val asked = mutableListOf<Int>()
        session.onContentLayerPixels = { asked += it }
        assertThrows<ContentLayerPixelsException> { session.beginStroke() }
        assertThrows<ContentLayerPixelsException> { session.clear(id) }
        assertEquals(listOf(id, id), asked)
        session.setTextContent(id, textContent("Inked"), final = false)
        assertEquals(1, session.history.undoCount)
        session.commitTextEdit()
        assertEquals(2, session.history.undoCount)
        session.rasterizeText(id)
        session.beginStroke()
    }

    @Test
    fun videoLayersScrubAsOneStepAndRefusePixelsUntilRasterized() {
        val engine = FakeEngine()
        val session = session(engine)
        val id = session.addVideoLayer("Clip", VideoLayerContent("clip.mp4", 0, 2_000_000))
        val asked = mutableListOf<Int>()
        session.onContentLayerPixels = { asked += it }
        assertThrows<ContentLayerPixelsException> { session.beginStroke() }
        assertThrows<ContentLayerPixelsException> { session.beginTransform(1) }
        assertEquals(listOf(id, id), asked)
        val steps = session.history.undoCount
        session.setVideoTime(id, 500_000, final = false)
        session.setVideoTime(id, 9_000_000, final = true)
        assertEquals(2_000_000, session.state.raster(id)!!.video!!.timeMicroseconds)
        assertEquals(steps + 1, session.history.undoCount)
        session.undo()
        assertEquals(0, session.state.raster(id)!!.video!!.timeMicroseconds)
        session.rasterizeVideo(id)
        assertEquals(null, session.state.raster(id)!!.video)
        session.beginStroke()
    }

    @Test
    fun videoFileNamesMustBePlainNames() {
        for (name in listOf("", "../x.mp4", "a/b.mp4", "a\b.mp4", ".hidden")) {
            assertThrows<IllegalArgumentException>(name) { VideoLayerContent(name, 0, 1) }
        }
    }

    @Test
    fun anEmptyNewTextBoxLeavesNothing() {
        val engine = FakeEngine()
        val session = session(engine)
        val before = session.state
        session.addTextLayer(textContent("a"), final = false)
        session.revertTextEdit()
        assertEquals(before, session.state)
        assertEquals(0, session.history.undoCount)
    }

    @Test
    fun everyChangeSendsTheTreeAndRecordsOneEntry() {
        val engine = FakeEngine()
        val session = session(engine)
        assertEquals(1, engine.treeUpdates)
        val id = session.newLayer()
        assertEquals("Layer 2", session.state.raster(id)!!.name)
        assertEquals(2, engine.treeUpdates)
        session.setBlend(id, BlendMode.Multiply)
        assertTrue(engine.tree.contains("multiply"))
        assertEquals(2, session.history.undoCount)
        assertEquals(HistoryLabel.LayerBlendMode, session.undo()!!.label)
        assertFalse(engine.tree.contains("multiply"))
        session.redo()
        assertTrue(engine.tree.contains("multiply"))
    }

    @Test
    fun aSliderDragRecordsOneEntryFromWhereItStarted() {
        val engine = FakeEngine()
        val session = session(engine)
        val id = session.state.activeLayerId!!
        session.setOpacity(id, 0.8f, final = false)
        session.setOpacity(id, 0.5f, final = false)
        assertEquals(0.5f, session.state.raster(id)!!.opacity)
        assertEquals(0, session.history.undoCount)
        session.setOpacity(id, 0.4f, final = true)
        assertEquals(1, session.history.undoCount)
        session.undo()
        assertEquals(1f, session.state.raster(id)!!.opacity)
    }

    @Test
    fun deletedLayersKeepTheirPixelsWhileHistoryNeedsThem() {
        val engine = FakeEngine()
        val session = session(engine, steps = 10)
        val id = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        session.delete(listOf(id))
        assertFalse(id in engine.discarded)
        session.undo()
        assertNotNull(engine.pixels[id])
        session.redo()
        repeat(10) { session.newLayer() }
        assertTrue(id in engine.discarded)
        assertTrue(engine.released.isNotEmpty())
    }

    @Test
    fun lockedLayersRefusePixelChanges() {
        val engine = FakeEngine()
        val session = session(engine)
        val id = session.state.activeLayerId!!
        session.setLocked(id, true)
        assertThrows<LayerOperationException> { session.clear() }
        assertThrows<LayerOperationException> { session.beginStroke() }
        assertFalse(session.canPaint)
    }

    @Test
    fun theLayerLimitStopsNewLayers() {
        val session = session(FakeEngine(), maximumLayers = 2)
        session.newLayer()
        assertTrue(session.layerLimitReached)
        assertThrows<LayerOperationException> { session.newLayer() }
        assertThrows<LayerOperationException> { session.duplicate(session.state.activeLayerId!!) }
    }

    @Test
    fun maskSelectionRoutesPixelOperationsToTheMask() {
        val engine = FakeEngine()
        val session = session(engine)
        val layerId = session.state.activeLayerId!!
        val maskId = session.addMask(layerId)
        assertTrue(session.maskSelected)
        assertEquals(maskId, session.activeSurfaceId)
        session.fill(RgbaColor.BLACK)
        assertNotNull(engine.pixels[maskId])
        session.select(layerId)
        assertEquals(layerId, session.activeSurfaceId)
    }

    @Test
    fun selectionOperationsAreUndoableAndKeepTheirSurfaces() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        assertEquals(null, session.selectionId)
        session.selectAll()
        val all = session.selectionId!!
        assertEquals(all, engine.shownSelection)
        assertEquals("fill(${RgbaColor.WHITE})", engine.pixels[all])
        session.invertSelection()
        assertEquals("invertSelection(fill(${RgbaColor.WHITE}))", engine.pixels[all])
        session.selectContents(layer)
        val contents = session.selectionId!!
        assertTrue(contents != all)
        val saved = session.saveSelection()
        assertEquals(listOf("Selection 1"), session.state.savedSelections.map { it.name })
        session.deselect()
        assertEquals(null, engine.shownSelection)
        session.loadSelection(saved)
        assertEquals(engine.pixels[saved], engine.pixels[session.selectionId!!])
        repeat(3) { session.undo() }
        assertEquals(contents, session.selectionId)
        assertEquals(contents, engine.shownSelection)
        session.undo()
        session.undo()
        assertEquals(all, session.selectionId)
        assertEquals("fill(${RgbaColor.WHITE})", engine.pixels[all])
        assertTrue(all !in engine.discarded)
        assertEquals(HistoryLabel.SelectAll, session.undo()!!.label)
        assertEquals(null, engine.shownSelection)
        assertThrows<LayerOperationException> { session.invertSelection() }
    }

    @Test
    fun shapesFeatherAndPaintingTheSelection() {
        val engine = FakeEngine()
        val session = session(engine)
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        assertThrows<LayerOperationException> { session.selectShape(square, SelectionCombine.Subtract) }
        session.selectShape(square, SelectionCombine.Add)
        val first = session.selectionId!!
        assertTrue(engine.pixels[first]!!.startsWith("Add("))
        session.selectShape(square, SelectionCombine.Subtract)
        assertEquals(first, session.selectionId)
        assertTrue(engine.pixels[first]!!.startsWith("Subtract("))
        session.featherSelection(4f)
        assertTrue(engine.pixels[first]!!.startsWith("feather(4.0"))
        session.selectShape(square, SelectionCombine.Replace)
        assertTrue(session.selectionId != first)
        session.deselect()
        val canceled = session.beginSelectionStroke()
        assertTrue(canceled.newSurface)
        assertEquals(canceled.selectionId, engine.shownSelection)
        session.cancelSelectionStroke(canceled)
        assertEquals(null, engine.shownSelection)
        val stroke = session.beginSelectionStroke()
        engine.write(stroke.entry, stroke.selectionId, "painted")
        session.recordSelectionStroke(stroke)
        assertEquals(stroke.selectionId, session.selectionId)
        assertEquals(HistoryLabel.PaintSelection, session.undo()!!.label)
        assertEquals(null, session.selectionId)
        assertEquals(null, engine.pixels[stroke.selectionId])
        session.redo()
        assertEquals("painted", engine.pixels[stroke.selectionId])
    }

    @Test
    fun fillsAndAutomaticSelection() {
        val engine = FakeEngine()
        val session = session(engine)
        session.beginFill(5.0, 5.0, 0.1, RgbaColor.BLACK)
        session.setFillThreshold(0.3)
        session.endFill(commit = true)
        val layer = session.state.activeLayerId!!
        assertTrue(engine.pixels[layer]!!.startsWith("Color(Layer, 0.3"))
        val last = session.history.entries().last()
        assertEquals(HistoryLabel.FloodFill, last.label)
        val before = engine.pixels[layer]
        session.beginFill(5.0, 5.0, 0.1, RgbaColor.WHITE)
        session.endFill(commit = false)
        assertEquals(before, engine.pixels[layer])
        session.beginAutomaticSelection(1.0, 1.0, 0.2, SelectionCombine.Replace)
        session.endFill(commit = true)
        val selection = session.selectionId!!
        assertTrue(engine.pixels[selection]!!.startsWith("Color(Selection"))
        session.clear()
        assertTrue(engine.pixels[layer]!!.startsWith("Clear(Layer") && engine.pixels[layer]!!.contains("in Color(Selection"))
        session.undo()
        session.undo()
        assertEquals(null, session.selectionId)
        assertEquals(before, engine.pixels[layer])
        session.beginAutomaticSelection(1.0, 1.0, 0.2, SelectionCombine.Replace)
        session.endFill(commit = false)
        assertEquals(null, session.selectionId)
        assertEquals(null, engine.shownSelection)
    }

    @Test
    fun adjustmentIsOneEntry() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        val before = engine.pixels[layer]
        session.beginAdjustment(0, floatArrayOf(0.5f), floatArrayOf(), 1f, HistoryLabel.HueSaturationBrightness)
        assertTrue(session.adjustmentActive)
        session.endAdjustment(commit = false)
        assertEquals(before, engine.pixels[layer])
        session.beginAdjustment(0, floatArrayOf(0.1f), floatArrayOf(), 1f, HistoryLabel.HueSaturationBrightness)
        session.setAdjustment(0, floatArrayOf(0.2f, -0.5f), floatArrayOf(), 1f)
        session.endAdjustment(commit = true)
        assertEquals("adjust($before, 0/[0.2, -0.5]/1.0, null)", engine.pixels[layer])
        assertEquals(HistoryLabel.HueSaturationBrightness, session.undo()!!.label)
        assertEquals(before, engine.pixels[layer])
        session.redo()
        session.beginAdjustment(0, floatArrayOf(0.3f), floatArrayOf(), 1f, HistoryLabel.HueSaturationBrightness)
        session.beginTransform(1)
        assertTrue(!session.adjustmentActive)
        session.endTransform(commit = false)
        assertTrue(engine.pixels[layer]!!.startsWith("adjust(adjust("))
    }

    @Test
    fun pencilMaskEntriesStayOutOfTheHistory() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        val historySize = session.history.undoCount
        session.beginAdjustment(0, floatArrayOf(0.5f), floatArrayOf(), 1f, HistoryLabel.HueSaturationBrightness, pencil = true)
        assertTrue(session.adjustmentPencil)
        val stroke = session.beginAdjustmentMaskEntry()
        engine.write(stroke, ADJUSTMENT_MASK_SURFACE_ID, "painted")
        session.applyAdjustmentMaskEntry(stroke, undo = true)
        assertEquals(null, engine.pixels[ADJUSTMENT_MASK_SURFACE_ID])
        session.applyAdjustmentMaskEntry(stroke, undo = false)
        assertEquals("painted", engine.pixels[ADJUSTMENT_MASK_SURFACE_ID])
        val clear = session.clearAdjustmentMask()
        assertTrue(clear != stroke)
        session.endAdjustment(commit = true)
        assertEquals(historySize + 1, session.history.undoCount)
        assertTrue(engine.released.containsAll(listOf(stroke, clear)))
        assertTrue(ADJUSTMENT_MASK_SURFACE_ID in engine.discarded)
        assertTrue(engine.pixels[layer]!!.startsWith("adjust("))
        session.beginAdjustment(0, floatArrayOf(0.5f), floatArrayOf(), 1f, HistoryLabel.HueSaturationBrightness)
        assertThrows<LayerOperationException> { session.beginAdjustmentMaskEntry() }
        session.endAdjustment(commit = false)
    }

    @Test
    fun transformIsOneEntry() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        val before = engine.pixels[layer]
        session.beginTransform(1)
        session.setTransformMesh(floatArrayOf(1f), 1)
        session.endTransform(commit = false)
        assertEquals(before, engine.pixels[layer])
        session.selectAll()
        session.beginTransform(2)
        session.setTransformMesh(floatArrayOf(2f), 2)
        session.endTransform(commit = true)
        assertTrue(engine.pixels[layer]!!.startsWith("transform("))
        assertEquals(null, session.selectionId)
        assertEquals(HistoryLabel.Transform, session.undo()!!.label)
        assertEquals(before, engine.pixels[layer])
        assertTrue(session.selectionId != null)
    }

    @Test
    fun mergeDownCommitsAnOpenTransformFirst() {
        val engine = FakeEngine()
        val session = session(engine)
        val lower = session.state.activeLayerId!!
        val upper = session.newLayer()
        session.fill(RgbaColor.BLACK)
        session.beginTransform(1)
        session.setTransformMesh(floatArrayOf(1f), 1)
        session.mergeDown(upper)
        assertFalse(session.transformActive)
        assertTrue(engine.pixels[lower]!!.contains("transform("))
        assertEquals(HistoryLabel.MergeDown, session.undo()!!.label)
        assertEquals(HistoryLabel.Transform, session.undo()!!.label)
    }

    @Test
    fun canvasOperationsAreOneEntry() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        session.addMask(layer)
        session.selectAll()
        val before = engine.pixels.toMap()
        session.canvasOperation(CanvasOperation.Rotate(1))
        assertEquals(256 to 256, engine.canvasSize)
        session.canvasOperation(CanvasOperation.Crop(10, 20, 100, 50))
        assertEquals(100 to 50, engine.canvasSize)
        assertEquals(100, session.state.widthPx)
        assertTrue(engine.pixels[layer]!!.startsWith("remap(0, 100x50"))
        val maskId =
            session.state
                .raster(layer)!!
                .mask!!
                .id
        assertTrue(engine.pixels[maskId]!!.startsWith("remap(1,"))
        assertTrue(engine.pixels[session.selectionId!!]!!.startsWith("remap(2,"))
        assertEquals(HistoryLabel.CropCanvas, session.undo()!!.label)
        assertEquals(256 to 256, engine.canvasSize)
        session.undo()
        assertEquals(before, engine.pixels.filterKeys { it in before })
        session.redo()
        session.redo()
        assertEquals(100 to 50, engine.canvasSize)
        assertThrows<LayerOperationException> { session.canvasOperation(CanvasOperation.Resize(0, 10)) }
    }

    @Test
    fun clipboard() {
        val engine = FakeEngine()
        val session = session(engine)
        val layer = session.state.activeLayerId!!
        session.fill(RgbaColor.BLACK)
        assertThrows<LayerOperationException> { session.paste() }
        session.copy()
        assertEquals(engine.pixels[layer], engine.pixels[CLIPBOARD_SURFACE_ID])
        val pasted = session.paste()
        assertEquals(engine.pixels[layer], engine.pixels[pasted])
        session.selectAll()
        val copy = session.copyToNewLayer()
        assertTrue(engine.pixels[copy]!!.startsWith("masked("))
        session.select(layer)
        session.cut()
        assertTrue(engine.pixels[CLIPBOARD_SURFACE_ID]!!.startsWith("masked("))
        assertTrue(engine.pixels[layer]!!.startsWith("Clear("))
        assertEquals(HistoryLabel.Cut, session.undo()!!.label)
        assertTrue(CLIPBOARD_SURFACE_ID !in engine.discarded)
        session.copy(merged = true)
        assertTrue(engine.pixels[CLIPBOARD_SURFACE_ID]!!.startsWith("masked(compose("))
    }

    @Test
    fun randomSequencesUndoAndRedoExactly() {
        val random = Random(20261014)
        repeat(200) {
            val engine = FakeEngine()
            val session = session(engine)

            data class Snapshot(
                val state: DocumentState,
                val pixels: Map<Int, String>,
            )

            fun snapshot() =
                Snapshot(
                    session.state.copy(activeLayerId = null),
                    engine.pixels.filterKeys {
                        it in
                            session.state.pixelSurfaceIds()
                    },
                )
            val snapshots = mutableListOf(snapshot())
            var strokeNumber = 0
            repeat(random.nextInt(5, 30)) {
                val layers = session.state.rasterLayers()
                val layer = layers.randomOrNull(random)
                val before = session.history.undoCount
                runCatching {
                    when (random.nextInt(18)) {
                        0 -> session.newLayer()
                        1 -> layer?.let { session.duplicate(it.id) }
                        2 -> if (layers.size > 1) session.delete(listOf(layer!!.id))
                        3 -> layer?.let { session.select(it.id) }.also { session.fill(RgbaColor(random.nextFloat(), 0f, 0f)) }
                        4 -> session.clear()
                        5 -> session.invert()
                        6 -> layer?.takeIf { session.state.nodeBelow(it.id) is RasterLayer }?.let { session.mergeDown(it.id) }
                        7 -> layer?.let { if (it.mask == null) session.addMask(it.id) else session.applyMask(it.id) }
                        8 -> if (layers.size > 1) session.group(layers.take(2).map { it.id })
                        9 ->
                            session.state
                                .allNodes()
                                .filterIsInstance<LayerGroup>()
                                .firstOrNull()
                                ?.let { session.flattenGroup(it.id) }
                        10 -> layer?.let { session.setHidden(it.id, !it.hidden) }
                        11 -> {
                            val entry = session.beginStroke()
                            engine.write(entry, session.activeSurfaceId!!, "stroke${strokeNumber++}")
                            session.recordStroke(entry)
                        }
                        12 -> if (random.nextInt(4) == 0) session.flattenDocument()
                        13 ->
                            when (random.nextInt(3)) {
                                0 -> session.selectAll()
                                1 -> session.selectContents()
                                else -> session.deselect()
                            }
                        16 ->
                            if (random.nextBoolean()) {
                                session.beginFill(1.0, 1.0, random.nextDouble(), RgbaColor(random.nextFloat(), 0f, 0f))
                                session.endFill(commit = random.nextInt(4) != 0)
                            } else {
                                session.beginAutomaticSelection(1.0, 1.0, random.nextDouble(), SelectionCombine.entries.random(random))
                                session.endFill(commit = random.nextInt(4) != 0)
                            }
                        15 ->
                            when (random.nextInt(3)) {
                                0 -> session.selectShape(floatArrayOf(0f, 0f, 5f, 0f, 5f, 5f), SelectionCombine.entries.random(random))
                                1 -> session.featherSelection(random.nextFloat() * 10)
                                else -> {
                                    val stroke = session.beginSelectionStroke()
                                    engine.write(stroke.entry, stroke.selectionId, "selectionStroke${strokeNumber++}")
                                    session.recordSelectionStroke(stroke)
                                }
                            }
                        14 ->
                            when (random.nextInt(3)) {
                                0 -> session.invertSelection()
                                1 -> session.saveSelection()
                                else -> {
                                    val saved = session.state.savedSelections.randomOrNull(random)
                                    if (saved != null) session.loadSelection(saved.id)
                                }
                            }
                        else -> layer?.let { session.setOpacity(it.id, random.nextFloat()) }
                    }
                }
                if (session.history.undoCount > before) snapshots += snapshot()
            }
            for (index in snapshots.size - 2 downTo 0) {
                session.undo()
                assertEquals(snapshots[index], snapshot(), "undo to step $index")
            }
            for (index in 1 until snapshots.size) {
                session.redo()
                assertEquals(snapshots[index], snapshot(), "redo to step $index")
            }
        }
    }
}
