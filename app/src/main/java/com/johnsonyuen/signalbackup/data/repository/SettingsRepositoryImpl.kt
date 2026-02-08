package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : SettingsRepository {

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

    override suspend fun setGoogleAccountEmail(email: String?) {
        settingsDataStore.setGoogleAccountEmail(email)
    }
}
