package com.mobyle.nanquim.engine

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProbeSurfaceActivity : Activity() {
    private val surfaceReady = CountDownLatch(1)

    @Volatile
    private var surface: Surface? = null

    @Volatile
    var surfaceWidth = 0
        private set

    @Volatile
    var surfaceHeight = 0
        private set

    lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.colorMode = ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
        val view = SurfaceView(this)
        surfaceView = view
        view.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    surfaceWidth = width
                    surfaceHeight = height
                    surface = holder.surface
                    surfaceReady.countDown()
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                }
            },
        )
        setContentView(view)
    }

    fun awaitSurface(timeoutSeconds: Long): Surface? = if (surfaceReady.await(timeoutSeconds, TimeUnit.SECONDS)) surface else null
}
