package com.johnsonyuen.signalbackup.ui.screen.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.ui.common.LocalFolderAccess
import com.johnsonyuen.signalbackup.ui.common.LocalFolderInfo
import com.johnsonyuen.signalbackup.ui.common.UiText
import com.johnsonyuen.signalbackup.ui.consent.AuthorizationStart
import com.johnsonyuen.signalbackup.ui.consent.DriveAuthorizationFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings screen: folders, schedule, theme, permissions and account. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val uploadScheduler: UploadScheduler,
    private val driveAuthorizer: DriveAuthorizer,
    private val syncedFileRepository: SyncedFileRepository,
    private val uploadSessionRepository: UploadSessionRepository,
    private val localFolderAccess: LocalFolderAccess,
    private val authorizationFlow: DriveAuthorizationFlow,
) : ViewModel() {

    private data class LocalState(
        val picker: DrivePickerState = DrivePickerState(),
        val signingOut: Boolean = false,
    )

    private val local = MutableStateFlow(LocalState())
    private val folderRefresh = MutableStateFlow(0)
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null

    private val folderInfo: Flow<LocalFolderInfo?> =
        combine(
            settingsRepository.settings.map { it.localFolderUri }.distinctUntilChanged(),
            folderRefresh,
        ) { uri, _ -> uri }
            .transformLatest { uri -> emit(uri?.let { localFolderAccess.inspect(it) }) }
            .onStart { emit(null) }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        folderInfo,
        local,
    ) { s, info, localState ->
        val folderSet = s.localFolderUri != null
        SettingsUiState(
            localFolderSet = folderSet,
            localFolderName = if (folderSet) info?.name else null,
            localFolderAccessible = !folderSet || info == null || info.accessible,
            localFolderHasBackups = if (folderSet) info?.hasSignalBackups else null,
            driveFolderName = s.driveFolderName,
            scheduleEnabled = s.scheduleEnabled,
            scheduleHour = s.scheduleHour,
            scheduleMinute = s.scheduleMinute,
            accountEmail = s.googleAccountEmail,
            themeMode = ThemeMode.fromString(s.themeMode),
            wifiOnly = s.wifiOnly,
            signingOut = localState.signingOut,
            picker = localState.picker,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun refreshLocalFolder() {
        folderRefresh.update { it + 1 }
    }

    // --- Local folder ---

    /** Called after the SAF picker returned [uri] and the read permission was persisted. */
    fun setLocalFolder(uri: String) {
        viewModelScope.launch {
            val previous = settingsRepository.current().localFolderUri
            if (previous == uri) return@launch
            previous?.let { localFolderAccess.releasePermission(it) }
            settingsRepository.setLocalFolderUri(uri)
            // Relative paths now refer to a different tree.
            uploadSessionRepository.clear()
            syncedFileRepository.clear()
            uploadScheduler.applySchedule()
        }
    }

    // --- Schedule ---

    fun setScheduleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScheduleEnabled(enabled)
            uploadScheduler.applySchedule()
        }
    }

    fun setScheduleTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setScheduleTime(hour, minute)
            uploadScheduler.applySchedule()
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setWifiOnly(enabled)
            uploadScheduler.applySchedule()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode.name) }
    }

    // --- Drive folder picker ---

    /** Opens the picker at My Drive with a fresh stack and list. */
    fun openDrivePicker() {
        loadJob?.cancel()
        setPicker { DrivePickerState(visible = true) }
        load(null)
    }

    fun dismissDrivePicker() {
        loadJob?.cancel()
        loadJob = null
        setPicker { DrivePickerState() }
    }

    fun openFolder(folder: DriveFolder) {
        setPicker { it.copy(stack = it.stack + folder) }
        load(folder.id)
    }

    fun navigateUp() {
        val stack = local.value.picker.stack
        if (stack.isEmpty()) return
        val newStack = stack.dropLast(1)
        setPicker { it.copy(stack = newStack) }
        load(newStack.lastOrNull()?.id)
    }

    fun retryLoad() {
        load(local.value.picker.current?.id)
    }

    private fun load(parentId: String?) {
        loadJob?.cancel()
        setPicker { it.copy(loading = true, error = null, folders = emptyList()) }
        loadJob = viewModelScope.launch {
            try {
                val folders = driveRepository.listFolders(parentId)
                setPicker { if (it.visible && it.current?.id == parentId) it.copy(folders = folders, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                setPicker { DrivePickerState() }
                requestConsent()
            } catch (e: Exception) {
                setPicker { it.copy(loading = false, error = DriveAuthorizationFlow.errorText(e)) }
            }
        }
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val parentId = local.value.picker.current?.id
        setPicker { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                driveRepository.createFolder(trimmed, parentId)
                setPicker { it.copy(creating = false) }
                if (local.value.picker.visible) load(local.value.picker.current?.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NeedsConsentException) {
                setPicker { DrivePickerState() }
                requestConsent()
            } catch (e: Exception) {
                setPicker { it.copy(creating = false, error = DriveAuthorizationFlow.errorText(e)) }
            }
        }
    }

    /** Saves [folder] as the backup destination and closes the picker. */
    fun selectDriveFolder(folder: DriveFolder) {
        dismissDrivePicker()
        viewModelScope.launch {
            if (settingsRepository.current().driveFolderId == folder.id) return@launch
            settingsRepository.setDriveFolder(folder.id, folder.name)
            uploadSessionRepository.clear()
            syncedFileRepository.clear()
            uploadScheduler.applySchedule()
        }
    }

    private fun setPicker(transform: (DrivePickerState) -> DrivePickerState) {
        local.update { it.copy(picker = transform(it.picker)) }
    }

    // --- Consent (folder picker) ---

    private suspend fun requestConsent() {
        when (val start = authorizationFlow.start()) {
            is AuthorizationStart.Authorized -> {
                start.message?.let { _events.send(SettingsEvent.ShowMessage(it)) }
                openDrivePicker()
            }
            is AuthorizationStart.NeedsUi -> _events.send(SettingsEvent.LaunchConsent(start.pendingIntent))
            is AuthorizationStart.Failed -> _events.send(SettingsEvent.ShowMessage(start.message))
        }
    }

    /** Consent screen result; reopens the picker when access was granted. */
    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            val result = authorizationFlow.complete(data)
            result.message?.let { _events.send(SettingsEvent.ShowMessage(it)) }
            if (result.granted) openDrivePicker()
        }
    }

    fun onConsentLaunchFailed() {
        viewModelScope.launch {
            _events.send(SettingsEvent.ShowMessage(UiText.Res(R.string.error_consent_launch)))
        }
    }

    // --- Account ---

    /** Sign-out per product decision 8. */
    fun signOut() {
        if (local.value.signingOut) return
        local.update { it.copy(signingOut = true) }
        dismissDrivePicker()
        viewModelScope.launch {
            try {
                val localUri = settingsRepository.current().localFolderUri
                driveAuthorizer.revokeAccess()
                uploadScheduler.cancelAll()
                settingsRepository.clearAccountData()
                uploadSessionRepository.clear()
                syncedFileRepository.clear()
                if (localUri != null) {
                    localFolderAccess.releasePermission(localUri)
                    settingsRepository.setLocalFolderUri(null)
                }
                _events.send(SettingsEvent.ShowMessage(UiText.Res(R.string.signed_out)))
            } finally {
                local.update { it.copy(signingOut = false) }
            }
        }
    }
}
