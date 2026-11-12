package com.mobyle.nanquim.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.command.FeedbackCommandIds
import com.mobyle.nanquim.feedback.FeedbackChannel
import com.mobyle.nanquim.feedback.FeedbackKind
import com.mobyle.nanquim.settings.AppSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Rate, share, report, privacy and the legal pages, at the end of Settings. */
@Composable
fun FeedbackSection(
    appSettings: AppSettings,
    onCommand: (String, JsonObject) -> Unit,
    onOpenAdChoices: (() -> Unit)? = null,
) {
    var reporting by remember { mutableStateOf<FeedbackKind?>(null) }
    val none = JsonObject(emptyMap())
    Text(stringResource(R.string.settings_feedback_title), style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = { onCommand(FeedbackCommandIds.RATE, none) }) { Text(stringResource(R.string.settings_rate)) }
    TextButton(onClick = { onCommand(FeedbackCommandIds.SHARE_APP, none) }) { Text(stringResource(R.string.settings_share_app)) }
    TextButton(onClick = { reporting = FeedbackKind.Bug }) { Text(stringResource(R.string.settings_report_bug)) }
    TextButton(onClick = { reporting = FeedbackKind.Feature }) { Text(stringResource(R.string.settings_suggest_feature)) }

    Text(stringResource(R.string.settings_privacy_title), style = MaterialTheme.typography.titleMedium)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = appSettings.usageStatistics,
                role = Role.Switch,
                onValueChange = { onCommand(FeedbackCommandIds.USAGE_STATISTICS, JsonObject(mapOf("enabled" to JsonPrimitive(it)))) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_usage_statistics), modifier = Modifier.weight(1f))
        Switch(checked = appSettings.usageStatistics, onCheckedChange = null)
    }
    Text(stringResource(R.string.settings_usage_statistics_hint), style = MaterialTheme.typography.bodySmall)
    // Only where the consent form applies (EEA, UK…): elsewhere Android's own ad settings are the place.
    onOpenAdChoices?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.settings_ad_choices)) } }
    LegalLinks(onCommand)

    reporting?.let { kind ->
        FeedbackChannelDialog(
            kind = kind,
            onChoose = { channel ->
                reporting = null
                onCommand(
                    FeedbackCommandIds.REPORT,
                    JsonObject(mapOf("kind" to JsonPrimitive(kind.id), "channel" to JsonPrimitive(channel.id))),
                )
            },
            onDismiss = { reporting = null },
        )
    }
}

@Composable
fun LegalLinks(onCommand: (String, JsonObject) -> Unit) {
    val none = JsonObject(emptyMap())
    TextButton(
        onClick = { onCommand(FeedbackCommandIds.PRIVACY_POLICY_OPEN, none) },
    ) { Text(stringResource(R.string.settings_privacy_policy)) }
    TextButton(onClick = { onCommand(FeedbackCommandIds.TERMS_OPEN, none) }) { Text(stringResource(R.string.settings_terms)) }
}

/** GitHub needs an account and makes the report public, so email is always offered next to it. */
@Composable
fun FeedbackChannelDialog(
    kind: FeedbackKind,
    onChoose: (FeedbackChannel) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (kind == FeedbackKind.Bug) R.string.settings_report_bug else R.string.settings_suggest_feature)) },
        text = {
            Column {
                Text(stringResource(R.string.feedback_dialog_body), modifier = Modifier.padding(bottom = 8.dp))
                TextButton(onClick = { onChoose(FeedbackChannel.GitHub) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feedback_dialog_github))
                }
                TextButton(onClick = { onChoose(FeedbackChannel.Email) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.feedback_dialog_email))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
