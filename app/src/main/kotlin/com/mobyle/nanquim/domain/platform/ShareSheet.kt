package com.mobyle.nanquim.domain.platform

import java.io.File

/** Hands files from the app's cache to whatever the platform offers for sharing them. */
fun interface ShareSheet {
    fun share(
        files: List<File>,
        mimeType: String,
    )
}
