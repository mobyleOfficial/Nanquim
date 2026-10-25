package com.mobyle.nanquim.brush

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import com.mobyle.nanquim.brush.library.BrushLibrary
import com.mobyle.nanquim.brush.library.BrushTool
import com.mobyle.nanquim.canvas.SrgbColor
import com.mobyle.nanquim.document.brush.BrushDocument
import com.mobyle.nanquim.document.brush.BrushPackage
import com.mobyle.nanquim.document.brush.BrushResetPoints
import com.mobyle.nanquim.engine.BrushPad
import com.mobyle.nanquim.engine.StrokeSampleBuffer
import java.util.concurrent.atomic.AtomicReference

enum class BrushEditorSection {
    StrokePath,
    Stabilization,
    Taper,
    Shape,
    Grain,
    Rendering,
    WetMix,
    ColorDynamics,
    Dynamics,
    Stylus,
    Properties,
    About,

    Smudge,
    Particles,
    DualBrush,
}

enum class TextureSlot(
    val field: String,
    val defaultFileName: String,
) {
    Shape("shape.texture", "shape.png"),
    Grain("grain.texture", "grain.png"),
}

class BrushEditorSession(
    val brushId: String,
    val original: BrushPackage,
    private val library: BrushLibrary,
    private val brushes: ActiveBrushes,
    private val scope: CoroutineScope,
    padSizePx: Float,
    private val padOpacity: Float,
    private val pressureCurve: List<Pair<Float, Float>>?,
    private val primaryColor: SrgbColor = SrgbColor.BLACK,
    private val secondaryColor: SrgbColor = SrgbColor.WHITE,
) {
    var working: BrushPackage by mutableStateOf(original)
        private set

    var section: BrushEditorSection by mutableStateOf(BrushEditorSection.StrokePath)

    var padDark: Boolean by mutableStateOf(false)

    var padUsesColor: Boolean by mutableStateOf(true)

    var padSizePx: Float by mutableFloatStateOf(padSizePx.coerceIn(1f, MAXIMUM_PAD_SIZE_PX))
        private set

    fun updatePadSize(sizePx: Float) {
        padSizePx = sizePx.coerceIn(1f, MAXIMUM_PAD_SIZE_PX)
    }

    var padImage: ImageBitmap? by mutableStateOf(null)
        private set

    var lastPressure: Float? by mutableStateOf(null)

    var brushError: String? by mutableStateOf(null)
        private set

    val changed: Boolean get() = working != original

    val libraryGroups get() = library.groups

    private val padThread = Dispatchers.Default.limitedParallelism(1)
    private val pad = BrushPad(PAD_WIDTH, PAD_HEIGHT)
    private val bitmaps = Array(2) { Bitmap.createBitmap(PAD_WIDTH, PAD_HEIGHT, Bitmap.Config.ARGB_8888) }
    private var backBitmap = 0
    private val pendingBrush = AtomicReference<BrushPackage?>(original)
    private var closed = false

    init {
        scope.launch(padThread) {
            pad.setPressureCurve(pressureCurve)
            applyPendingBrush()
            pad.clear()
            publish()
        }
    }

    fun setValue(
        path: String,
        value: JsonElement,
    ) = update(linkedTaper(working.document.with(path, value), path, value))

    fun removeValue(path: String) = update(working.document.without(path))

    fun update(document: BrushDocument) {
        working = working.withDocument(document.with("id", brushId))
        pendingBrush.set(working)
        scope.launch(padThread) { applyPendingBrush() }
    }

    private val previousTextures = mutableMapOf<TextureSlot, ByteArray?>()

    var autoRepeatProgress: Float? by mutableStateOf(null)
        private set

    fun textureBytes(slot: TextureSlot): ByteArray? = working.document.string(slot.field)?.let { working.files[it] }

    fun canUndoTexture(slot: TextureSlot): Boolean = slot in previousTextures

    fun undoTexture(slot: TextureSlot): Boolean {
        if (slot !in previousTextures) return false
        val previous = previousTextures.getValue(slot)
        setTexture(slot, previous)
        previousTextures.remove(slot)
        return true
    }

    suspend fun autoRepeat(
        slot: TextureSlot,
        settings: BrushPad.Companion.AutoRepeatSettings,
    ): Boolean {
        val png = textureBytes(slot) ?: return false
        autoRepeatProgress = 0f
        try {
            val image =
                withContext(Dispatchers.Default) {
                    BrushPad.autoRepeat(png, settings) { fraction ->
                        autoRepeatProgress = fraction
                        isActive
                    }
                } ?: return false
            setTexture(slot, TextureImport.grayPng(image.side, image.pixels))
            return true
        } finally {
            autoRepeatProgress = null
        }
    }

    fun setFile(
        name: String,
        bytes: ByteArray?,
    ) {
        working = working.withFile(name, bytes)
        pendingBrush.set(working)
    }

    fun createResetPoint(
        name: String,
        createdAt: String,
    ) = update(BrushResetPoints.create(working.document, name, createdAt))

    fun restoreResetPoint(index: Int): Boolean {
        val restored = BrushResetPoints.restore(working.document, index) ?: return false
        update(restored)
        return true
    }

    suspend fun resetAll(): Boolean {
        BrushResetPoints.restoreLatest(working.document)?.let {
            update(it)
            return true
        }
        val original = library.builtInOriginal(brushId) ?: return false
        working = original
        update(original.document.with("id", brushId))
        return true
    }

    fun setTexture(
        slot: TextureSlot,
        png: ByteArray?,
    ) {
        previousTextures[slot] = textureBytes(slot)
        val current = working.document.string(slot.field)
        var updated = working
        current?.let { updated = updated.withFile(it, null) }
        updated =
            if (png == null) {
                updated.withDocument(updated.document.with(slot.field, null as String?))
            } else {
                updated.withFile(slot.defaultFileName, png).let {
                    it.withDocument(it.document.with(slot.field, slot.defaultFileName))
                }
            }
        working = updated
        pendingBrush.set(working)
        scope.launch(padThread) { applyPendingBrush() }
    }

    fun padBeginStroke(samples: StrokeSampleBuffer) {
        val ink = if (padDark) 1f else 0f
        val color = if (padUsesColor) primaryColor else SrgbColor(ink, ink, ink)
        val secondary = if (padUsesColor) secondaryColor else SrgbColor(ink, ink, ink)
        val size = padSizePx
        scope.launch(padThread) {
            applyPendingBrush()
            if (pad.beginStroke(
                    size,
                    padOpacity,
                    color.red,
                    color.green,
                    color.blue,
                    secondaryRed = secondary.red,
                    secondaryGreen = secondary.green,
                    secondaryBlue = secondary.blue,
                )
            ) {
                pad.addSamples(samples)
                publish()
            }
        }
    }

    fun padAddSamples(samples: StrokeSampleBuffer) {
        scope.launch(padThread) {
            if (!pad.addSamples(samples).isEmpty) publish()
        }
    }

    fun padEndStroke() {
        scope.launch(padThread) {
            pad.endStroke()
            publish()
        }
    }

    fun clearPad() {
        scope.launch(padThread) {
            pad.clear()
            publish()
        }
    }

    suspend fun save() {
        if (changed) {
            library.save(working)
            brushes.reload(brushId).join()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.launch(padThread) { pad.close() }
    }

    private fun applyPendingBrush() {
        val brushPackage = pendingBrush.getAndSet(null) ?: return
        val error =
            pad.setBrush(brushPackage.document.toJsonText(), brushPackage.shapeTextureBytes, brushPackage.grainTextureBytes)
        if (error != null) Log.w(TAG, "Brush editor: $error")
        scope.launch { brushError = error }
    }

    private suspend fun publish() {
        val bitmap = bitmaps[backBitmap]
        pad.pixels.rewind()
        bitmap.copyPixelsFromBuffer(pad.pixels)
        backBitmap = 1 - backBitmap
        withContext(Dispatchers.Main) { padImage = bitmap.asImageBitmap() }
    }

    companion object {
        private const val TAG = "BrushEditor"

        const val PAD_WIDTH = 1024
        const val PAD_HEIGHT = 512

        const val MAXIMUM_PAD_SIZE_PX = 160f

        fun linkedTaper(
            document: BrushDocument,
            path: String,
            value: JsonElement,
        ): BrushDocument {
            val match = TAPER_LENGTH.matchEntire(path) ?: return document
            val (input, end) = match.destructured
            if (document.boolean("taper.$input.linkStartEnd") != true) return document
            val other = if (end == "start") "end" else "start"
            return document.with("taper.$input.$other", value)
        }

        private val TAPER_LENGTH = Regex("^taper\\.(stylus|finger)\\.(start|end)$")

        fun padSizeFor(
            brushes: ActiveBrushes,
            tool: BrushTool,
            brushId: String,
            document: BrushDocument,
        ): Float {
            val active = brushes[tool]?.takeIf { it.brushId == brushId }
            val size = active?.sizePx ?: BrushLimits.of(document).sizeAt(BrushLibrary.defaultSliders(document).sizeSlider)
            return size.coerceIn(1f, MAXIMUM_PAD_SIZE_PX)
        }
    }
}
