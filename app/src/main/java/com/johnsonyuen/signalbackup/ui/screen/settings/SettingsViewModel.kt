/**
 * SettingsViewModel.kt - ViewModel for the Settings screen, managing app configuration.
 *
 * This ViewModel manages all user-configurable settings:
 * - **Local backup folder**: The SAF URI where Signal stores its backups.
 * - **Drive destination folder**: The Google Drive folder to upload backups to.
 * - **Upload schedule**: The daily time (hour + minute) for automatic backups.
 * - **Theme mode**: Light, dark, or system default.
 * - **Google account**: The signed-in email; sign-out capability.
 *
 * It also manages the Drive folder picker's navigation state:
 * - A stack of visited folders (for back navigation).
 * - The current folder's children (loaded from the Drive API).
 * - Loading and error states for Drive API calls.
 *
 * State management patterns:
 * - **Settings flows** (localFolderUri, driveFolderId, etc.) use `Flow.stateIn()` with
 *   `WhileSubscribed(5000)` -- reactive reads from DataStore with a 5-second keep-alive.
 * - **Folder picker state** (_driveFolders, _isLoadingFolders, _folderStack, _driveError)
 *   uses `MutableStateFlow` because it is driven by imperative API calls, not DataStore.
 * - **themeMode** uses `Flow.map { ThemeMode.fromString(it) }` to convert the raw
 *   string stored in DataStore to a type-safe enum.
 *
 * Drive folder navigation:
 * The folder picker is a mini file browser. The ViewModel maintains a "folder stack"
 * representing the path from root to the current folder:
 * - `navigateToFolder(folder)`: Push onto stack, load children.
 * - `navigateUp()`: Pop from stack, load parent's children.
 * - `currentDriveFolder`: The top of the stack (null = root / "My Drive").
 * - `loadDriveFolders(parentId)`: Calls the Drive API to list subfolders.
 *
 * @see ui.screen.settings.SettingsScreen for the UI that observes this ViewModel
 * @see data.repository.SettingsRepository for the DataStore-backed settings
 * @see data.repository.DriveRepository for the Google Drive API calls
 * @see domain.usecase.ScheduleUploadUseCase for rescheduling after time changes
 */
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

/**
 * ViewModel for the Settings screen.
 *
 * @param settingsRepository Provides reactive access to user preferences in DataStore.
 * @param driveRepository Provides Google Drive API operations (list folders, create folder).
 * @param scheduleUploadUseCase Reschedules the WorkManager periodic work when the time changes.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val scheduleUploadUseCase: ScheduleUploadUseCase
) : ViewModel() {

    // -----------------------------------------------------------------------
    // Settings flows -- reactive reads from DataStore.
    // Each uses stateIn() to convert the cold Flow to a hot StateFlow with
    // a sensible default value.
    // -----------------------------------------------------------------------

    /** SAF URI string of the local backup folder, or null if not configured. */
    val localFolderUri: StateFlow<String?> = settingsRepository.localFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Google Drive folder ID for the upload destination, or null. */
    val driveFolderId: StateFlow<String?> = settingsRepository.driveFolderId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Display name of the selected Drive folder, or null. */
    val driveFolderName: StateFlow<String?> = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Scheduled upload hour (0-23). Default: 3 (3:00 AM). */
    val scheduleHour: StateFlow<Int> = settingsRepository.scheduleHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    /** Scheduled upload minute (0-59). Default: 0. */
    val scheduleMinute: StateFlow<Int> = settingsRepository.scheduleMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The signed-in Google account email, or null. */
    val googleAccountEmail: StateFlow<String?> = settingsRepository.googleAccountEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The current theme mode (Light, Dark, System).
     * Maps the raw string from DataStore to a type-safe [ThemeMode] enum.
     */
    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .map { ThemeMode.fromString(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    // -----------------------------------------------------------------------
    // Drive folder picker state -- imperative, driven by API calls.
    // -----------------------------------------------------------------------

    /** The list of subfolders in the currently browsed Drive folder. */
    private val _driveFolders = MutableStateFlow<List<DriveFolder>>(emptyList())
    val driveFolders: StateFlow<List<DriveFolder>> = _driveFolders.asStateFlow()

    /** True while a Drive API call to list folders is in progress. */
    private val _isLoadingFolders = MutableStateFlow(false)
    val isLoadingFolders: StateFlow<Boolean> = _isLoadingFolders.asStateFlow()

    /** Error message from the last failed Drive API call, or null. */
    private val _driveError = MutableStateFlow<String?>(null)
    val driveError: StateFlow<String?> = _driveError.asStateFlow()

    /**
     * Stack of folders the user has navigated into.
     * Empty = at the Drive root ("My Drive").
     * The last element is the currently viewed folder.
     */
    private val _folderStack = MutableStateFlow<List<DriveFolder>>(emptyList())

    /** The folder currently being viewed (top of stack), or null if at root. */
    val currentDriveFolder: StateFlow<DriveFolder?> = _folderStack
        .map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The display name of the currently viewed folder, or null (= "My Drive"). */
    val currentDriveFolderName: StateFlow<String?> = _folderStack
        .map { it.lastOrNull()?.name }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // -----------------------------------------------------------------------
    // Settings actions -- called by the UI to update preferences.
    // -----------------------------------------------------------------------

    /** Saves the selected local backup folder URI to DataStore. */
    fun setLocalFolderUri(uri: String) {
        viewModelScope.launch {
            settingsRepository.setLocalFolderUri(uri)
        }
    }

    /**
     * Saves the selected Drive folder (id + name) to DataStore.
     * If [folder] is null, saves "root" / "My Drive" (the Drive root).
     */
    fun setDriveFolder(folder: DriveFolder?) {
        viewModelScope.launch {
            val id = folder?.id ?: "root"
            val name = folder?.name ?: "My Drive"
            settingsRepository.setDriveFolderId(id)
            settingsRepository.setDriveFolderName(name)
        }
    }

    /**
     * Updates the daily schedule time and immediately reschedules the WorkManager
     * periodic work to use the new time.
     */
    fun setScheduleTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            settingsRepository.setScheduleTime(hour, minute)
            scheduleUploadUseCase()  // Reschedule WorkManager with the new time.
        }
    }

    // -----------------------------------------------------------------------
    // Drive folder picker actions -- navigation and folder management.
    // -----------------------------------------------------------------------

    /**
     * Loads the subfolders of the given parent folder from the Drive API.
     * Called when the picker dialog opens (parentId = null for root) and
     * when the user navigates into a subfolder.
     */
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

    /** Navigates into a subfolder: pushes it onto the stack and loads its children. */
    fun navigateToFolder(folder: DriveFolder) {
        _folderStack.value = _folderStack.value + folder
        loadDriveFolders(folder.id)
    }

    /**
     * Creates a new folder inside the currently viewed folder on Google Drive.
     * After creation, reloads the folder list to show the new folder.
     */
    fun createFolder(name: String) {
        viewModelScope.launch {
            _driveError.value = null
            try {
                val parentId = _folderStack.value.lastOrNull()?.id
                driveRepository.createFolder(name, parentId)
                loadDriveFolders(parentId)  // Refresh to show the new folder.
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create Drive folder '$name'", e)
                _driveError.value = "Failed to create folder: ${e.message}"
            }
        }
    }

    /** Clears the Drive error message (e.g., after it has been shown in a snackbar). */
    fun clearDriveError() {
        _driveError.value = null
    }

    /**
     * Navigates up one level in the folder hierarchy.
     * Pops the top folder from the stack and loads the parent's children.
     */
    fun navigateUp() {
        val stack = _folderStack.value
        if (stack.isNotEmpty()) {
            _folderStack.value = stack.dropLast(1)
            loadDriveFolders(_folderStack.value.lastOrNull()?.id)
        }
    }

    // -----------------------------------------------------------------------
    // Theme and account actions.
    // -----------------------------------------------------------------------

    /** Saves the selected theme mode to DataStore. */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode.name)
        }
    }

    /**
     * Signs out by clearing the stored Google account email and Drive folder settings.
     * Does NOT revoke the Google OAuth token -- the user can sign back in without
     * re-granting permissions.
     */
    fun signOut() {
        viewModelScope.launch {
            settingsRepository.clearAccountData()
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
