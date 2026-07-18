package com.photosync.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadedItemDao {

    @Query("SELECT key FROM uploaded WHERE key = :key AND size = :size LIMIT 1")
    suspend fun findMatching(key: String, size: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: UploadedItem)

    @Query("SELECT COUNT(*) FROM uploaded")
    suspend fun count(): Int

    /** Live count that emits whenever the table changes — drives the UI. */
    @Query("SELECT COUNT(*) FROM uploaded")
    fun countFlow(): Flow<Int>
}
