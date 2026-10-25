package com.mobyle.nanquim.ui.settings

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.BuildConfig
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ui.common.ScreenHeader

data class CreditEntry(
    val section: String,
    val name: String,
    val use: String,
    val license: String,
)

fun parseCredits(markdown: String): List<CreditEntry> {
    val entries = mutableListOf<CreditEntry>()
    var section = ""
    var header: List<String> = emptyList()
    for (line in markdown.lines()) {
        if (line.startsWith("## ")) {
            section = line.removePrefix("## ").trim()
            header = emptyList()
            continue
        }
        if (!line.startsWith("|")) continue
        val cells = line.trim().trim('|').split('|').map { it.trim().replace("`", "") }
        if (cells.all { it.matches(Regex("-+")) }) continue
        if (header.isEmpty()) {
            header = cells.map { it.lowercase() }
            continue
        }
        fun cell(name: String) = header.indexOf(name).takeIf { it >= 0 }?.let(cells::getOrNull).orEmpty()
        if (header.contains("scope") && cell("scope") != "app") continue
        val name = cell("library").ifEmpty { cell("path") }
        val use = cell("use").ifEmpty { listOf(cell("author"), cell("source")).filter { it.isNotEmpty() }.joinToString(" · ") }
        if (name.isNotEmpty()) entries += CreditEntry(section, name, use, cell("license"))
    }
    return entries
}

@Composable
fun AboutScreen(
    assets: AssetManager,
    onBack: () -> Unit,
) {
    val credits by produceState(emptyList<CreditEntry>()) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { assets.open(CREDITS_ASSET).bufferedReader().readText() }.getOrNull()?.let(::parseCredits).orEmpty()
            }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.about_title), onBack = onBack)
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                    Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                }
                items(credits) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        if (entry.use.isNotEmpty()) Text(entry.use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.license, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

private const val CREDITS_ASSET = "about/CREDITS.md"
