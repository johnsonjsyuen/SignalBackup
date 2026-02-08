package com.johnsonyuen.signalbackup.data.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {

    // Flow getters
    val localFolderUri: Flow<String?>
    val driveFolderId: Flow<String?>
    val driveFolderName: Flow<String?>
    val scheduleHour: Flow<Int>
    val scheduleMinute: Flow<Int>
    val googleAccountEmail: Flow<String?>

    // Suspend setters
    suspend fun setLocalFolderUri(uri: String?)
    suspend fun setDriveFolderId(id: String?)
    suspend fun setDriveFolderName(name: String?)
    suspend fun setScheduleHour(hour: Int)
    suspend fun setScheduleMinute(minute: Int)
    suspend fun setGoogleAccountEmail(email: String?)
}
