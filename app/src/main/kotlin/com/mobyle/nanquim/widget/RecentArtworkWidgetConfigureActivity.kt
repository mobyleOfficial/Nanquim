package com.mobyle.nanquim.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.NanquimApplication
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.ArtworkEntity
import com.mobyle.nanquim.ui.theme.NanquimTheme
import java.io.File

class RecentArtworkWidgetConfigureActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId =
            intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val application = application as NanquimApplication
        val current = WidgetChoices.chosen(this, appWidgetId)
        setContent {
            NanquimTheme {
                var artworks by remember { mutableStateOf<List<ArtworkEntity>>(emptyList()) }
                LaunchedEffect(Unit) {
                    artworks =
                        application.galleryDatabase
                            .dao()
                            .artworks()
                            .sortedByDescending { it.modifiedAt }
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(modifier = Modifier.safeDrawingPadding().padding(16.dp)) {
                        Text(stringResource(R.string.widget_configure_title), style = MaterialTheme.typography.titleLarge)
                        LazyColumn(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            item {
                                Choice(stringResource(R.string.widget_most_recent), null, selected = current == null) { choose(null) }
                            }
                            items(artworks, key = { it.id }) { artwork ->
                                val folder = application.artworkStorage.folderOf(artwork.id)
                                Choice(artwork.name, File(folder, "thumbnail.png"), selected = current == artwork.id) { choose(artwork.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(artworkId: String?) {
        WidgetChoices.choose(this, appWidgetId, artworkId)
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@RecentArtworkWidgetConfigureActivity).getGlanceIdBy(appWidgetId)
            RecentArtworkWidget().refresh(this@RecentArtworkWidgetConfigureActivity, glanceId)
            setResult(RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun Choice(
    name: String,
    thumbnail: File?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var picture by remember(thumbnail) { mutableStateOf<Bitmap?>(null) }
    if (thumbnail != null) {
        LaunchedEffect(thumbnail) { picture = withContext(Dispatchers.IO) { WidgetArtwork.decodeSmall(thumbnail) } }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            picture?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(name, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
    }
}
