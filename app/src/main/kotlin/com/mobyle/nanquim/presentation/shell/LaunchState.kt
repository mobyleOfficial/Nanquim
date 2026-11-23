package com.mobyle.nanquim.presentation.shell

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the process remembers about how it was launched, across the activities it creates. */
class LaunchState {
    var appStartReported = false

    /** Set by the first activity of the process once it has opened or restored what the launch asked for. */
    var launchHandled = false

    /** A file another app asked us to open, waiting for the person to choose what to do with it. */
    var incomingFile: Uri? by mutableStateOf(null)
}
