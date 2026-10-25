package com.mobyle.nanquim.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mobyle.nanquim.R
import com.mobyle.nanquim.gallery.TransferReport
import com.mobyle.nanquim.formats.interchange.ImportWarning

@Composable
fun TransferReportDialog(
    report: TransferReport,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (report.export) R.string.export_report_title else R.string.import_report_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(report.warnings) { warning -> Text(if (report.export) exportWarningText(warning) else importWarningText(warning)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_report_ok)) } },
    )
}

@Composable
private fun importWarningText(warning: ImportWarning): String =
    when (warning.code) {
        "colorProfileIgnored" -> stringResource(R.string.import_warning_colorProfileIgnored)
        "cmykConverted" -> stringResource(R.string.import_warning_cmykConverted)
        "cmykCanvas" -> stringResource(R.string.import_warning_cmykCanvas, warning.detail)
        "adjustmentLayerSkipped" -> stringResource(R.string.import_warning_adjustmentLayerSkipped, warning.detail)
        "fillLayerSkipped" -> stringResource(R.string.import_warning_fillLayerSkipped, warning.detail)
        "layerEffectsDropped" -> stringResource(R.string.import_warning_layerEffectsDropped, warning.detail)
        "vectorMaskDropped" -> stringResource(R.string.import_warning_vectorMaskDropped, warning.detail)
        "textRasterized" -> stringResource(R.string.import_warning_textRasterized, warning.detail)
        "smartObjectRasterized" -> stringResource(R.string.import_warning_smartObjectRasterized, warning.detail)
        "blendModeReplaced" -> stringResource(R.string.import_warning_blendModeReplaced, warning.detail)
        "groupStructureRepaired" -> stringResource(R.string.import_warning_groupStructureRepaired)
        "mergedImageUsed" -> stringResource(R.string.import_warning_mergedImageUsed)
        "orientationIgnored" -> stringResource(R.string.import_warning_orientationIgnored)
        "layerMaskSkipped" -> stringResource(R.string.import_warning_layerMaskSkipped, warning.detail)
        "layerTypeUnknown" -> stringResource(R.string.import_warning_layerTypeUnknown, warning.detail)
        "provisionalAlphaLock" -> stringResource(R.string.import_warning_provisionalAlphaLock, warning.detail)
        "provisionalLayerMask" -> stringResource(R.string.import_warning_provisionalLayerMask, warning.detail)
        "provisionalOrientation" -> stringResource(R.string.import_warning_provisionalOrientation)
        "groupClippingIgnored" -> stringResource(R.string.import_warning_groupClippingIgnored, warning.detail)
        "tileUnreadable" -> stringResource(R.string.import_warning_tileUnreadable, warning.detail)
        "provisionalBrushMapping" -> stringResource(R.string.import_warning_provisionalBrushMapping, warning.detail)
        "brushShapeReplaced" -> stringResource(R.string.import_warning_brushShapeReplaced, warning.detail)
        "brushBlendModeIgnored" -> stringResource(R.string.import_warning_brushBlendModeIgnored, warning.detail)
        "brushWetMixIgnored" -> stringResource(R.string.import_warning_brushWetMixIgnored, warning.detail)
        "brushStampIgnored" -> stringResource(R.string.import_warning_brushStampIgnored, warning.detail)
        "extraImagesIgnored" -> stringResource(R.string.import_warning_extraImagesIgnored)
        "approximateCmyk" -> stringResource(R.string.import_warning_approximateCmyk)
        "cmykByProfile" -> stringResource(R.string.import_warning_cmykByProfile)
        "approximateLab" -> stringResource(R.string.import_warning_approximateLab)
        "unsupportedColorSpace" -> stringResource(R.string.import_warning_unsupportedColorSpace, warning.detail)
        "tooManySwatches" -> stringResource(R.string.import_warning_tooManySwatches, warning.detail)
        else -> stringResource(R.string.import_warning_unknown, warning.code)
    }

@Composable
private fun exportWarningText(warning: ImportWarning): String =
    when (warning.code) {
        "blendModeReplaced" -> stringResource(R.string.export_warning_blendModeReplaced, warning.detail)
        "tileUnreadable" -> stringResource(R.string.export_warning_tileUnreadable, warning.detail)
        else -> stringResource(R.string.import_warning_unknown, warning.code)
    }
