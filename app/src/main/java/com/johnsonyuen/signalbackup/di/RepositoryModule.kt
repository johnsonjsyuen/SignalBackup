package com.johnsonyuen.signalbackup.di

import com.johnsonyuen.signalbackup.data.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

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
    abstract fun bindDriveRepository(impl: DriveRepositoryImpl): DriveRepository
}
