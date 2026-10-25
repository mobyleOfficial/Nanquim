package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.mobyle.nanquim.document.animation.AnimationSettings
import com.mobyle.nanquim.document.animation.OnionBlend
import com.mobyle.nanquim.document.animation.OnionSkin
import com.mobyle.nanquim.document.animation.PlaybackMode
import com.mobyle.nanquim.document.layer.RgbaColor

object AnimationJson {
    const val FILE = "animation.json"

    const val MAXIMUM_BYTES = 1L * 1024 * 1024

    private val known =
        setOf("format", "enabled", "playback", "framesPerSecond", "onionSkin", "firstFrameIsBackground", "lastFrameIsForeground", "frames")
    private val json = Json { prettyPrint = false }

    fun read(
        text: String,
        idOfUuid: (String) -> Int?,
    ): AnimationSettings {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return AnimationSettings()
        val defaults = AnimationSettings()
        val onion = root["onionSkin"] as? JsonObject
        val onionDefaults = OnionSkin()
        val holds =
            (root["frames"] as? JsonObject)
                ?.mapNotNull { (uuid, value) ->
                    val id = idOfUuid(uuid) ?: return@mapNotNull null
                    val hold = ((value as? JsonObject)?.get("holdFrames") as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                    if (hold > 0) id to hold.coerceAtMost(AnimationSettings.MAXIMUM_HOLD_FRAMES) else null
                }?.toMap()
                .orEmpty()
        return AnimationSettings(
            enabled = root.boolean("enabled") ?: false,
            playback = PlaybackMode.fromId(root.string("playback")) ?: defaults.playback,
            framesPerSecond = (root.int("framesPerSecond") ?: defaults.framesPerSecond).coerceIn(AnimationSettings.FRAMES_PER_SECOND),
            onionSkin =
                OnionSkin(
                    frames = (onion?.int("frames") ?: onionDefaults.frames).coerceIn(0, OnionSkin.MAXIMUM_FRAMES),
                    opacity = (onion?.double("opacity")?.toFloat() ?: onionDefaults.opacity).coerceIn(0f, 1f),
                    colorPrevious = onion?.color("colorPrevious") ?: onionDefaults.colorPrevious,
                    colorNext = onion?.color("colorNext") ?: onionDefaults.colorNext,
                    blend = OnionBlend.fromId(onion?.string("blend")) ?: onionDefaults.blend,
                    enabled = onion?.boolean("enabled") ?: onionDefaults.enabled,
                ),
            firstFrameIsBackground = root.boolean("firstFrameIsBackground") ?: false,
            lastFrameIsForeground = root.boolean("lastFrameIsForeground") ?: false,
            holdFrames = holds,
            extra = JsonObject(root.filterKeys { it !in known }),
        )
    }

    fun write(
        settings: AnimationSettings,
        uuidOf: (Int) -> String?,
    ): ByteArray {
        val root =
            buildJsonObject {
                settings.extra.forEach { (key, value) -> put(key, value) }
                put("format", 1)
                put("enabled", settings.enabled)
                put("playback", settings.playback.id)
                put("framesPerSecond", settings.framesPerSecond)
                put(
                    "onionSkin",
                    buildJsonObject {
                        put("frames", settings.onionSkin.frames)
                        put("opacity", settings.onionSkin.opacity)
                        put("colorPrevious", settings.onionSkin.colorPrevious.json())
                        put("colorNext", settings.onionSkin.colorNext.json())
                        put("blend", settings.onionSkin.blend.id)
                        put("enabled", settings.onionSkin.enabled)
                    },
                )
                put("firstFrameIsBackground", settings.firstFrameIsBackground)
                put("lastFrameIsForeground", settings.lastFrameIsForeground)
                put(
                    "frames",
                    buildJsonObject {
                        for ((id, hold) in settings.holdFrames.toSortedMap()) {
                            val uuid = uuidOf(id) ?: continue
                            put(uuid, buildJsonObject { put("holdFrames", hold) })
                        }
                    },
                )
            }
        return json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
    }

    private fun RgbaColor.json(): JsonElement =
        buildJsonArray {
            add(JsonPrimitive(red))
            add(JsonPrimitive(green))
            add(JsonPrimitive(blue))
            add(JsonPrimitive(alpha))
        }

    private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.color(key: String): RgbaColor? {
        val values = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat()?.coerceIn(0f, 1f) } ?: return null
        if (values.size < 3) return null
        return RgbaColor(values[0], values[1], values[2], values.getOrElse(3) { 1f })
    }
}
