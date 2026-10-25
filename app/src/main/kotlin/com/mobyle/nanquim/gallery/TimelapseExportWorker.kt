package com.mobyle.nanquim.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mobyle.nanquim.R
import java.io.File

class TimelapseExportWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val segments = inputData.getStringArray(KEY_SEGMENTS)?.map(::File).orEmpty()
        val output = File(inputData.getString(KEY_OUTPUT) ?: return Result.failure())
        val length = TimelapseLength.fromId(inputData.getString(KEY_LENGTH)) ?: TimelapseLength.Full
        val cards = inputData.getStringArray(KEY_CARD_LINES)?.toList().orEmpty()
        runCatching { setForeground(foregroundInfo(0f)) }
        var shownPercent = -1
        val ok =
            withContext(Dispatchers.IO) {
                TimelapseExport(segments, length, cards) { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != shownPercent) {
                        shownPercent = percent
                        setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                        runCatching { notificationManager().notify(NOTIFICATION_ID, notification(progress)) }
                    }
                }.write(output)
            }
        return if (ok) Result.success() else Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f)

    private fun foregroundInfo(progress: Float) = ForegroundInfo(NOTIFICATION_ID, notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notificationManager() = applicationContext.getSystemService(NotificationManager::class.java)

    private fun notification(progress: Float) =
        NotificationCompat
            .Builder(applicationContext, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.timelapse_export_notification))
            .setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).toInt(), false)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun channel(): String {
        val manager = notificationManager()
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.timelapse_export_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return CHANNEL_ID
    }

    companion object {
        const val KEY_SEGMENTS = "segments"
        const val KEY_OUTPUT = "output"
        const val KEY_LENGTH = "length"
        const val KEY_CARD_LINES = "cardLines"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL_ID = "timelapse-export"
        private const val NOTIFICATION_ID = 7301
        private const val PROGRESS_MAX = 100
    }
}
