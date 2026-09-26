package com.gravarty.htsp.provider

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gravarty.htsp.core.HtspSettings
import java.util.concurrent.TimeUnit

/**
 * Background sync. pvr.hts keeps its HTSP connection open inside Kodi and receives
 * changes live; an Android app is not kept running, so the same full sync runs
 * periodically instead. WorkManager keeps the schedule across reboots.
 */
class HtspSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = HtspServerSync.loadSettings(applicationContext)
        if (settings.host.isEmpty()) return Result.success()

        return when (HtspServerSync.run(applicationContext, settings)) {
            HtspServerSync.Result.OK -> Result.success()
            HtspServerSync.Result.NO_CHANNELS -> Result.success() // keep existing channels
            else -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "htsp_background_sync"
        private const val INTERVAL_HOURS = 1L

        /** One sync right now, e.g. after the EPG or connection settings changed. */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<HtspSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("htsp_sync_now", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HtspSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setInitialDelay(INTERVAL_HOURS, TimeUnit.HOURS) // setup just synced
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
