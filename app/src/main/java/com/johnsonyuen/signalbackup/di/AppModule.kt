/**
 * AppModule.kt - Hilt module providing core app-wide singleton dependencies.
 *
 * This module provides the fundamental infrastructure objects that the rest of the app
 * depends on: the Room database, DataStore, SettingsDataStore wrapper, and WorkManager.
 *
 * Hilt concepts used:
 * - **@Module**: Marks this as a Dagger/Hilt module that contributes bindings to the DI graph.
 * - **@InstallIn(SingletonComponent::class)**: These bindings live for the entire app lifetime.
 * - **@Provides**: Used to create objects that Hilt cannot construct automatically (e.g.,
 *   Room databases require Builder calls, DataStore uses a delegate pattern).
 * - **@Singleton**: Ensures only one instance is created and shared across the app.
 * - **@ApplicationContext**: Injects the Application context (not an Activity context),
 *   which is safe to hold as a singleton because it outlives all Activities.
 *
 * Important: The DataStore delegate `by preferencesDataStore(name = "settings")` is defined
 * at the file level (outside the module) because DataStore MUST have exactly one instance
 * per file name -- creating multiple DataStore instances for the same file causes corruption.
 *
 * @see di.DriveModule for Google Drive-related providers
 * @see di.RepositoryModule for repository interface bindings
 */
package com.johnsonyuen.signalbackup.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.local.db.AppDatabase
import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * File-level DataStore delegate. This creates the "settings" preferences DataStore file.
 * MUST be defined once at the file level -- defining it inside a class or function would
 * create multiple instances, which DataStore explicitly forbids.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Hilt module that provides core singleton dependencies.
 *
 * Uses `object` (not `class`) because all methods are static @Provides functions.
 * Hilt generates optimized code for object modules.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the Room database singleton.
     *
     * The database file is named "signal_backup_db" and stored in the app's private directory.
     * fallbackToDestructiveMigration() means if the schema version changes and no migration
     * is provided, Room will drop all tables and recreate them (acceptable during development).
     */
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "signal_backup_db")
            .fallbackToDestructiveMigration()
            .build()

    /**
     * Provides the UploadHistoryDao from the database.
     *
     * Not @Singleton because DAO instances are lightweight and tied to the database.
     * The database itself is the singleton.
     */
    @Provides
    fun provideUploadHistoryDao(db: AppDatabase): UploadHistoryDao = db.uploadHistoryDao()

    /**
     * Provides the raw Preferences DataStore instance.
     *
     * Uses the file-level delegate property to ensure exactly one DataStore instance exists.
     */
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    /**
     * Provides the SettingsDataStore wrapper.
     *
     * Manually constructs SettingsDataStore rather than using @Inject on its constructor.
     * This avoids a Hilt duplicate binding error (you cannot have both @Inject constructor
     * and @Provides for the same type).
     */
    @Provides
    @Singleton
    fun provideSettingsDataStore(dataStore: DataStore<Preferences>): SettingsDataStore =
        SettingsDataStore(dataStore)

    /**
     * Provides the WorkManager singleton instance.
     *
     * WorkManager is initialized by our custom SignalBackupApp.workManagerConfiguration
     * (not by the default initializer, which we disable in AndroidManifest.xml).
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
