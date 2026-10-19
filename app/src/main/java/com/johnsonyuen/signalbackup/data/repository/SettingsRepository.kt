package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/** User settings, stored in the Room `app_settings` row. Flows are distinct-until-changed. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings

    val localFolderUri: Flow<String?>
    val driveFolderId: Flow<String?>
    val driveFolderName: Flow<String?>
    val scheduleEnabled: Flow<Boolean>
    val scheduleHour: Flow<Int>
    val scheduleMinute: Flow<Int>
    val googleAccountEmail: Flow<String?>
    val themeMode: Flow<String>
    val wifiOnly: Flow<Boolean>

    suspend fun setLocalFolderUri(uri: String?)
    suspend fun setDriveFolder(id: String?, name: String?)
    suspend fun setScheduleEnabled(enabled: Boolean)
    suspend fun setScheduleTime(hour: Int, minute: Int)
    suspend fun setGoogleAccountEmail(email: String?)
    suspend fun setThemeMode(mode: String)
    suspend fun setWifiOnly(enabled: Boolean)

    /** Clears the account email and Drive folder. Callers also clear sessions and synced files. */
    suspend fun clearAccountData()
}
