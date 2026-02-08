/**
 * RepositoryModule.kt - Hilt module that binds repository interfaces to their implementations.
 *
 * This module tells Hilt which concrete class to use when a repository interface is
 * requested as a dependency. It uses @Binds (instead of @Provides) because the implementations
 * already have @Inject constructors -- Hilt just needs to know the interface-to-impl mapping.
 *
 * Why @Binds instead of @Provides?
 * - @Binds is more efficient: it generates less code because it just tells Dagger
 *   "when someone asks for SettingsRepository, give them SettingsRepositoryImpl."
 * - @Provides is needed when you have to call a constructor or factory method yourself.
 * - @Binds requires the module to be abstract (not object), and each method must be abstract.
 *
 * This is the core of the **dependency inversion principle** in clean architecture:
 * ViewModels and use cases depend on interfaces (SettingsRepository, DriveRepository, etc.),
 * and this module tells Hilt which implementations to inject at runtime.
 *
 * Architecture context:
 * - Part of the **DI layer** (di package).
 * - All bindings are singletons -- one instance of each repository shared app-wide.
 *
 * @see data.repository.SettingsRepository and SettingsRepositoryImpl
 * @see data.repository.UploadHistoryRepository and UploadHistoryRepositoryImpl
 * @see data.repository.DriveRepository and DriveRepositoryImpl
 */
package com.johnsonyuen.signalbackup.di

import com.johnsonyuen.signalbackup.data.repository.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Abstract Hilt module for repository interface bindings.
 *
 * Must be abstract (not object) because @Binds methods must be abstract.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /** Binds [SettingsRepositoryImpl] as the implementation of [SettingsRepository]. */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    /** Binds [UploadHistoryRepositoryImpl] as the implementation of [UploadHistoryRepository]. */
    @Binds
    @Singleton
    abstract fun bindUploadHistoryRepository(impl: UploadHistoryRepositoryImpl): UploadHistoryRepository

    /** Binds [DriveRepositoryImpl] as the implementation of [DriveRepository]. */
    @Binds
    @Singleton
    abstract fun bindDriveRepository(impl: DriveRepositoryImpl): DriveRepository
}
