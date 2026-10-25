package com.mobyle.nanquim.display

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.widget.ImageView
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.canvas.CanvasSession
import com.mobyle.nanquim.document.RgbaImages
import com.mobyle.nanquim.engine.Engine

class ProjectedCanvas(
    private val activity: Activity,
    private val engine: Engine,
    private val session: CanvasSession,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
) : DisplayManager.DisplayListener {
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var presentation: CanvasPresentation? = null
    private var jobs: List<Job> = emptyList()
    private var requesting = false
    private var requestAgain = false

    fun start() {
        displays.registerDisplayListener(this, null)
        jobs =
            listOf(
                scope.launch { snapshotFlow { enabled() && session.artwork != null }.collect { update() } },
                scope.launch { snapshotFlow { session.documentVersion }.collect { refresh() } },
                scope.launch {
                    snapshotFlow { session.strokeInProgress }.collectLatest { drawing ->
                        while (drawing) {
                            delay(STROKE_REFRESH_MILLIS)
                            refresh()
                        }
                    }
                },
            )
    }

    fun stop() {
        displays.unregisterDisplayListener(this)
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        show(null)
    }

    override fun onDisplayAdded(displayId: Int) = update()

    override fun onDisplayRemoved(displayId: Int) = update()

    override fun onDisplayChanged(displayId: Int) = Unit

    private fun update() {
        val wanted = enabled() && session.artwork != null
        show(if (wanted) displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull() else null)
    }

    private fun show(display: Display?) {
        if (presentation?.display?.displayId == display?.displayId && (display == null || presentation?.isShowing == true)) return
        presentation?.dismiss()
        presentation = null
        session.projectedDisplay = null
        if (display == null) return
        presentation =
            CanvasPresentation(activity, display).also {
                it.setOnDismissListener { _ -> if (presentation === it) update() }
                runCatching { it.show() }.onFailure { presentation = null }
            }
        if (presentation != null) {
            session.projectedDisplay = display.name
            refresh()
        }
    }

    private fun refresh() {
        val target = presentation ?: return
        if (requesting) {
            requestAgain = true
            return
        }
        requesting = true
        val metrics = target.display.mode
        engine.requestCompositePreview(maxOf(metrics.physicalWidth, metrics.physicalHeight)) { render ->
            scope.launch {
                val bitmap =
                    if (render.error == null && render.width > 0) {
                        withContext(Dispatchers.Default) { RgbaImages.premultipliedToBitmap(render.width, render.height, render.pixels) }
                    } else {
                        null
                    }
                if (bitmap != null && presentation === target) target.showImage(bitmap)
                requesting = false
                if (requestAgain) {
                    requestAgain = false
                    refresh()
                }
            }
        }
    }

    private class CanvasPresentation(
        context: Context,
        display: Display,
    ) : Presentation(context, display) {
        private lateinit var image: ImageView

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            image =
                ImageView(context).apply {
                    setBackgroundColor(Color.BLACK)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            setContentView(image)
        }

        fun showImage(bitmap: Bitmap) {
            if (::image.isInitialized) image.setImageBitmap(bitmap)
        }
    }

    private companion object {
        const val STROKE_REFRESH_MILLIS = 100L
    }
}
