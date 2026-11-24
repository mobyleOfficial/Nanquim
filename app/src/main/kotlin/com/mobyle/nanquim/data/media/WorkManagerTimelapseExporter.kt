package com.mobyle.nanquim.data.media

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mobyle.nanquim.domain.platform.TimelapseExporter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File

/** Runs the export in [TimelapseExportWorker], so it carries on (with its notification) if the person leaves the app. */
class WorkManagerTimelapseExporter(
    private val workManager: WorkManager,
) : TimelapseExporter {
    override suspend fun export(
        segments: List<File>,
        output: File,
        lengthId: String,
        cardLines: List<String>,
    ): Boolean {
        val request =
            OneTimeWorkRequestBuilder<TimelapseExportWorker>()
                .setInputData(
                    workDataOf(
                        TimelapseExportWorker.KEY_SEGMENTS to segments.map { it.path }.toTypedArray(),
                        TimelapseExportWorker.KEY_OUTPUT to output.path,
                        TimelapseExportWorker.KEY_LENGTH to lengthId,
                        TimelapseExportWorker.KEY_CARD_LINES to cardLines.toTypedArray(),
                    ),
                ).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        workManager.enqueueUniqueWork(EXPORT_WORK, ExistingWorkPolicy.REPLACE, request)
        val finished = workManager.getWorkInfoByIdFlow(request.id).filterNotNull().first { it.state.isFinished }
        return finished.state == WorkInfo.State.SUCCEEDED
    }

    private companion object {
        const val EXPORT_WORK = "timelapse-export"
    }
}
