package com.mobyle.nanquim.document.history

import com.mobyle.nanquim.document.layer.DocumentState

enum class HistoryLabel {
    BrushStroke,
    EditText,
    Animation,
    VideoFrame,
    Private,
    RasterizeVideo,
    RasterizeText,
    EraseStroke,
    NewLayer,
    DuplicateLayer,
    DeleteLayer,
    RenameLayer,
    LayerOpacity,
    LayerBlendMode,
    LayerVisibility,
    LockLayer,
    AlphaLock,
    ClippingMask,
    AddMask,
    DeleteMask,
    ApplyMask,
    MaskSettings,
    MoveLayer,
    GroupLayers,
    Ungroup,
    MergeDown,
    MergeLayers,
    CombineDown,
    FlattenGroup,
    Flatten,
    ClearLayer,
    FillLayer,
    InvertLayer,
    ReferenceLayer,
    BackgroundColor,
    SelectAll,
    Deselect,
    InvertSelection,
    SelectContents,
    SaveSelection,
    LoadSelection,
    DeleteSavedSelection,
    SelectShape,
    FeatherSelection,
    PaintSelection,
    FloodFill,
    AutomaticSelection,
    Cut,
    Paste,
    CopyToNewLayer,
    Transform,
    CropCanvas,
    ResizeCanvas,
    RotateCanvas,
    FlipCanvas,
    SmudgeStroke,

    HueSaturationBrightness,
    ColorBalance,
    Curves,
    GradientMap,
    GaussianBlur,
    MotionBlur,
    PerspectiveBlur,
    Sharpen,
    Noise,
    Bloom,
    Glitch,
    Halftone,
    ChromaticAberration,
    Liquify,

    DrawingAssist,
}

data class HistoryEntry(
    val label: HistoryLabel,
    val before: DocumentState? = null,
    val after: DocumentState? = null,
    val pixelEntryIds: List<Long> = emptyList(),
) {
    init {
        require((before == null) == (after == null)) { "Structure states come in pairs" }
        require(before != null || pixelEntryIds.isNotEmpty()) { "An entry must change something" }
    }
}

class UndoHistory(
    maximumSteps: Int = DEFAULT_MAXIMUM_STEPS,
    maximumPixelBytes: Long = DEFAULT_MAXIMUM_PIXEL_BYTES,
    private val onDropped: (List<HistoryEntry>) -> Unit = {},
) {
    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()
    private val pixelBytes = mutableMapOf<Long, Long>()

    var maximumSteps: Int = maximumSteps
        set(value) {
            require(value in MINIMUM_STEPS..MAXIMUM_STEPS) { "Undo steps must be in $MINIMUM_STEPS..$MAXIMUM_STEPS" }
            field = value
            trim()
        }

    var maximumPixelBytes: Long = maximumPixelBytes
        set(value) {
            require(value > 0) { "The undo size limit must be positive" }
            field = value
            trim()
        }

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val nextUndo: HistoryEntry? get() = undoStack.lastOrNull()
    val nextRedo: HistoryEntry? get() = redoStack.lastOrNull()

    fun entries(): List<HistoryEntry> = undoStack.toList() + redoStack.toList()

    val totalPixelBytes: Long get() = pixelBytes.values.sum()

    fun record(entry: HistoryEntry) {
        if (redoStack.isNotEmpty()) {
            val discarded = redoStack.toList()
            redoStack.clear()
            forget(discarded)
        }
        undoStack.addLast(entry)
        trim()
    }

    fun setPixelBytes(
        pixelEntryId: Long,
        bytes: Long,
    ) {
        val known = (undoStack + redoStack).any { pixelEntryId in it.pixelEntryIds }
        if (!known) return
        pixelBytes[pixelEntryId] = bytes
        trim()
    }

    fun undo(): HistoryEntry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(entry)
        return entry
    }

    fun redo(): HistoryEntry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(entry)
        return entry
    }

    fun clear() {
        val all = undoStack.toList() + redoStack.toList()
        undoStack.clear()
        redoStack.clear()
        forget(all)
    }

    private fun trim() {
        val dropped = mutableListOf<HistoryEntry>()
        while (undoStack.size > maximumSteps) dropped += undoStack.removeFirst()
        while (undoStack.size > 1 && totalPixelBytes - dropped.sumOf(::bytesOf) > maximumPixelBytes) {
            dropped += undoStack.removeFirst()
        }
        forget(dropped)
    }

    private fun bytesOf(entry: HistoryEntry): Long = entry.pixelEntryIds.sumOf { pixelBytes[it] ?: 0L }

    private fun forget(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        entries.forEach { entry -> entry.pixelEntryIds.forEach(pixelBytes::remove) }
        onDropped(entries)
    }

    companion object {
        const val DEFAULT_MAXIMUM_STEPS = 250
        const val MINIMUM_STEPS = 10
        const val MAXIMUM_STEPS = 1000
        const val DEFAULT_MAXIMUM_PIXEL_BYTES = 2L * 1024 * 1024 * 1024
    }
}
