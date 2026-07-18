package com.photosync.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.photosync.app.data.SettingsStore
import com.photosync.app.sync.MediaWatchScheduler
import com.photosync.app.sync.SyncScheduler

class PhotoSyncApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Make sure the 15-minute backbone is scheduled whenever the app runs
        // and the user has already configured + enabled sync.
        val settings = SettingsStore(this)
        if (settings.isConfigured() && settings.syncEnabled) {
            SyncScheduler.schedulePeriodic(this)
            MediaWatchScheduler.schedule(this)
        }
    }
}
