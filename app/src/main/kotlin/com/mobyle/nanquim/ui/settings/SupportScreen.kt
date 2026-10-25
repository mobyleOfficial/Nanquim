package com.mobyle.nanquim.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.ui.common.ScreenHeader

data class SupportLink(
    val label: String,
    val url: String,
)

fun parseSupportLinks(items: List<String>): List<SupportLink> =
    items.mapNotNull { item ->
        val label = item.substringBefore('|', "").trim()
        val url = item.substringAfter('|', "").trim()
        if (label.isEmpty() || !url.startsWith("https://") || url.any { it.isWhitespace() }) null else SupportLink(label, url)
    }

@Composable
fun SupportScreen(
    links: List<SupportLink>,
    onOpenLink: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            ScreenHeader(title = stringResource(R.string.support_title), onBack = onBack)
            HorizontalDivider()
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.support_intro), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.support_donate_title), style = MaterialTheme.typography.titleMedium)
                if (links.isEmpty()) {
                    Text(
                        stringResource(R.string.support_no_links),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    links.forEachIndexed { index, link ->
                        OutlinedButton(onClick = { onOpenLink(index) }, modifier = Modifier.fillMaxWidth()) { Text(link.label) }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.support_other_title), style = MaterialTheme.typography.titleMedium)
                listOf(
                    R.string.support_other_share,
                    R.string.support_other_report,
                    R.string.support_other_translate,
                    R.string.support_other_brushes,
                ).forEach {
                    Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
