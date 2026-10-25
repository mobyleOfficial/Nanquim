package com.mobyle.nanquim.engine

import android.view.Surface

object VulkanProbe {
    init {
        System.loadLibrary("nanquim_engine")
    }

    fun run(
        surface: Surface,
        switchCycles: Int = 10,
    ): String = nativeRun(surface, switchCycles)

    @JvmStatic
    private external fun nativeRun(
        surface: Surface,
        switchCycles: Int,
    ): String
}
