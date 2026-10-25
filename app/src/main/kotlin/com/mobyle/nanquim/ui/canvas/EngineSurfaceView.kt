package com.mobyle.nanquim.ui.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.mobyle.nanquim.engine.Engine

@SuppressLint("ViewConstructor")
open class EngineSurfaceView(
    context: Context,
    protected val engine: Engine,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = engine.attachSurface(holder.surface, width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
}
