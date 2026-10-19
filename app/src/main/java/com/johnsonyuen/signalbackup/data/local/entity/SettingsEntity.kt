package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** The single settings row (id = 1) of table `app_settings`, schema v5. */
@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val localFolderUri: String? = null,
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
    @ColumnInfo(defaultValue = "1")
    val scheduleEnabled: Boolean = true,
    val scheduleHour: Int = DEFAULT_SCHEDULE_HOUR,
    val scheduleMinute: Int = DEFAULT_SCHEDULE_MINUTE,
    val googleAccountEmail: String? = null,
    val themeMode: String = DEFAULT_THEME_MODE,
    val wifiOnly: Boolean = false,
) {
    companion object {
        const val SINGLETON_ID = 1
        const val DEFAULT_SCHEDULE_HOUR = 3
        const val DEFAULT_SCHEDULE_MINUTE = 0
        const val DEFAULT_THEME_MODE = "SYSTEM"
    }
}
