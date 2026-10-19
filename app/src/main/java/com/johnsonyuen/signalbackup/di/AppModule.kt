package com.johnsonyuen.signalbackup.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.local.db.AppDatabase
import com.johnsonyuen.signalbackup.data.local.db.DatabaseMigrations
import com.johnsonyuen.signalbackup.data.local.db.SettingsDao
import com.johnsonyuen.signalbackup.data.local.db.SyncedFileDao
import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import com.johnsonyuen.signalbackup.data.local.db.UploadSessionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

/** App-wide singletons: Room database and DAOs, WorkManager, Clock. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** No destructive fallback: every historical schema (v1–v4) has a migration path. */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .addCallback(DatabaseMigrations.DEFAULT_SETTINGS_CALLBACK)
            .build()

    @Provides
    fun provideUploadHistoryDao(db: AppDatabase): UploadHistoryDao = db.uploadHistoryDao()

    @Provides
    fun provideSettingsDao(db: AppDatabase): SettingsDao = db.settingsDao()

    @Provides
    fun provideUploadSessionDao(db: AppDatabase): UploadSessionDao = db.uploadSessionDao()

    @Provides
    fun provideSyncedFileDao(db: AppDatabase): SyncedFileDao = db.syncedFileDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
