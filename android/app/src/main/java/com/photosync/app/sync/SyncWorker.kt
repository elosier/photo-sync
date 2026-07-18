package com.photosync.app.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters

/**
 * Runs the sync in the background. Enqueued periodically every 15 minutes and
 * on demand. Returns [Result.retry] on transient failure so WorkManager's
 * backoff (also 15 min) re-attempts even outside the periodic cadence.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Notifications.ensureChannel(applicationContext)
        try {
            setForeground(makeForegroundInfo("Checking for new photos…"))
        } catch (_: Exception) {
            // setForeground can fail if the app lacks the FGS permission or is
            // heavily restricted; the sync still runs as a normal worker.
        }

        val summary = SyncEngine(applicationContext).run { text ->
            runCatching { setForegroundAsync(makeForegroundInfo(text)) }
        }

        return if (summary.needsRetry) Result.retry() else Result.success()
    }

    private fun makeForegroundInfo(text: String): ForegroundInfo {
        val notification = Notifications.progress(applicationContext, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Notifications.FOREGROUND_ID, notification)
        }
    }
}
