package com.mobyle.nanquim.data.platform

import android.content.ContentResolver
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.mobyle.nanquim.domain.platform.ExternalFiles
import java.io.InputStream
import java.io.OutputStream

/** [ExternalFiles] on Android: content:// URIs only, read and written through the [ContentResolver]. */
class ContentResolverFiles(
    private val contentResolver: ContentResolver,
) : ExternalFiles {
    override fun accepts(uri: String): Boolean = uri.toUri().scheme == ContentResolver.SCHEME_CONTENT

    override fun openInput(uri: String): InputStream? = contentResolver.openInputStream(uri.toUri())

    override fun openOutput(uri: String): OutputStream? = contentResolver.openOutputStream(uri.toUri(), "wt")

    override fun displayName(uri: String): String? =
        contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
