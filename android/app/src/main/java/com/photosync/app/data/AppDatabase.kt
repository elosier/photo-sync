package com.photosync.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadedItem::class, FailedItem::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadedDao(): UploadedItemDao
    abstract fun failedDao(): FailedItemDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 -> v2: add the `failed` attempts table. Must preserve the
         *  `uploaded` table — destructive migration would re-hash everything. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `failed` (" +
                        "`key` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`permanent` INTEGER NOT NULL, " +
                        "`lastError` TEXT NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "photosync.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
