package com.johnsonyuen.signalbackup.ui.screen.settings

import android.app.PendingIntent
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.ui.common.UiText

/** Everything the Settings screen renders. */
data class SettingsUiState(
    val localFolderSet: Boolean = false,
    val localFolderName: String? = null,
    val localFolderAccessible: Boolean = true,
    val localFolderHasBackups: Boolean? = null,
    val driveFolderName: String? = null,
    val scheduleEnabled: Boolean = true,
    val scheduleHour: Int = 3,
    val scheduleMinute: Int = 0,
    val accountEmail: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val wifiOnly: Boolean = false,
    val signingOut: Boolean = false,
    val picker: DrivePickerState = DrivePickerState(),
) {
    val signedIn: Boolean get() = accountEmail != null
}

/** Drive folder picker dialog state. The stack is reset every time the picker opens. */
data class DrivePickerState(
    val visible: Boolean = false,
    /** Folders navigated into; empty = My Drive root. */
    val stack: List<DriveFolder> = emptyList(),
    val folders: List<DriveFolder> = emptyList(),
    val loading: Boolean = false,
    val error: UiText? = null,
    val creating: Boolean = false,
) {
    val current: DriveFolder? get() = stack.lastOrNull()
}

/** One-shot Settings events. */
sealed interface SettingsEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent) : SettingsEvent
    data class ShowMessage(val text: UiText) : SettingsEvent
}
