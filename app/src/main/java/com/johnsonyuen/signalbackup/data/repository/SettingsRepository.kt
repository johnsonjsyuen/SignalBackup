/**
 * SettingsRepository.kt - Repository interface for user settings.
 *
 * This interface defines the contract for reading and writing user settings. It follows
 * the **Repository pattern** from clean architecture, which abstracts the data source
 * behind an interface so that:
 * - The domain and presentation layers do not know about DataStore (or any specific storage).
 * - The data source can be swapped (e.g., for testing) without changing consumers.
 * - All settings access goes through a single, well-defined API.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package), but defines the boundary
 *   that the domain and presentation layers depend on.
 * - Implemented by [SettingsRepositoryImpl], which delegates to [SettingsDataStore].
 * - Bound to its implementation by Hilt in [RepositoryModule] via @Binds.
 * - Consumed by ViewModels (HomeViewModel, SettingsViewModel) and use cases
 *   (PerformUploadUseCase, ScheduleUploadUseCase).
 *
 * Pattern explanation: Flows for reads, suspend functions for writes.
 * - Flows provide reactive, observable access to settings that automatically update the UI.
 * - Suspend functions for writes ensure thread-safe, asynchronous persistence.
 *
 * @see data.repository.SettingsRepositoryImpl for the implementation
 * @see data.local.datastore.SettingsDataStore for the underlying storage
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Contract for accessing user settings.
 *
 * All reactive getters return [Flow] for automatic UI updates.
 * All setters are suspend functions for safe async writes.
 */
interface SettingsRepository {

    // ---- Flow getters (reactive reads) ----

    /** The local backup folder URI (SAF content URI), or null if not configured. */
    val localFolderUri: Flow<String?>

    /** The Google Drive destination folder ID, or null if not configured. */
    val driveFolderId: Flow<String?>

    /** The display name of the selected Drive folder, or null if not configured. */
    val driveFolderName: Flow<String?>

    /** The scheduled upload hour (0-23). */
    val scheduleHour: Flow<Int>

    /** The scheduled upload minute (0-59). */
    val scheduleMinute: Flow<Int>

    /** The signed-in Google account email, or null if not signed in. */
    val googleAccountEmail: Flow<String?>

    /** The theme mode string ("SYSTEM", "LIGHT", or "DARK"). */
    val themeMode: Flow<String>

    // ---- Suspend setters (async writes) ----

    /** Sets or clears the local backup folder URI. */
    suspend fun setLocalFolderUri(uri: String?)

    /** Sets or clears the Google Drive folder ID. */
    suspend fun setDriveFolderId(id: String?)

    /** Sets or clears the Google Drive folder display name. */
    suspend fun setDriveFolderName(name: String?)

    /** Sets the scheduled upload hour. */
    suspend fun setScheduleHour(hour: Int)

    /** Sets the scheduled upload minute. */
    suspend fun setScheduleMinute(minute: Int)

    /** Atomically sets both schedule hour and minute in a single transaction. */
    suspend fun setScheduleTime(hour: Int, minute: Int)

    /** Atomically clears all account-related data (email, Drive folder). */
    suspend fun clearAccountData()

    /** Sets or clears the signed-in Google account email. */
    suspend fun setGoogleAccountEmail(email: String?)

    /** Sets the theme mode preference. */
    suspend fun setThemeMode(mode: String)
}
