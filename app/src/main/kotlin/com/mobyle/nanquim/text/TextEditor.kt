package com.mobyle.nanquim.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.mobyle.nanquim.document.layer.RgbaColor
import com.mobyle.nanquim.document.layer.TextLayerContent
import com.mobyle.nanquim.document.layer.TextModelEditing
import com.mobyle.nanquim.document.session.DocumentSession
import com.mobyle.nanquim.engine.Engine
import com.mobyle.nanquim.engine.TextGeometry
import kotlin.coroutines.resume

class TextEditor(
    private val engine: Engine,
    private val documentSession: () -> DocumentSession?,
    private val dpi: () -> Double,
) {
    data class Editing(
        val layerId: Int?,
        val model: JsonObject,
        val selectionStart: Int,
        val selectionEnd: Int,
        val geometry: TextGeometry? = null,
        val createdHere: Boolean = false,
    ) {
        val text: String get() = TextModelEditing.plainText(model)
        val selectionMin: Int get() = minOf(selectionStart, selectionEnd)
        val selectionMax: Int get() = maxOf(selectionStart, selectionEnd)
    }

    var editing: Editing? by mutableStateOf(null)
        private set

    val recentFontIds = mutableStateListOf<String>()

    fun place(
        x: Double,
        y: Double,
        color: RgbaColor,
        fontId: String = FontLibrary.DEFAULT_FONT_ID,
        sizePt: Double = DEFAULT_SIZE_PT,
    ) {
        finish()
        editing = Editing(null, TextModelEditing.newModel("", fontId, sizePt, color, x, y), 0, 0)
        refreshGeometry()
    }

    fun edit(
        layerId: Int,
        caret: Int? = null,
    ) {
        if (editing?.layerId == layerId) {
            caret?.let { setSelection(it, it) }
            return
        }
        finish()
        val model =
            documentSession()
                ?.state
                ?.raster(layerId)
                ?.text
                ?.model ?: return
        val end = caret ?: TextModelEditing.plainText(model).length
        editing = Editing(layerId, model, end, end)
        refreshGeometry()
    }

    fun insert(text: String) {
        val current = editing ?: return
        replace(current.selectionMin, current.selectionMax, text)
    }

    fun replace(
        start: Int,
        end: Int,
        text: String,
    ) {
        val current = editing ?: return
        val model = TextModelEditing.replace(current.model, start, end, text)
        val caret = minOf(start, end) + text.length
        update(current.copy(model = model, selectionStart = caret, selectionEnd = caret))
    }

    fun setTextAndSelection(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val current = editing ?: return
        val old = current.text
        if (old != text) {
            var prefix = 0
            while (prefix < old.length && prefix < text.length && old[prefix] == text[prefix]) prefix++
            var suffix = 0
            while (suffix < old.length - prefix && suffix < text.length - prefix &&
                old[old.length - 1 - suffix] == text[text.length - 1 - suffix]
            ) {
                suffix++
            }
            val model = TextModelEditing.replace(current.model, prefix, old.length - suffix, text.substring(prefix, text.length - suffix))
            update(current.copy(model = model, selectionStart = selectionStart, selectionEnd = selectionEnd))
        } else {
            setSelection(selectionStart, selectionEnd)
        }
    }

    fun deleteBackward() {
        val current = editing ?: return
        if (current.selectionStart != current.selectionEnd) {
            replace(current.selectionMin, current.selectionMax, "")
        } else if (current.selectionStart > 0) {
            replace(current.text.offsetByCodePoints(current.selectionStart, -1), current.selectionStart, "")
        }
    }

    fun deleteForward() {
        val current = editing ?: return
        if (current.selectionStart != current.selectionEnd) {
            replace(current.selectionMin, current.selectionMax, "")
        } else if (current.selectionStart < current.text.length) {
            replace(current.selectionStart, current.text.offsetByCodePoints(current.selectionStart, 1), "")
        }
    }

    fun setSelection(
        start: Int,
        end: Int,
    ) {
        val current = editing ?: return
        val length = current.text.length
        editing = current.copy(selectionStart = start.coerceIn(0, length), selectionEnd = end.coerceIn(0, length))
    }

    fun moveCaret(
        delta: Int,
        extend: Boolean,
    ) {
        val current = editing ?: return
        val text = current.text
        val target =
            runCatching { text.offsetByCodePoints(current.selectionEnd, delta) }
                .getOrElse { if (delta < 0) 0 else text.length }
        setSelection(if (extend) current.selectionStart else target, target)
    }

    fun applyStyle(changes: JsonObject) {
        val current = editing ?: return
        (changes["fontId"] as? JsonPrimitive)?.contentOrNull?.let { id ->
            recentFontIds.remove(id)
            recentFontIds.add(0, id)
            if (recentFontIds.size > MAXIMUM_RECENT_FONTS) recentFontIds.removeAt(recentFontIds.lastIndex)
        }
        val whole = current.selectionStart == current.selectionEnd
        val (start, end) = if (whole) 0 to current.text.length else current.selectionMin to current.selectionMax
        update(current.copy(model = TextModelEditing.applyStyle(current.model, start, end, changes)))
    }

    fun currentStyle(): JsonObject? {
        val current = editing ?: return null
        return TextModelEditing.styleAt(current.model, current.selectionMin)
    }

    fun setParagraph(changes: JsonObject) {
        val current = editing ?: return
        update(current.copy(model = TextModelEditing.setParagraph(current.model, changes)))
    }

    fun setBox(changes: JsonObject) {
        val current = editing ?: return
        update(current.copy(model = TextModelEditing.setBox(current.model, changes)))
    }

    fun setTransform(transform: List<Double>) {
        val current = editing ?: return
        update(current.copy(model = TextModelEditing.setTransform(current.model, transform)))
    }

    fun finish() {
        val current = editing ?: return
        editing = null
        val session = documentSession() ?: return
        val layerId = current.layerId ?: return
        when {
            current.text.isNotEmpty() -> session.commitTextEdit()
            current.createdHere -> session.revertTextEdit()
            else -> {
                session.commitTextEdit()
                session.delete(listOf(layerId))
            }
        }
    }

    fun onDocumentChanged() {
        val current = editing ?: return
        val layerId = current.layerId ?: return
        val stored =
            documentSession()
                ?.state
                ?.raster(layerId)
                ?.text
                ?.model
        if (stored == null) {
            editing = null
        } else if (stored != current.model) {
            val length = TextModelEditing.plainText(stored).length
            editing =
                current.copy(
                    model = stored,
                    selectionStart = minOf(current.selectionStart, length),
                    selectionEnd = minOf(current.selectionEnd, length),
                )
            refreshGeometry()
        }
    }

    private fun update(next: Editing) {
        val session = documentSession() ?: return
        var layerId = next.layerId
        val content = TextLayerContent(next.model)
        if (layerId == null) {
            if (next.text.isEmpty()) {
                editing = next
                refreshGeometry()
                return
            }
            layerId = session.addTextLayer(content, final = false)
            editing = next.copy(layerId = layerId, createdHere = true)
        } else {
            session.setTextContent(layerId, content, final = false)
            editing = next
        }
        refreshGeometry()
    }

    suspend fun geometryOf(model: JsonObject): TextGeometry =
        suspendCancellableCoroutine { continuation ->
            engine.requestTextGeometry(model.toString(), dpi()) { continuation.resume(it) }
        }

    private fun refreshGeometry() {
        val current = editing ?: return
        val model = current.model
        engine.requestTextGeometry(model.toString(), dpi()) { geometry ->
            val now = editing
            if (now != null && now.model == model) editing = now.copy(geometry = geometry)
        }
    }

    companion object {
        const val DEFAULT_SIZE_PT = 48.0
        private const val MAXIMUM_RECENT_FONTS = 8
    }
}
