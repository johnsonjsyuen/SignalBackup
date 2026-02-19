package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,

    // App settings
    val localFolderUri: String? = null,
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
    val scheduleHour: Int = DEFAULT_SCHEDULE_HOUR,
    val scheduleMinute: Int = DEFAULT_SCHEDULE_MINUTE,
    val googleAccountEmail: String? = null,
    val themeMode: String = "SYSTEM",
    val wifiOnly: Boolean = false,

    // Resumable upload session
    val resumeSessionUri: String? = null,
    val resumeLocalFileUri: String? = null,
    val resumeFileName: String? = null,
    val resumeBytesUploaded: Long? = null,
    val resumeTotalBytes: Long? = null,
    val resumeDriveFolderId: String? = null,
    val resumeCreatedAt: Long? = null,
    val resumeDriveFileId: String? = null,
) {
    companion object {
        const val DEFAULT_SCHEDULE_HOUR = 3
        const val DEFAULT_SCHEDULE_MINUTE = 0
    }
}
