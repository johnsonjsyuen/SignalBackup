package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.johnsonyuen.signalbackup.data.local.entity.SettingsEntity
import com.johnsonyuen.signalbackup.data.local.entity.SyncedFileEntity
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.local.entity.UploadSessionEntity

/** Room database (schema v5). Migrations from v1–v4 live in [DatabaseMigrations]. */
@Database(
    entities = [
        UploadHistoryEntity::class,
        SettingsEntity::class,
        UploadSessionEntity::class,
        SyncedFileEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadHistoryDao(): UploadHistoryDao
    abstract fun settingsDao(): SettingsDao
    abstract fun uploadSessionDao(): UploadSessionDao
    abstract fun syncedFileDao(): SyncedFileDao

    companion object {
        const val VERSION = 5
        const val NAME = "signal_backup_db"
    }
}
