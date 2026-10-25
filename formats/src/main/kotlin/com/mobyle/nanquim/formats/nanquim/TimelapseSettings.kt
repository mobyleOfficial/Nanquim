package com.mobyle.nanquim.formats.nanquim

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class TimelapseResolution(
    val id: String,
    val longSidePixels: Int,
    val shortSidePixels: Int,
) {
    FullHd("1080p", 1920, 1080),
    QuadHd("2k", 2560, 1440),
    UltraHd("4k", 3840, 2160),
    Canvas("canvas", 0, 0),
    ;

    companion object {
        fun fromId(id: String?): TimelapseResolution? = entries.firstOrNull { it.id == id }
    }
}

data class TimelapseSettings(
    val enabled: Boolean = true,
    val resolution: TimelapseResolution = TimelapseResolution.FullHd,
    val hevc: Boolean = false,
) {
    companion object {
        const val FOLDER = "timelapse"

        fun read(element: Any?): TimelapseSettings? {
            val value = element as? JsonObject ?: return null
            val defaults = TimelapseSettings()
            return TimelapseSettings(
                enabled = (value["enabled"] as? JsonPrimitive)?.booleanOrNull ?: defaults.enabled,
                resolution = TimelapseResolution.fromId((value["resolution"] as? JsonPrimitive)?.contentOrNull) ?: defaults.resolution,
                hevc = (value["hevc"] as? JsonPrimitive)?.booleanOrNull ?: defaults.hevc,
            )
        }

        fun write(
            settings: TimelapseSettings,
            previous: JsonObject?,
        ): JsonObject =
            JsonObject(
                previous.orEmpty() +
                    mapOf(
                        "enabled" to JsonPrimitive(settings.enabled),
                        "resolution" to JsonPrimitive(settings.resolution.id),
                        "hevc" to JsonPrimitive(settings.hevc),
                    ),
            )
    }
}
