package com.photosync.app.sync

import android.content.Context
import android.util.Log
import com.photosync.app.data.AppDatabase
import com.photosync.app.data.FailedItem
import com.photosync.app.data.SettingsStore
import com.photosync.app.data.UploadedItem
import com.photosync.app.media.MediaItem
import com.photosync.app.media.MediaScanner
import com.photosync.app.net.UploadClient

/**
 * Core one-way sync: enumerate local media, skip anything already recorded as
 * uploaded, and push the rest to the server. Shared by the periodic worker,
 * the "Sync now" button and the media-observer service.
 */
class SyncEngine(private val context: Context) {

    data class Summary(
        val uploaded: Int = 0,
        val alreadyThere: Int = 0,
        val skippedPermanent: Int = 0,
        val failedTransient: Int = 0,
        val total: Int = 0,
    ) {
        /** True if something failed transiently and the run should be retried. */
        val needsRetry get() = failedTransient > 0
    }

    private val settings = SettingsStore(context)
    private val dao = AppDatabase.get(context).uploadedDao()
    private val failedDao = AppDatabase.get(context).failedDao()

    suspend fun run(onProgress: (String) -> Unit = {}): Summary {
        if (!settings.isConfigured() || !settings.syncEnabled) {
            return Summary().also {
                settings.lastResult = "Not configured / disabled"
                settings.lastRunMillis = System.currentTimeMillis()
            }
        }

        val device = settings.deviceFolder
        val client = UploadClient(settings.serverBaseUrl, settings.token, context.contentResolver)
        val items = MediaScanner(context).scan(
            includeVideos = true,
            cameraOnly = settings.cameraOnly,
        )

        var uploaded = 0
        var already = 0
        var skipped = 0
        var failed = 0

        for ((index, item) in items.withIndex()) {
            // Already synced (and unchanged size)? Skip without touching network.
            if (dao.findMatching(item.key, item.sizeBytes) != null) {
                already++
                continue
            }
            // Given up on this exact file (repeated permanent rejection)? Skip
            // without hashing. A size change resets the give-up state below.
            val priorFailure = failedDao.get(item.key)
            if (priorFailure != null &&
                priorFailure.permanent &&
                priorFailure.size == item.sizeBytes &&
                priorFailure.attempts >= MAX_PERMANENT_ATTEMPTS
            ) {
                skipped++
                continue
            }
            onProgress("Syncing ${index + 1}/${items.size}: ${item.displayName}")

            val sha = client.computeSha256(item.uri)
            if (sha == null) {
                recordFailure(item, priorFailure, permanent = false, error = "unreadable")
                failed++ // couldn't read the file right now; try again next run
                continue
            }

            // Cheap pre-check to avoid re-sending bytes the server already has.
            if (client.exists(device, item, sha) == true) {
                recordUploaded(item, sha, serverPath = "${device}/${item.relativeDatePath()}/${item.displayName}")
                already++
                continue
            }

            when (val r = client.upload(device, item, sha)) {
                is UploadClient.Result.Stored -> {
                    recordUploaded(item, sha, "${device}/${item.relativeDatePath()}/${item.displayName}")
                    uploaded++
                }
                is UploadClient.Result.AlreadyPresent -> {
                    recordUploaded(item, sha, "${device}/${item.relativeDatePath()}/${item.displayName}")
                    already++
                }
                is UploadClient.Result.Retryable -> {
                    Log.w(TAG, "retryable for ${item.displayName}: ${r.message}")
                    recordFailure(item, priorFailure, permanent = false, error = r.message)
                    failed++
                }
                is UploadClient.Result.Permanent -> {
                    Log.e(TAG, "permanent failure for ${item.displayName}: ${r.message}")
                    recordFailure(item, priorFailure, permanent = true, error = r.message)
                    skipped++
                }
            }
        }

        val summary = Summary(uploaded, already, skipped, failed, items.size)
        settings.lastRunMillis = System.currentTimeMillis()
        settings.lastResult =
            "Uploaded $uploaded, already $already, failed $failed, skipped $skipped (of ${items.size})"
        Log.i(TAG, settings.lastResult)
        return summary
    }

    private suspend fun recordUploaded(item: MediaItem, sha: String, serverPath: String) {
        dao.upsert(
            UploadedItem(
                key = item.key,
                size = item.sizeBytes,
                sha256 = sha,
                serverPath = serverPath,
                uploadedAtMillis = System.currentTimeMillis(),
            )
        )
        failedDao.clear(item.key) // success wipes any failure history
    }

    private suspend fun recordFailure(
        item: MediaItem,
        prior: FailedItem?,
        permanent: Boolean,
        error: String,
    ) {
        // A changed file gets a fresh attempt count; same-size failures accrue.
        val attempts = if (prior != null && prior.size == item.sizeBytes) prior.attempts + 1 else 1
        failedDao.upsert(
            FailedItem(
                key = item.key,
                size = item.sizeBytes,
                attempts = attempts,
                permanent = permanent,
                lastError = error.take(200),
                updatedAtMillis = System.currentTimeMillis(),
            )
        )
    }

    companion object {
        private const val TAG = "SyncEngine"

        /** Permanent (4xx) rejections stop being retried after this many tries. */
        const val MAX_PERMANENT_ATTEMPTS = 3
    }
}
