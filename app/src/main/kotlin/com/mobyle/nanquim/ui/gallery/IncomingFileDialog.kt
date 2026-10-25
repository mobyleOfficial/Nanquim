package com.mobyle.nanquim.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.ImportTarget

@Composable
fun IncomingFileDialog(
    name: String,
    isPackage: Boolean,
    isBrush: Boolean,
    isPalette: Boolean,
    canAddLayer: Boolean,
    onImport: (ImportTarget) -> Unit,
    onImportPalette: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.incoming_title, name)) },
        text = {
            Column {
                if (isPalette) {
                    TextButton(onClick = onImportPalette) { Text(stringResource(R.string.incoming_import_palette)) }
                    return@Column
                }
                if (isBrush) {
                    TextButton(onClick = { onImport(ImportTarget.NewArtwork) }) { Text(stringResource(R.string.incoming_import_brushes)) }
                    return@Column
                }
                TextButton(onClick = { onImport(ImportTarget.NewArtwork) }) { Text(stringResource(R.string.incoming_new_artwork)) }
                if (!isPackage && canAddLayer) {
                    TextButton(onClick = { onImport(ImportTarget.Layer) }) { Text(stringResource(R.string.incoming_add_layer)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) } },
    )
}
