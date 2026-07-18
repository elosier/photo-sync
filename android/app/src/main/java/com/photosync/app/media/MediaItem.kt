package com.photosync.app.media

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A single photo or video discovered on the device. */
data class MediaItem(
    val key: String,          // "img:<id>" / "vid:<id>"
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val takenMillis: Long,    // capture time in device-local epoch millis
    val isVideo: Boolean,
) {
    /** Relative date path in the layout the server expects: YYYY/YYYY_mm_dd. */
    fun relativeDatePath(): String {
        val date = Date(takenMillis)
        val year = SimpleDateFormat("yyyy", Locale.US).format(date)
        val day = SimpleDateFormat("yyyy_MM_dd", Locale.US).format(date)
        return "$year/$day"
    }
}
