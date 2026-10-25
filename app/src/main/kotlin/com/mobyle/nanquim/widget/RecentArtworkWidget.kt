package com.mobyle.nanquim.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.MainActivity
import com.mobyle.nanquim.NanquimApplication
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.ArtworkEntity
import java.io.File

class RecentArtworkWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val first = withContext(Dispatchers.IO) { WidgetArtwork.load(context, appWidgetId) }
        provideContent {
            val version = currentState(VERSION) ?: 0
            var shown by remember { mutableStateOf(first) }
            LaunchedEffect(version) {
                if (version > 0) shown = withContext(Dispatchers.IO) { WidgetArtwork.load(context, appWidgetId) }
            }
            Content(context, shown)
        }
    }

    suspend fun refreshAll(context: Context) {
        GlanceAppWidgetManager(context).getGlanceIds(RecentArtworkWidget::class.java).forEach { refresh(context, it) }
    }

    suspend fun refresh(
        context: Context,
        id: GlanceId,
    ) {
        updateAppWidgetState(context, id) { state -> state[VERSION] = (state[VERSION] ?: 0) + 1 }
        update(context, id)
    }

    @Composable
    private fun Content(
        context: Context,
        shown: WidgetArtwork?,
    ) {
        val open =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                shown?.let { putExtra(MainActivity.EXTRA_OPEN_ARTWORK, it.artwork.id) }
            }
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(16.dp)
                    .background(ColorProvider(BACKGROUND))
                    .clickable(actionStartActivity(open)),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (shown?.thumbnail != null) {
                Image(
                    provider = ImageProvider(shown.thumbnail),
                    contentDescription = shown.artwork.name,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            Column(
                modifier = GlanceModifier.fillMaxWidth().background(ColorProvider(SCRIM)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = shown?.artwork?.name ?: context.getString(R.string.widget_empty),
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }

    private companion object {
        val VERSION = intPreferencesKey("version")
        val BACKGROUND = Color(0xFF2B2622)
        val SCRIM = Color(0x99000000)
    }
}

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

class WidgetArtwork(
    val artwork: ArtworkEntity,
    val thumbnail: Bitmap?,
) {
    companion object {
        private const val MAXIMUM_SIDE = 512

        suspend fun load(
            context: Context,
            appWidgetId: Int,
        ): WidgetArtwork? {
            val application = context.applicationContext as NanquimApplication
            val artworks = application.galleryDatabase.dao().artworks()
            val chosen = WidgetChoices.chosen(context, appWidgetId)
            val artwork = artworks.firstOrNull { it.id == chosen } ?: artworks.maxByOrNull { it.modifiedAt } ?: return null
            val file = File(application.artworkStorage.folderOf(artwork.id), "thumbnail.png")
            return WidgetArtwork(artwork, decodeSmall(file))
        }

        fun decodeSmall(file: File): Bitmap? {
            if (!file.isFile) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAXIMUM_SIDE) sample *= 2
            return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}

object WidgetChoices {
    private const val PREFERENCES = "widgets"

    private fun key(appWidgetId: Int) = "artwork.$appWidgetId"

    fun chosen(
        context: Context,
        appWidgetId: Int,
    ): String? = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(key(appWidgetId), null)

    fun choose(
        context: Context,
        appWidgetId: Int,
        artworkId: String?,
    ) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (artworkId == null) editor.remove(key(appWidgetId)) else editor.putString(key(appWidgetId), artworkId)
        editor.commit()
    }

    fun forget(
        context: Context,
        appWidgetId: Int,
    ) = choose(context, appWidgetId, null)
}
