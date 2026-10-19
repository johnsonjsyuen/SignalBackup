package com.johnsonyuen.signalbackup.domain.model

/** One-shot snapshot of user settings. */
data class AppSettings(
    val localFolderUri: String?,
    val driveFolderId: String?,
    val driveFolderName: String?,
    val scheduleEnabled: Boolean,
    val scheduleHour: Int,
    val scheduleMinute: Int,
    val googleAccountEmail: String?,
    val themeMode: String,
    val wifiOnly: Boolean,
) {
    val isSetupComplete: Boolean
        get() = localFolderUri != null && googleAccountEmail != null
}
