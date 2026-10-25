package com.mobyle.nanquim.document.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object TextModelEditing {
    private data class Run(
        val text: String,
        val style: String,
    )

    fun newModel(
        text: String,
        fontId: String,
        sizePt: Double,
        color: RgbaColor,
        x: Double,
        y: Double,
    ): JsonObject =
        buildJsonObject {
            put("format", 1)
            putJsonArray("content") { add(runJson(Run(text, FIRST_STYLE))) }
            putJsonObject("styles") {
                putJsonObject(FIRST_STYLE) {
                    put("fontId", fontId)
                    put("sizePt", sizePt)
                    putJsonArray("color") {
                        add(JsonPrimitive(color.red))
                        add(JsonPrimitive(color.green))
                        add(JsonPrimitive(color.blue))
                        add(JsonPrimitive(color.alpha))
                    }
                    put("tracking", 0)
                    put("kerning", "auto")
                    put("baselineShift", 0)
                    put("underline", false)
                    put("strikethrough", false)
                    put("allCaps", false)
                    putJsonObject("outline") {
                        put("width", 0)
                        putJsonArray("color") {
                            repeat(3) { add(JsonPrimitive(0)) }
                            add(JsonPrimitive(1))
                        }
                    }
                }
            }
            putJsonObject("paragraph") {
                put("alignment", "left")
                put("lineHeight", 1.2)
                put("vertical", false)
                put("direction", "auto")
            }
            putJsonObject("box") {
                put("mode", "auto")
                put("width", 0)
                put("height", 0)
            }
            putJsonArray("transform") { listOf(1.0, 0.0, 0.0, 1.0, x, y).forEach { add(JsonPrimitive(it)) } }
        }

    fun plainText(model: JsonObject): String = runs(model).joinToString("") { it.text }

    fun replace(
        model: JsonObject,
        start: Int,
        end: Int,
        inserted: String,
        style: String? = null,
    ): JsonObject {
        val current = runs(model)
        val length = current.sumOf { it.text.length }
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(from, length)
        val insertStyle = style ?: styleKeyAt(current, from)
        val result = mutableListOf<Run>()
        var offset = 0
        var insertedYet = false
        for (run in current) {
            val runStart = offset
            val runEnd = offset + run.text.length
            offset = runEnd
            if (runStart < from) result += Run(run.text.substring(0, minOf(run.text.length, from - runStart)), run.style)
            if (!insertedYet && runEnd >= from) {
                result += Run(inserted, insertStyle)
                insertedYet = true
            }
            if (runEnd > to) result += Run(run.text.substring(maxOf(0, to - runStart)), run.style)
        }
        if (!insertedYet) result += Run(inserted, insertStyle)
        return withRuns(model, result)
    }

    fun applyStyle(
        model: JsonObject,
        start: Int,
        end: Int,
        changes: JsonObject,
    ): JsonObject {
        val styles = styles(model).toMutableMap()
        val result = mutableListOf<Run>()
        var offset = 0
        for (run in runs(model)) {
            val runStart = offset
            val runEnd = offset + run.text.length
            offset = runEnd
            val from = (start - runStart).coerceIn(0, run.text.length)
            val to = (end - runStart).coerceIn(from, run.text.length)
            if (from == to && !(run.text.isEmpty() && start == runStart)) {
                result += run
                continue
            }
            val derived = deriveStyle(styles, run.style, changes)
            result += Run(run.text.substring(0, from), run.style)
            result += Run(run.text.substring(from, to), derived)
            result += Run(run.text.substring(to), run.style)
        }
        return withRuns(JsonObject(model + ("styles" to JsonObject(styles))), result)
    }

    fun withTypingStyle(
        model: JsonObject,
        index: Int,
        changes: JsonObject,
    ): Pair<JsonObject, String> {
        val styles = styles(model).toMutableMap()
        val key = deriveStyle(styles, styleKeyAt(runs(model), index), changes)
        return JsonObject(model + ("styles" to JsonObject(styles))) to key
    }

    fun styleAt(
        model: JsonObject,
        index: Int,
    ): JsonObject = styles(model)[styleKeyAt(runs(model), index)] ?: JsonObject(emptyMap())

    fun setParagraph(
        model: JsonObject,
        changes: JsonObject,
    ): JsonObject = merged(model, "paragraph", changes)

    fun setBox(
        model: JsonObject,
        changes: JsonObject,
    ): JsonObject = merged(model, "box", changes)

    fun transform(model: JsonObject): List<Double> =
        (model["transform"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() }?.takeIf { it.size == 6 }
            ?: listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    fun setTransform(
        model: JsonObject,
        transform: List<Double>,
    ): JsonObject {
        require(transform.size == 6) { "A transform has 6 numbers" }
        return JsonObject(model + ("transform" to buildJsonArray { transform.forEach { add(JsonPrimitive(it)) } }))
    }

    private fun merged(
        model: JsonObject,
        key: String,
        changes: JsonObject,
    ): JsonObject {
        val current = model[key] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(model + (key to JsonObject(current + changes)))
    }

    private fun runs(model: JsonObject): List<Run> =
        (model["content"] as? JsonArray).orEmpty().mapNotNull { element ->
            val run = element as? JsonObject ?: return@mapNotNull null
            Run(
                (run["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (run["style"] as? JsonPrimitive)?.contentOrNull ?: FIRST_STYLE,
            )
        }

    private fun styles(model: JsonObject): Map<String, JsonObject> =
        (model["styles"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }.toMap()

    private fun styleKeyAt(
        runs: List<Run>,
        index: Int,
    ): String {
        var offset = 0
        var last = runs.firstOrNull()?.style ?: FIRST_STYLE
        for (run in runs) {
            if (run.text.isNotEmpty()) {
                if (index in (offset + 1)..(offset + run.text.length)) return run.style
                if (index <= offset) return last.takeIf { offset > 0 } ?: run.style
                last = run.style
            }
            offset += run.text.length
        }
        return last
    }

    private fun deriveStyle(
        styles: MutableMap<String, JsonObject>,
        base: String,
        changes: JsonObject,
    ): String {
        val style = JsonObject((styles[base] ?: JsonObject(emptyMap())) + changes)
        styles.entries.firstOrNull { it.value == style }?.let { return it.key }
        var number = styles.size
        while ("s$number" in styles) number++
        styles["s$number"] = style
        return "s$number"
    }

    private fun withRuns(
        model: JsonObject,
        runs: List<Run>,
    ): JsonObject {
        val merged = mutableListOf<Run>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val previous = merged.lastOrNull()
            if (previous != null && previous.style == run.style) {
                merged[merged.lastIndex] = Run(previous.text + run.text, run.style)
            } else {
                merged += run
            }
        }
        if (merged.isEmpty()) merged += Run("", runs.firstOrNull()?.style ?: FIRST_STYLE)
        val used = merged.map { it.style }.toSet()
        val styles = styles(model).filterKeys { it in used }
        return JsonObject(
            model +
                mapOf<String, JsonElement>(
                    "content" to JsonArray(merged.map(::runJson)),
                    "styles" to JsonObject(styles),
                ),
        )
    }

    private fun runJson(run: Run): JsonObject =
        buildJsonObject {
            put("text", run.text)
            put("style", run.style)
        }

    private const val FIRST_STYLE = "s0"
}
