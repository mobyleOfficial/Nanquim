package com.mobyle.nanquim.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.mobyle.nanquim.presentation.widget.RecentArtworkWidget
import com.mobyle.nanquim.presentation.widget.WidgetChoices

// Stays in this package: launchers keep placed widgets by this class's name, and they drop the ones whose receiver is gone.
class RecentArtworkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecentArtworkWidget()

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetChoices.forget(context, it) }
    }
}
