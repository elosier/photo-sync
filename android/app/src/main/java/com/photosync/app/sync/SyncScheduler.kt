package com.photosync.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.photosync.app.data.SettingsStore
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Owns the WorkManager schedule.
 *
 * - A periodic job every 15 minutes (WorkManager's minimum) is the backbone
 *   and satisfies "retry every 15 minutes".
 * - On transient failure the job returns retry() with a 15-minute linear
 *   backoff, so failures are re-attempted even between periodic ticks.
 * - [syncNow] enqueues an expedited one-off run for the "Sync now" button and
 *   the media observer.
 */
object SyncScheduler {
    const val PERIODIC = "photo-sync-periodic"
    const val ONESHOT = "photo-sync-now"

    private fun constraints(context: Context): Constraints {
        val wifiOnly = SettingsStore(context).wifiOnly
        return Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofMinutes(15))
            .addTag(PERIODIC)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            // Keep an existing schedule but refresh constraints if they changed.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofMinutes(15))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(ONESHOT)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
