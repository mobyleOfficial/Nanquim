package com.mobyle.nanquim.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.glance.appwidget.updateAll
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.mobyle.nanquim.NanquimApplication
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecentArtworkWidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val application = context.applicationContext as NanquimApplication
    private val manager = AppWidgetManager.getInstance(context)
    private lateinit var host: AppWidgetHost
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var view: AppWidgetHostView

    @Before
    fun placeWidget() {
        instrumentation.runOnMainSync {
            host = CountingHost()
            host.startListening()
            appWidgetId = host.allocateAppWidgetId()
        }
        val bound = manager.bindAppWidgetIdIfAllowed(appWidgetId, ComponentName(context, RecentArtworkWidgetReceiver::class.java))
        assumeTrue("binding not granted (adb shell appwidget grantbind --package com.mobyle.nanquim)", bound)
        instrumentation.runOnMainSync {
            view = host.createView(context, appWidgetId, manager.getAppWidgetInfo(appWidgetId))
        }
        runBlocking { RecentArtworkWidget().updateAll(context) }
    }

    @After
    fun removeWidget() {
        instrumentation.runOnMainSync {
            host.deleteAppWidgetId(appWidgetId)
            host.stopListening()
        }
    }

    private fun texts(): List<String> {
        val found = mutableListOf<String>()
        instrumentation.runOnMainSync { collect(view, found) }
        return found
    }

    private fun collect(
        view: View,
        found: MutableList<String>,
    ) {
        if (view is TextView) found += view.text.toString()
        if (view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it), found) }
    }

    private fun waitFor(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(250)
        }
        return condition()
    }

    @Test
    fun showsTheLatestArtworkFollowsChangesAndOpensIt() {
        val latest = runBlocking { application.galleryDatabase.dao().artworks() }.maxByOrNull { it.modifiedAt }
        assumeTrue("no artwork in the gallery", latest != null)
        assertTrue("widget shows ${texts()}", waitFor(10_000) { latest!!.name in texts() })
        savePicture()

        val renamed = latest!!.name + " (widget)"
        instrumentation.runOnMainSync { runBlocking { application.gallery.rename(latest.id, renamed) } }
        assertTrue("widget shows ${texts()} after the rename", waitFor(WidgetUpdates.MINIMUM_INTERVAL_MS + 15_000) { renamed in texts() })

        instrumentation.runOnMainSync { runBlocking { application.gallery.rename(latest.id, latest.name) } }

        instrumentation.runOnMainSync { clickFirstClickable(view) }
        assertTrue("the canvas did not open the artwork", waitFor(20_000) { application.canvasSession.artwork?.id == latest.id })

        val updatesBefore = updates
        val stroke =
            buildJsonArray {
                (0..20).forEach { step ->
                    add(
                        buildJsonArray {
                            add(100.0 + step * 20)
                            add(120.0 + step * 10)
                            add(0.8)
                            add(step * 8)
                        },
                    )
                }
            }
        dispatchAndWait("tool.paint.strokePath", buildJsonObject { put("samples", stroke) })
        dispatchAndWait("document.save", buildJsonObject {})
        assertTrue("no widget update after the save", waitFor(WidgetUpdates.MINIMUM_INTERVAL_MS + 15_000) { updates > updatesBefore })
    }

    private fun savePicture() {
        Thread.sleep(1_000)
        instrumentation.runOnMainSync {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(SIDE_PX, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SIDE_PX, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, SIDE_PX, SIDE_PX)
            val picture = Bitmap.createBitmap(SIDE_PX, SIDE_PX, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(picture))
            File(
                context.getExternalFilesDir(null),
                "widget.png",
            ).outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Volatile
    private var updates = 0

    private inner class CountingHost : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(
            context: android.content.Context,
            appWidgetId: Int,
            appWidget: android.appwidget.AppWidgetProviderInfo?,
        ): AppWidgetHostView =
            object : AppWidgetHostView(context) {
                override fun updateAppWidget(remoteViews: android.widget.RemoteViews?) {
                    super.updateAppWidget(remoteViews)
                    updates++
                }
            }
    }

    private fun dispatchAndWait(
        commandId: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ) {
        val done = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync { application.commandDispatcher.dispatch(commandId, arguments) { done.countDown() } }
        assertTrue("$commandId did not finish", done.await(20, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun clickFirstClickable(view: View): Boolean {
        if (view.hasOnClickListeners()) return view.performClick()
        if (view is ViewGroup) (0 until view.childCount).forEach { if (clickFirstClickable(view.getChildAt(it))) return true }
        return false
    }

    private companion object {
        const val HOST_ID = 4711
        const val SIDE_PX = 480
    }
}
