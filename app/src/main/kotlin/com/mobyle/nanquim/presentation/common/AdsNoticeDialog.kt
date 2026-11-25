package com.mobyle.nanquim.presentation.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mobyle.nanquim.R

/** Shown once, before the gallery banner first loads: why there is an ad, where it is, and how to turn it off. */
@Composable
fun AdsNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ads_notice_title)) },
        text = { Text(stringResource(R.string.ads_notice_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ads_notice_ok)) } },
    )
}
