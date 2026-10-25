package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import com.mobyle.nanquim.document.reference.ReferenceCorner
import com.mobyle.nanquim.document.reference.ReferenceMode
import com.mobyle.nanquim.document.reference.ReferenceSettings
import com.mobyle.nanquim.document.reference.ReferenceView

internal object ReferenceJson {
    const val IMAGE_PATH = "reference/image.png"

    fun read(element: Any?): ReferenceSettings? {
        val reference = element as? JsonObject ?: return null
        val defaults = ReferenceSettings()
        val window = reference["window"] as? JsonObject
        val docked = window?.get("docked")
        return ReferenceSettings(
            visible = (reference["visible"] as? JsonPrimitive)?.booleanOrNull ?: defaults.visible,
            mode = reference.text("mode")?.let(ReferenceMode::fromId) ?: defaults.mode,
            x = window?.number("x")?.coerceIn(0.0, 1.0) ?: defaults.x,
            y = window?.number("y")?.coerceIn(0.0, 1.0) ?: defaults.y,
            width = window?.number("width")?.coerceAtLeast(ReferenceSettings.MINIMUM_SIDE) ?: defaults.width,
            height = window?.number("height")?.coerceAtLeast(ReferenceSettings.MINIMUM_SIDE) ?: defaults.height,
            docked =
                when {
                    window == null || docked == null -> defaults.docked
                    docked is JsonNull -> null
                    else -> window.text("docked")?.let(ReferenceCorner::fromId) ?: defaults.docked
                },
            opacity = reference.number("opacity")?.toFloat()?.coerceIn(ReferenceSettings.MINIMUM_OPACITY, 1f) ?: defaults.opacity,
            canvasView = view(reference["canvasView"]),
            imageView = view(reference["imageView"]),
            hasImage = reference.text("image") == IMAGE_PATH,
        )
    }

    fun write(
        reference: ReferenceSettings,
        previous: JsonObject?,
    ): JsonObject {
        if (previous != null && read(previous) == reference) return previous
        val window =
            JsonObject(
                (previous?.get("window") as? JsonObject).orEmpty() +
                    mapOf(
                        "x" to JsonPrimitive(reference.x),
                        "y" to JsonPrimitive(reference.y),
                        "width" to JsonPrimitive(reference.width),
                        "height" to JsonPrimitive(reference.height),
                        "docked" to (reference.docked?.let { JsonPrimitive(it.id) } ?: JsonNull),
                    ),
            )
        val fields =
            previous.orEmpty() +
                mapOf(
                    "visible" to JsonPrimitive(reference.visible),
                    "mode" to JsonPrimitive(reference.mode.id),
                    "opacity" to JsonPrimitive(reference.opacity),
                    "window" to window,
                    "canvasView" to viewJson(reference.canvasView, previous?.get("canvasView") as? JsonObject),
                    "imageView" to viewJson(reference.imageView, previous?.get("imageView") as? JsonObject),
                )
        return JsonObject(if (reference.hasImage) fields + ("image" to JsonPrimitive(IMAGE_PATH)) else fields - "image")
    }

    private fun view(element: JsonElement?): ReferenceView {
        val view = element as? JsonObject ?: return ReferenceView()
        val center = (view["center"] as? JsonArray)?.numbers()
        return ReferenceView(
            zoom = view.number("zoom")?.coerceIn(ReferenceView.MINIMUM_ZOOM, ReferenceView.MAXIMUM_ZOOM) ?: 1.0,
            centerX = center?.getOrNull(0)?.coerceIn(0.0, 1.0) ?: 0.5,
            centerY = center?.getOrNull(1)?.coerceIn(0.0, 1.0) ?: 0.5,
        )
    }

    private fun viewJson(
        view: ReferenceView,
        previous: JsonObject?,
    ) = JsonObject(
        previous.orEmpty() +
            mapOf(
                "zoom" to JsonPrimitive(view.zoom),
                "center" to JsonArray(listOf(JsonPrimitive(view.centerX), JsonPrimitive(view.centerY))),
            ),
    )

    private fun JsonArray.numbers(): List<Double> =
        mapNotNull { element ->
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf(Double::isFinite)
        }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf(Double::isFinite)
}
