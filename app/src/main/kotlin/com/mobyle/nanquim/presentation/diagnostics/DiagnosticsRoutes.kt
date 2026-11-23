package com.mobyle.nanquim.presentation.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.mobyle.nanquim.R
import com.mobyle.nanquim.document.command.CommandResult
import com.mobyle.nanquim.presentation.common.rememberToast
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel = koinViewModel()) {
    val exported = rememberExportNotice(R.string.diagnostics_export_done)
    DiagnosticsScreen(
        collector = viewModel.collector,
        recorder = viewModel.recorder,
        onBack = viewModel::back,
        onOpenLatencyTest = viewModel::openLatencyTest,
        onOpenShapeTraces = viewModel::openShapeTraces,
        onOpenTouchSessions = viewModel::openTouchSessions,
        selectedRenderer = viewModel.selectedRenderer,
        activeRenderer = viewModel.activeRenderer,
        rendererFellBack = viewModel.rendererFellBack,
        onSelectRenderer = viewModel::selectRenderer,
        onExport = { uri -> viewModel.export(uri, exported) },
    )
}

@Composable
fun LatencyTestRoute(viewModel: LatencyTestViewModel = koinViewModel()) {
    LatencyTestScreen(engine = viewModel.engine, onBack = viewModel::back)
}

@Composable
fun ShapeTracesRoute(viewModel: ShapeTracesViewModel = koinViewModel()) {
    val exported = rememberExportNotice(R.string.shape_traces_export_done)
    ShapeTraceScreen(
        recorder = viewModel.recorder,
        onBack = viewModel::back,
        onRemoveLast = viewModel::removeLast,
        onClear = viewModel::clear,
        onExport = { uri -> viewModel.export(uri, exported) },
    )
}

@Composable
fun TouchSessionsRoute(viewModel: TouchSessionsViewModel = koinViewModel()) {
    val exported = rememberExportNotice(R.string.touch_sessions_export_done)
    TouchSessionScreen(
        recorder = viewModel.recorder,
        xdpi = viewModel.xdpi,
        onBack = viewModel::back,
        onRemoveLast = viewModel::removeLast,
        onClear = viewModel::clear,
        onExport = { uri -> viewModel.export(uri, exported) },
    )
}

/** Says whether an export finished, with [doneMessage] or the shared failure message. */
@Composable
private fun rememberExportNotice(
    @StringRes doneMessage: Int,
): (CommandResult) -> Unit {
    val resources = LocalResources.current
    val toast = rememberToast()
    return { result ->
        val message =
            when (result) {
                is CommandResult.Success -> resources.getString(doneMessage)
                is CommandResult.Failure -> resources.getString(R.string.diagnostics_export_failed, result.message)
            }
        toast(message, false)
    }
}
