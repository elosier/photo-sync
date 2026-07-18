package com.photosync.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.photosync.app.data.SettingsStore

/**
 * Re-establishes the periodic schedule and the media-change watch after a
 * reboot or app update. (WorkManager persists jobs, but content-URI triggers
 * need re-arming and this guarantees a clean slate. No foreground service is
 * started here — Android 15 forbids dataSync FGS launches from BOOT_COMPLETED.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val settings = SettingsStore(context)
        if (settings.isConfigured() && settings.syncEnabled) {
            SyncScheduler.schedulePeriodic(context)
            MediaWatchScheduler.schedule(context)
        }
    }
}
