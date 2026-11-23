package com.mobyle.nanquim.domain.settings.models

/** The graphics API the engine draws with. [id] is what settings, commands and telemetry store. */
enum class Renderer(
    val id: String,
) {
    Gles("gles"),
    Vulkan("vulkan"),
    ;

    companion object {
        fun fromId(id: String?): Renderer? = entries.firstOrNull { it.id == id }
    }
}
