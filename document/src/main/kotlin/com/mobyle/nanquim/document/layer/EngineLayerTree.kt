package com.mobyle.nanquim.document.layer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import com.mobyle.nanquim.document.animation.FrameAppearance

fun DocumentState.toEngineLayerTree(
    soloLayerId: Int? = null,
    frames: Map<Int, FrameAppearance>? = null,
    hideBackground: Boolean = false,
    hidePrivate: Boolean = false,
): JsonObject =
    buildJsonObject {
        putJsonArray("background") {
            add(JsonPrimitive(background.color.red))
            add(JsonPrimitive(background.color.green))
            add(JsonPrimitive(background.color.blue))
            add(JsonPrimitive(background.color.alpha))
        }
        put("backgroundHidden", background.hidden || hideBackground)
        if (pixelFormat != PixelFormat.Rgba8) put("pixelFormat", pixelFormat.id)
        put("activeLayer", activeLayerId?.let(::JsonPrimitive) ?: JsonNull)
        put("solo", soloLayerId?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "layers",
            buildJsonArray {
                for (node in layers) {
                    val frame = frames?.get(node.id)
                    add(node.toEngineJson(frame?.tint, frame?.opacityFactor ?: 1f, frame?.hidden == true, hidePrivate))
                }
            },
        )
    }

private fun List<LayerNode>.toEngineJson(
    tint: RgbaColor?,
    hidePrivate: Boolean,
): JsonArray = buildJsonArray { this@toEngineJson.forEach { add(it.toEngineJson(tint, hidePrivate = hidePrivate)) } }

private fun LayerNode.toEngineJson(
    tint: RgbaColor? = null,
    opacityFactor: Float = 1f,
    hiddenByFrame: Boolean = false,
    hidePrivate: Boolean = false,
): JsonElement =
    buildJsonObject {
        put("type", if (this@toEngineJson is LayerGroup) "group" else "raster")
        put("id", id)
        put("opacity", opacity * opacityFactor)
        put("blend", blend.id)
        put("hidden", hidden || hiddenByFrame || (hidePrivate && (this@toEngineJson as? RasterLayer)?.isPrivate == true))
        when (val node = this@toEngineJson) {
            is RasterLayer -> {
                put("alphaLock", node.alphaLock)
                put("clipping", node.clipping)
                tint?.let {
                    putJsonArray("tint") {
                        add(JsonPrimitive(it.red))
                        add(JsonPrimitive(it.green))
                        add(JsonPrimitive(it.blue))
                        add(JsonPrimitive(it.alpha))
                    }
                }
                node.mask?.let { mask ->
                    put(
                        "mask",
                        buildJsonObject {
                            put("id", mask.id)
                            put("enabled", mask.enabled)
                            put("inverted", mask.inverted)
                        },
                    )
                }
            }
            is LayerGroup -> put("children", node.children.toEngineJson(tint, hidePrivate))
        }
    }
