package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity

@Database(
    entities = [UploadHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadHistoryDao(): UploadHistoryDao
}
