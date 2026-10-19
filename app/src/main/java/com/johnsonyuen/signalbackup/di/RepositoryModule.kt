package com.johnsonyuen.signalbackup.di

import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepositoryImpl
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepositoryImpl
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepositoryImpl
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the Room-backed repositories. DriveRepository is bound in DriveModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindUploadHistoryRepository(impl: UploadHistoryRepositoryImpl): UploadHistoryRepository

    @Binds
    @Singleton
    abstract fun bindUploadSessionRepository(impl: UploadSessionRepositoryImpl): UploadSessionRepository

    @Binds
    @Singleton
    abstract fun bindSyncedFileRepository(impl: SyncedFileRepositoryImpl): SyncedFileRepository
}
