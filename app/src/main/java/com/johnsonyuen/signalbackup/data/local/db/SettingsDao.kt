package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.johnsonyuen.signalbackup.data.local.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun getSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Upsert
    suspend fun upsert(entity: SettingsEntity)

    @Query("UPDATE app_settings SET localFolderUri = :uri WHERE id = 1")
    suspend fun updateLocalFolderUri(uri: String?)

    @Query("UPDATE app_settings SET driveFolderId = :id WHERE id = 1")
    suspend fun updateDriveFolderId(id: String?)

    @Query("UPDATE app_settings SET driveFolderName = :name WHERE id = 1")
    suspend fun updateDriveFolderName(name: String?)

    @Query("UPDATE app_settings SET scheduleHour = :hour WHERE id = 1")
    suspend fun updateScheduleHour(hour: Int)

    @Query("UPDATE app_settings SET scheduleMinute = :minute WHERE id = 1")
    suspend fun updateScheduleMinute(minute: Int)

    @Query("UPDATE app_settings SET scheduleHour = :hour, scheduleMinute = :minute WHERE id = 1")
    suspend fun updateScheduleTime(hour: Int, minute: Int)

    @Query("UPDATE app_settings SET googleAccountEmail = NULL, driveFolderId = NULL, driveFolderName = NULL WHERE id = 1")
    suspend fun clearAccountData()

    @Query("UPDATE app_settings SET googleAccountEmail = :email WHERE id = 1")
    suspend fun updateGoogleAccountEmail(email: String?)

    @Query("UPDATE app_settings SET themeMode = :mode WHERE id = 1")
    suspend fun updateThemeMode(mode: String)

    @Query("UPDATE app_settings SET wifiOnly = :enabled WHERE id = 1")
    suspend fun updateWifiOnly(enabled: Boolean)

    @Query(
        """UPDATE app_settings SET
            resumeSessionUri = :sessionUri,
            resumeLocalFileUri = :localFileUri,
            resumeFileName = :fileName,
            resumeBytesUploaded = :bytesUploaded,
            resumeTotalBytes = :totalBytes,
            resumeDriveFolderId = :driveFolderId,
            resumeCreatedAt = :createdAt,
            resumeDriveFileId = :driveFileId
        WHERE id = 1"""
    )
    suspend fun saveResumableSession(
        sessionUri: String,
        localFileUri: String,
        fileName: String,
        bytesUploaded: Long,
        totalBytes: Long,
        driveFolderId: String,
        createdAt: Long,
        driveFileId: String?,
    )

    @Query("UPDATE app_settings SET resumeBytesUploaded = :bytesUploaded WHERE id = 1")
    suspend fun updateResumableBytesUploaded(bytesUploaded: Long)

    @Query("UPDATE app_settings SET resumeDriveFileId = :driveFileId WHERE id = 1")
    suspend fun updateResumableDriveFileId(driveFileId: String)

    @Query(
        """UPDATE app_settings SET
            resumeSessionUri = NULL,
            resumeLocalFileUri = NULL,
            resumeFileName = NULL,
            resumeBytesUploaded = NULL,
            resumeTotalBytes = NULL,
            resumeDriveFolderId = NULL,
            resumeCreatedAt = NULL,
            resumeDriveFileId = NULL
        WHERE id = 1"""
    )
    suspend fun clearResumableSession()
}
