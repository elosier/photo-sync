package com.photosync.app.media

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore

/**
 * Enumerates images and videos visible to the app via MediaStore.
 *
 * We read DATE_TAKEN (milliseconds) where available and fall back to
 * DATE_ADDED (seconds) so the on-server date folder reflects when the photo
 * was actually captured.
 */
class MediaScanner(private val context: Context) {

    fun scan(includeVideos: Boolean = true, cameraOnly: Boolean = false): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
            cameraOnly = cameraOnly,
            out = items,
        )
        if (includeVideos) {
            queryCollection(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                isVideo = true,
                cameraOnly = cameraOnly,
                out = items,
            )
        }
        return items
    }

    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        cameraOnly: Boolean,
        out: MutableList<MediaItem>,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        // Camera-only: restrict to the camera roll (DCIM/...) but exclude
        // screenshot folders — Samsung stores screenshots in DCIM/Screenshots.
        val selection = if (cameraOnly) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?"
        } else null
        val selectionArgs = if (cameraOnly) arrayOf("DCIM/%", "%Screenshots%") else null

        val cursor: Cursor? = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )
        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val takenCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)

            val prefix = if (isVideo) "vid" else "img"
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "$prefix$id"
                val size = c.getLong(sizeCol)
                if (size <= 0L) continue // skip pending/empty entries

                val takenMs = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol) else 0L
                val addedS = c.getLong(addedCol)
                val whenMs = if (takenMs > 0L) takenMs else addedS * 1000L

                out.add(
                    MediaItem(
                        key = "$prefix:$id",
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = name,
                        sizeBytes = size,
                        takenMillis = whenMs,
                        isVideo = isVideo,
                    )
                )
            }
        }
    }
}
