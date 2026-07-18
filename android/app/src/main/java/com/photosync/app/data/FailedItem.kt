package com.photosync.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One row per media item whose last upload attempt failed.
 *
 * Purpose: stop permanently-rejected files (4xx from the server) from being
 * re-hashed and re-sent every 15 minutes forever. Transient failures are also
 * recorded (for diagnostics) but never gate a retry — connection problems must
 * keep retrying per the project requirement. If the file changes ([size]
 * differs), the give-up state is ignored and it gets fresh attempts.
 */
@Entity(tableName = "failed")
data class FailedItem(
    @PrimaryKey val key: String,
    val size: Long,
    val attempts: Int,
    val permanent: Boolean,
    val lastError: String,
    val updatedAtMillis: Long,
)

@Dao
interface FailedItemDao {

    @Query("SELECT * FROM failed WHERE key = :key LIMIT 1")
    suspend fun get(key: String): FailedItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FailedItem)

    @Query("DELETE FROM failed WHERE key = :key")
    suspend fun clear(key: String)

    @Query("SELECT COUNT(*) FROM failed WHERE permanent = 1 AND attempts >= :maxAttempts")
    suspend fun givenUpCount(maxAttempts: Int): Int
}
