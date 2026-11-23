package com.mobyle.nanquim.data.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mobyle.nanquim.domain.platform.ShareSheet
import java.io.File

/** The system share sheet, given the files through the app's FileProvider. */
class AndroidShareSheet(
    private val context: Context,
) : ShareSheet {
    override fun share(
        files: List<File>,
        mimeType: String,
    ) {
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
        val intent =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }.apply {
                type = mimeType
                clipData = ClipData.newRawUri(null, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
}
