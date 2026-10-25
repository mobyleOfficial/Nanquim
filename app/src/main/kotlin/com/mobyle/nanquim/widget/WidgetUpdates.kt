package com.mobyle.nanquim.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object WidgetUpdates {
    private const val TAG = "WidgetUpdates"
    private const val WORK_NAME = "recent-artwork-widget"
    private const val PREFERENCES = "widgets"
    private const val KEY_LAST_UPDATE = "lastUpdate"
    const val MINIMUM_INTERVAL_MS = 30_000L

    @Volatile
    private var running = false

    fun observe(
        context: Context,
        changes: Flow<*>,
        scope: CoroutineScope,
    ) {
        scope.launch { changes.drop(1).collect { request(context) } }
    }

    fun request(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        if (manager.getAppWidgetIds(ComponentName(context, RecentArtworkWidgetReceiver::class.java)).isEmpty()) return
        val last = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0L)
        val delay = delayBefore(last, System.currentTimeMillis())
        val work =
            OneTimeWorkRequestBuilder<UpdateWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        val policy = if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        Log.d(TAG, "widget update in $delay ms ($policy)")
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, work)
    }

    fun delayBefore(
        lastUpdate: Long,
        now: Long,
    ): Long = (lastUpdate + MINIMUM_INTERVAL_MS - now).coerceIn(0L, MINIMUM_INTERVAL_MS)

    class UpdateWorker(
        context: Context,
        parameters: WorkerParameters,
    ) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            running = true
            try {
                update()
            } finally {
                running = false
            }
            return Result.success()
        }

        private suspend fun update() {
            applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) {
                putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            }
            RecentArtworkWidget().refreshAll(applicationContext)
        }
    }
}
