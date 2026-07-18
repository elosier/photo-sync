package com.photosync.app.sync

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photosync.app.data.SettingsStore
import java.time.Duration

/**
 * Near-real-time "on the fly" trigger, implemented as a one-shot WorkManager
 * job whose only constraint is "the MediaStore images/videos tables changed".
 * When it fires it enqueues a sync and re-arms itself.
 *
 * This replaces the previous always-on foreground observer service, which is
 * not viable on Android 15: dataSync foreground services are capped at ~6 h
 * per day and may not be started from BOOT_COMPLETED at all. Content-triggered
 * work has none of those restrictions, needs no persistent notification, and
 * survives reboots via [BootReceiver]. The 15-minute periodic worker remains
 * the safety net if a trigger is ever missed.
 */
object MediaWatchScheduler {
    const val WATCH = "photo-sync-watch"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            // Debounce bursts (e.g. burst-mode shots) into one firing, but
            // never wait more than 2 minutes once something changed.
            .setTriggerContentUpdateDelay(Duration.ofSeconds(10))
            .setTriggerContentMaxDelay(Duration.ofMinutes(2))
            .build()
        val request = OneTimeWorkRequestBuilder<MediaWatchWorker>()
            .setConstraints(constraints)
            .build()
        // REPLACE resets the trigger window each time we (re-)arm.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WATCH, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WATCH)
    }
}

/** Fires when new media appears: kick a sync, then re-arm the watch. */
class MediaWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (SettingsStore(applicationContext).syncEnabled) {
            SyncScheduler.syncNow(applicationContext)
            MediaWatchScheduler.schedule(applicationContext)
        }
        return Result.success()
    }
}
