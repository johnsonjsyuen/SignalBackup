/**
 * SettingsRepositoryImpl.kt - Implementation of [SettingsRepository] backed by DataStore.
 *
 * This class implements the [SettingsRepository] interface by delegating all operations
 * to [SettingsDataStore]. It serves as a thin wrapper that bridges the repository
 * interface (used by the domain/presentation layers) with the DataStore implementation.
 *
 * Why have this wrapper instead of using SettingsDataStore directly?
 * - **Testability**: ViewModels and use cases depend on the SettingsRepository interface,
 *   which can be easily mocked in unit tests without needing a real DataStore.
 * - **Flexibility**: If the storage mechanism changes (e.g., from DataStore to Room),
 *   only this implementation needs to change -- all consumers remain untouched.
 * - **Clean Architecture**: The domain layer should not know about Android-specific
 *   implementations like DataStore.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package).
 * - Uses [@Inject] constructor for Hilt to create instances.
 * - Bound to [SettingsRepository] interface via @Binds in [RepositoryModule].
 * - Singleton scope (one instance shared across the entire app).
 *
 * @see data.repository.SettingsRepository for the interface contract
 * @see data.local.datastore.SettingsDataStore for the underlying DataStore wrapper
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Concrete implementation of [SettingsRepository] that delegates to [SettingsDataStore].
 *
 * The [@Inject] annotation on the constructor tells Hilt how to create this class.
 * Hilt will automatically provide the [SettingsDataStore] dependency (which it gets
 * from AppModule.provideSettingsDataStore()).
 *
 * @param settingsDataStore The DataStore wrapper to delegate all operations to.
 */
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : SettingsRepository {

    // Each property and method simply delegates to the corresponding SettingsDataStore
    // member. This thin delegation pattern is common in clean architecture -- it may
    // seem like boilerplate, but it provides the interface abstraction needed for
    // testing and loose coupling.

    override val localFolderUri: Flow<String?>
        get() = settingsDataStore.localFolderUri

    override val driveFolderId: Flow<String?>
        get() = settingsDataStore.driveFolderId

    override val driveFolderName: Flow<String?>
        get() = settingsDataStore.driveFolderName

    override val scheduleHour: Flow<Int>
        get() = settingsDataStore.scheduleHour

    override val scheduleMinute: Flow<Int>
        get() = settingsDataStore.scheduleMinute

    override val googleAccountEmail: Flow<String?>
        get() = settingsDataStore.googleAccountEmail

    override val themeMode: Flow<String>
        get() = settingsDataStore.themeMode

    override suspend fun setLocalFolderUri(uri: String?) {
        settingsDataStore.setLocalFolderUri(uri)
    }

    override suspend fun setDriveFolderId(id: String?) {
        settingsDataStore.setDriveFolderId(id)
    }

    override suspend fun setDriveFolderName(name: String?) {
        settingsDataStore.setDriveFolderName(name)
    }

    override suspend fun setScheduleHour(hour: Int) {
        settingsDataStore.setScheduleHour(hour)
    }

    override suspend fun setScheduleMinute(minute: Int) {
        settingsDataStore.setScheduleMinute(minute)
    }

    override suspend fun setScheduleTime(hour: Int, minute: Int) {
        settingsDataStore.setScheduleTime(hour, minute)
    }

    override suspend fun clearAccountData() {
        settingsDataStore.clearAccountData()
    }

    override suspend fun setGoogleAccountEmail(email: String?) {
        settingsDataStore.setGoogleAccountEmail(email)
    }

    override suspend fun setThemeMode(mode: String) {
        settingsDataStore.setThemeMode(mode)
    }

    // ---- Resumable upload session delegation ----

    override suspend fun getResumableSession(): ResumableUploadSession? {
        return settingsDataStore.getResumableSession()
    }

    override suspend fun saveResumableSession(session: ResumableUploadSession) {
        settingsDataStore.saveResumableSession(session)
    }

    override suspend fun updateResumableBytesUploaded(bytesUploaded: Long) {
        settingsDataStore.updateResumableBytesUploaded(bytesUploaded)
    }

    override suspend fun clearResumableSession() {
        settingsDataStore.clearResumableSession()
    }
}
