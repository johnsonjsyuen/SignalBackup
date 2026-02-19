package com.johnsonyuen.signalbackup.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.local.db.AppDatabase
import com.johnsonyuen.signalbackup.data.local.db.SettingsDao
import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val CREATE_SETTINGS_SQL = """
        CREATE TABLE IF NOT EXISTS app_settings (
            id INTEGER NOT NULL PRIMARY KEY DEFAULT 1,
            localFolderUri TEXT,
            driveFolderId TEXT,
            driveFolderName TEXT,
            scheduleHour INTEGER NOT NULL DEFAULT 3,
            scheduleMinute INTEGER NOT NULL DEFAULT 0,
            googleAccountEmail TEXT,
            themeMode TEXT NOT NULL DEFAULT 'SYSTEM',
            wifiOnly INTEGER NOT NULL DEFAULT 0,
            resumeSessionUri TEXT,
            resumeLocalFileUri TEXT,
            resumeFileName TEXT,
            resumeBytesUploaded INTEGER,
            resumeTotalBytes INTEGER,
            resumeDriveFolderId TEXT,
            resumeCreatedAt INTEGER,
            resumeDriveFileId TEXT
        )
    """

    private const val INSERT_DEFAULT_SETTINGS_SQL =
        "INSERT OR IGNORE INTO app_settings (id, scheduleHour, scheduleMinute, themeMode, wifiOnly) VALUES (1, 3, 0, 'SYSTEM', 0)"

    // v1→v2 had no schema change (version was bumped without a migration,
    // previously relied on fallbackToDestructiveMigration)
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { /* no-op */ }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(CREATE_SETTINGS_SQL)
            db.execSQL(INSERT_DEFAULT_SETTINGS_SQL)
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "signal_backup_db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL(INSERT_DEFAULT_SETTINGS_SQL)
                    val cursor = db.query("SELECT * FROM app_settings WHERE id = 1")
                    Log.d("AppModule", "onOpen: app_settings row count=${cursor.count}")
                    cursor.close()
                }
            })
            .build()

    @Provides
    fun provideUploadHistoryDao(db: AppDatabase): UploadHistoryDao = db.uploadHistoryDao()

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
