package com.mobyle.nanquim.engine

import org.junit.Assert.assertEquals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun Engine.checkRenderer(renderer: Renderer) {
    val done = CountDownLatch(1)
    var api: String? = null
    requestGpuInfo {
        api = it.api
        done.countDown()
    }
    check(done.await(30, TimeUnit.SECONDS)) { "GPU info timed out" }
    assertEquals(if (renderer == Renderer.Vulkan) "vulkan" else "gles", api)
}
