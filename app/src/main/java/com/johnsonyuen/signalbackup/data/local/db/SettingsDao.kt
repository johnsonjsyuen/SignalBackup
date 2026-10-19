package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.johnsonyuen.signalbackup.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    /** Inserts the default row unless one already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: SettingsEntity)

    @Query("UPDATE app_settings SET localFolderUri = :uri WHERE id = 1")
    suspend fun updateLocalFolderUri(uri: String?)

    @Query("UPDATE app_settings SET driveFolderId = :id, driveFolderName = :name WHERE id = 1")
    suspend fun updateDriveFolder(id: String?, name: String?)

    @Query("UPDATE app_settings SET scheduleEnabled = :enabled WHERE id = 1")
    suspend fun updateScheduleEnabled(enabled: Boolean)

    @Query("UPDATE app_settings SET scheduleHour = :hour, scheduleMinute = :minute WHERE id = 1")
    suspend fun updateScheduleTime(hour: Int, minute: Int)

    @Query("UPDATE app_settings SET googleAccountEmail = :email WHERE id = 1")
    suspend fun updateGoogleAccountEmail(email: String?)

    @Query("UPDATE app_settings SET themeMode = :mode WHERE id = 1")
    suspend fun updateThemeMode(mode: String)

    @Query("UPDATE app_settings SET wifiOnly = :enabled WHERE id = 1")
    suspend fun updateWifiOnly(enabled: Boolean)

    @Query("UPDATE app_settings SET googleAccountEmail = NULL, driveFolderId = NULL, driveFolderName = NULL WHERE id = 1")
    suspend fun clearAccountData()
}
