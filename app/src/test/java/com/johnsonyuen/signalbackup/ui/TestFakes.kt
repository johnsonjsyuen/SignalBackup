package com.johnsonyuen.signalbackup.ui

import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.Instant

val DEFAULT_SETTINGS = AppSettings(
    localFolderUri = null,
    driveFolderId = null,
    driveFolderName = null,
    scheduleEnabled = true,
    scheduleHour = 3,
    scheduleMinute = 0,
    googleAccountEmail = null,
    themeMode = "SYSTEM",
    wifiOnly = false,
)

val COMPLETE_SETTINGS = DEFAULT_SETTINGS.copy(
    localFolderUri = "content://tree/signal",
    driveFolderId = "drive-folder",
    driveFolderName = "Signal Backup",
    googleAccountEmail = "user@example.com",
)

/** In-memory [SettingsRepository]. */
class FakeSettingsRepository(initial: AppSettings = DEFAULT_SETTINGS) : SettingsRepository {
    val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value

    private fun <T> field(selector: (AppSettings) -> T): Flow<T> = state.map(selector).distinctUntilChanged()

    override val localFolderUri = field { it.localFolderUri }
    override val driveFolderId = field { it.driveFolderId }
    override val driveFolderName = field { it.driveFolderName }
    override val scheduleEnabled = field { it.scheduleEnabled }
    override val scheduleHour = field { it.scheduleHour }
    override val scheduleMinute = field { it.scheduleMinute }
    override val googleAccountEmail = field { it.googleAccountEmail }
    override val themeMode = field { it.themeMode }
    override val wifiOnly = field { it.wifiOnly }

    override suspend fun setLocalFolderUri(uri: String?) = state.update { it.copy(localFolderUri = uri) }
    override suspend fun setDriveFolder(id: String?, name: String?) =
        state.update { it.copy(driveFolderId = id, driveFolderName = name) }
    override suspend fun setScheduleEnabled(enabled: Boolean) = state.update { it.copy(scheduleEnabled = enabled) }
    override suspend fun setScheduleTime(hour: Int, minute: Int) =
        state.update { it.copy(scheduleHour = hour, scheduleMinute = minute) }
    override suspend fun setGoogleAccountEmail(email: String?) = state.update { it.copy(googleAccountEmail = email) }
    override suspend fun setThemeMode(mode: String) = state.update { it.copy(themeMode = mode) }
    override suspend fun setWifiOnly(enabled: Boolean) = state.update { it.copy(wifiOnly = enabled) }
    override suspend fun clearAccountData() =
        state.update { it.copy(googleAccountEmail = null, driveFolderId = null, driveFolderName = null) }
}

fun record(
    id: Long = 1,
    timestampMillis: Long,
    status: UploadResultStatus,
    reason: FailureReason? = null,
    bytesUploaded: Long = 0,
) = UploadRecord(
    id = id,
    timestamp = Instant.ofEpochMilli(timestampMillis),
    fileName = "signal-backup-2026-10-18-03-00-00",
    fileSizeBytes = 1_000_000,
    bytesUploaded = bytesUploaded,
    status = status,
    failureReason = reason,
    errorMessage = null,
    driveFolderId = "drive-folder",
    driveFileId = null,
    md5Checksum = null,
)

/** Sets Dispatchers.Main to a test dispatcher for each test. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
