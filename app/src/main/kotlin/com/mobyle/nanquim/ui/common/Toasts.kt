package com.mobyle.nanquim.ui.common

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mobyle.nanquim.R

/** Shows a toast from a screen; the view models report outcomes and leave the wording and the toast to the screen. */
@Composable
fun rememberToast(): (message: String, long: Boolean) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { message, long -> Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }
}

/** For the feedback commands: no app could open the mail, link or share sheet. */
@Composable
fun rememberOpenFailedNotice(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { { Toast.makeText(context, R.string.feedback_open_failed, Toast.LENGTH_SHORT).show() } }
}
