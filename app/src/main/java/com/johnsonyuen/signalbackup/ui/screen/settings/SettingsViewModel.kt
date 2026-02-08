package com.johnsonyuen.signalbackup.ui.screen.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.ThemeMode
import com.johnsonyuen.signalbackup.domain.usecase.ScheduleUploadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val scheduleUploadUseCase: ScheduleUploadUseCase
) : ViewModel() {

    val localFolderUri: StateFlow<String?> = settingsRepository.localFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val driveFolderId: StateFlow<String?> = settingsRepository.driveFolderId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val driveFolderName: StateFlow<String?> = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val scheduleHour: StateFlow<Int> = settingsRepository.scheduleHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    val scheduleMinute: StateFlow<Int> = settingsRepository.scheduleMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val googleAccountEmail: StateFlow<String?> = settingsRepository.googleAccountEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .map { ThemeMode.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    private val _driveFolders = MutableStateFlow<List<DriveFolder>>(emptyList())
    val driveFolders: StateFlow<List<DriveFolder>> = _driveFolders.asStateFlow()

    private val _isLoadingFolders = MutableStateFlow(false)
    val isLoadingFolders: StateFlow<Boolean> = _isLoadingFolders.asStateFlow()

    private val _driveError = MutableStateFlow<String?>(null)
    val driveError: StateFlow<String?> = _driveError.asStateFlow()

    private val _folderStack = MutableStateFlow<List<DriveFolder>>(emptyList())
    val currentDriveFolder: StateFlow<DriveFolder?> = _folderStack
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentDriveFolderName: StateFlow<String?> = _folderStack
        .map { it.lastOrNull()?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setLocalFolderUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.setLocalFolderUri(uri)
        }
    }

    fun setDriveFolder(folder: DriveFolder?) {
        viewModelScope.launch {
            val id = folder?.id ?: "root"
            val name = folder?.name ?: "My Drive"
            settingsRepository.setDriveFolderId(id)
            settingsRepository.setDriveFolderName(name)
        }
    }

    fun setScheduleTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setScheduleTime(hour, minute)
            scheduleUploadUseCase()
        }
    }

    fun loadDriveFolders(parentId: String? = null) {
        viewModelScope.launch {
            _isLoadingFolders.value = true
            _driveError.value = null
            try {
                _driveFolders.value = driveRepository.listFolders(parentId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list Drive folders", e)
                _driveFolders.value = emptyList()
                _driveError.value = "Failed to load folders: ${e.message}"
            } finally {
                _isLoadingFolders.value = false
            }
        }
    }

    fun navigateToFolder(folder: DriveFolder) {
        _folderStack.value = _folderStack.value + folder
        loadDriveFolders(folder.id)
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            _driveError.value = null
            try {
                val parentId = _folderStack.value.lastOrNull()?.id
                driveRepository.createFolder(name, parentId)
                loadDriveFolders(parentId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create Drive folder '$name'", e)
                _driveError.value = "Failed to create folder: ${e.message}"
            }
        }
    }

    fun clearDriveError() {
        _driveError.value = null
    }

    fun navigateUp() {
        val stack = _folderStack.value
        if (stack.isNotEmpty()) {
            _folderStack.value = stack.dropLast(1)
            loadDriveFolders(_folderStack.value.lastOrNull()?.id)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode.name)
        }
    }

    fun signOut() {
        viewModelScope.launch {
            settingsRepository.clearAccountData()
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
