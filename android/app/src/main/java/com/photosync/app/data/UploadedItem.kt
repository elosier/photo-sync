package com.photosync.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per media item that has been successfully stored on the server.
 *
 * [key] is a stable identifier of the form "img:<id>" or "vid:<id>" (MediaStore
 * ids are only unique within their own collection, so we namespace them).
 * [size] is kept so that an edited file (same id, new size) is re-uploaded.
 */
@Entity(tableName = "uploaded")
data class UploadedItem(
    @PrimaryKey val key: String,
    val size: Long,
    val sha256: String,
    val serverPath: String,
    val uploadedAtMillis: Long,
)
