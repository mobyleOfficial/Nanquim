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
import kotlinx.serialization.json.putJsonObject

fun DocumentState.toJson(): JsonObject =
    buildJsonObject {
        put("width", widthPx)
        put("height", heightPx)
        put("bitsPerChannel", pixelFormat.bitsPerChannel)
        putJsonObject("background") {
            putJsonArray("color") {
                add(JsonPrimitive(background.color.red))
                add(JsonPrimitive(background.color.green))
                add(JsonPrimitive(background.color.blue))
                add(JsonPrimitive(background.color.alpha))
            }
            put("hidden", background.hidden)
        }
        put("activeLayerId", activeLayerId?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("animation") {
            put("enabled", animation.enabled)
            put("playback", animation.playback.id)
            put("framesPerSecond", animation.framesPerSecond)
            put("onionSkin", animation.onionSkin.enabled)
            put("onionFrames", animation.onionSkin.frames)
            put("firstFrameIsBackground", animation.firstFrameIsBackground)
            put("lastFrameIsForeground", animation.lastFrameIsForeground)
            putJsonObject("holdFrames") { animation.holdFrames.toSortedMap().forEach { (id, hold) -> put(id.toString(), hold) } }
        }
        put("layers", layers.nodesJson())
    }

private fun List<LayerNode>.nodesJson(): JsonArray = buildJsonArray { this@nodesJson.forEach { add(it.json()) } }

private fun LayerNode.json(): JsonElement =
    buildJsonObject {
        put(
            "type",
            when {
                this@json is LayerGroup -> "group"
                (this@json as RasterLayer).text != null -> "text"
                this@json.video != null -> "video"
                else -> "raster"
            },
        )
        put("id", id)
        put("name", name)
        put("opacity", opacity)
        put("blend", blend.id)
        put("hidden", hidden)
        put("locked", locked)
        when (val node = this@json) {
            is RasterLayer -> {
                put("alphaLock", node.alphaLock)
                put("clipping", node.clipping)
                put("isReference", node.isReference)
                put("assisted", node.assisted)
                put("private", node.isPrivate)
                node.text?.let { text -> put("text", text.plainText) }
                node.video?.let { video ->
                    putJsonObject("video") {
                        put("timeMicroseconds", video.timeMicroseconds)
                        put("durationMicroseconds", video.durationMicroseconds)
                    }
                }
                put(
                    "mask",
                    node.mask?.let { mask ->
                        buildJsonObject {
                            put("id", mask.id)
                            put("enabled", mask.enabled)
                            put("inverted", mask.inverted)
                        }
                    } ?: JsonNull,
                )
            }
            is LayerGroup -> {
                put("collapsed", node.collapsed)
                put("children", node.children.nodesJson())
            }
        }
    }
