package com.mobyle.nanquim.data.engine

import com.mobyle.nanquim.domain.settings.models.Renderer

/**
 * The renderer used until the person picks one in Diagnostics. GLES everywhere but Unisoc: its
 * BufferQueue hangs when GLES switches the window in and out of single buffering, so the engine keeps
 * GLES double buffered there, while Vulkan's front-buffered swapchain draws strokes with the pen
 * down. Kept in step with isUnisoc() in the engine's egl_session.cpp.
 */
fun defaultRenderer(): Renderer = if (isUnisoc()) Renderer.Vulkan else Renderer.Gles

private fun isUnisoc(): Boolean {
    val manufacturer = systemProperty("ro.soc.manufacturer").lowercase()
    if (manufacturer == "unisoc" || manufacturer == "spreadtrum") return true
    val platform = systemProperty("ro.board.platform").lowercase()
    return platform.startsWith("ums") || (platform.startsWith("sp") && platform.getOrNull(2)?.isDigit() == true)
}

private fun systemProperty(name: String): String =
    runCatching {
        @Suppress("PrivateApi")
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull().orEmpty()
