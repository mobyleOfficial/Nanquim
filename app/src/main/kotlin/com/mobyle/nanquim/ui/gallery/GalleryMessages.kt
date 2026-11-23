package com.mobyle.nanquim.ui.gallery

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.GalleryError
import com.mobyle.nanquim.ui.common.rememberToast

/** What to tell the person when a gallery or transfer command fails, from the error code its message starts with. */
fun galleryFailureMessage(
    resources: Resources,
    message: String,
): String {
    val error = GalleryError.entries.firstOrNull { message.startsWith(it.name + ":") }
    return when (error) {
        GalleryError.NotFound -> resources.getString(R.string.gallery_error_not_found)
        GalleryError.NeedsNewerApp -> resources.getString(R.string.gallery_error_newer_app)
        GalleryError.Unsupported -> resources.getString(R.string.gallery_error_unsupported)
        GalleryError.Unreadable -> resources.getString(R.string.gallery_error_unreadable)
        GalleryError.WriteFailed -> resources.getString(R.string.gallery_error_write)
        GalleryError.TooLarge -> resources.getString(R.string.gallery_error_too_large)
        null -> resources.getString(R.string.gallery_error_generic, message)
    }
}

/** Shows a failed gallery command's message as a long toast. */
@Composable
fun rememberGalleryFailureNotice(): (String) -> Unit {
    val resources = LocalResources.current
    val toast = rememberToast()
    return { message -> toast(galleryFailureMessage(resources, message), true) }
}
