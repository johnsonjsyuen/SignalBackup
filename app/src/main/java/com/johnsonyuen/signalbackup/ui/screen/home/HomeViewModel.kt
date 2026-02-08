/**
 * HomeViewModel.kt - ViewModel for the Home screen, managing upload state and settings.
 *
 * This ViewModel is the bridge between the Home screen UI and the app's business logic.
 * It exposes:
 * - The current upload status (idle, uploading, success, failed, needs consent).
 * - Upload progress data (bytes, speed, ETA) during active uploads.
 * - Several user settings (email, schedule, folders) as reactive StateFlows.
 * - Action functions that the UI can call (upload now, set email, report errors).
 *
 * State management pattern:
 * - **MutableStateFlow + asStateFlow()** for the upload status, which is driven by
 *   observing WorkManager's WorkInfo state for manual uploads.
 * - **Flow.stateIn()** for settings values, which are read reactively from DataStore.
 *   `SharingStarted.WhileSubscribed(5000)` keeps the Flow active for 5 seconds after
 *   the last collector disappears, preventing unnecessary restarts during configuration
 *   changes (like screen rotation).
 *
 * Manual upload flow (triggered by uploadNow()):
 * 1. Enqueue a OneTimeWorkRequest via WorkManager through ManualUploadUseCase.
 * 2. WorkManager promotes the worker to a foreground service (shows notification).
 * 3. The worker acquires a WiFi lock, executes the upload, and releases the lock.
 * 4. This ViewModel observes the WorkInfo LiveData and maps it to UploadStatus.
 * 5. Because the upload runs in WorkManager (not viewModelScope), it survives the
 *    Activity going to background, process death, and even device reboots.
 *
 * Progress reporting:
 * - While the worker is RUNNING, WorkInfo.progress contains serialized UploadProgress data.
 * - The ViewModel extracts this into a StateFlow<UploadProgress?> for the UI to display.
 * - Progress is cleared (set to null) when the upload finishes or is not in progress.
 *
 * Key Android/Compose concepts:
 * - **@HiltViewModel**: Tells Hilt to create this ViewModel and inject its constructor
 *   dependencies. Compose accesses it via `hiltViewModel()`.
 * - **@ApplicationContext**: Injects the Application context (not Activity context).
 *   This is safe because ViewModels outlive Activities -- holding an Activity context
 *   would cause a memory leak.
 * - **viewModelScope.launch**: Launches a coroutine tied to the ViewModel's lifecycle.
 *   The coroutine is automatically cancelled when the ViewModel is cleared.
 *
 * @see domain.usecase.ManualUploadUseCase for enqueuing the upload via WorkManager
 * @see domain.usecase.PerformUploadUseCase for the actual upload logic inside the worker
 * @see worker.UploadWorker for the WorkManager worker that executes uploads
 * @see ui.screen.home.HomeScreen for the UI that observes this ViewModel
 */
package com.johnsonyuen.signalbackup.ui.screen.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.domain.usecase.ManualUploadUseCase
import com.johnsonyuen.signalbackup.receiver.UploadAlarmReceiver
import com.johnsonyuen.signalbackup.worker.UploadWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen.
 *
 * @param manualUploadUseCase Enqueues the upload as WorkManager work (survives backgrounding).
 * @param settingsRepository Provides reactive access to user preferences.
 * @param workManager WorkManager instance for observing manual upload work status.
 * @param appContext Application context, safe to hold in a ViewModel.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val manualUploadUseCase: ManualUploadUseCase,
    private val settingsRepository: SettingsRepository,
    private val workManager: WorkManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // -----------------------------------------------------------------------
    // Upload status -- driven by observing WorkManager's WorkInfo state.
    // -----------------------------------------------------------------------

    /** Mutable backing field for the upload status. Only this ViewModel can write to it. */
    private val _uploadStatus = MutableStateFlow<UploadStatus>(UploadStatus.Idle)

    /** Public read-only upload status observed by the Home screen UI. */
    val uploadStatus: StateFlow<UploadStatus> = _uploadStatus.asStateFlow()

    // -----------------------------------------------------------------------
    // Upload progress -- collected directly from UploadWorker's static flow.
    // -----------------------------------------------------------------------

    /**
     * Upload progress observed by the Home screen UI.
     *
     * Backed by an explicit collection from UploadWorker's static companion flow into
     * a local MutableStateFlow. This ensures every emission is relayed with logging,
     * making it easy to diagnose whether progress updates are reaching the ViewModel.
     */
    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    init {
        // Observe the manual upload work status and map it to our UploadStatus.
        // This survives Activity recreation and picks up in-progress uploads.
        observeManualUploadWork()
        observeScheduledUploadWork()

        // Explicitly collect progress from the UploadWorker's static flow.
        // Using an explicit collect (instead of stateIn) so we can log each emission
        // to diagnose whether progress updates flow through correctly.
        viewModelScope.launch {
            UploadWorker.progressFlow.collect { progress ->
                Log.d(TAG, "ViewModel received progress: ${progress?.bytesUploaded} / ${progress?.totalBytes}")
                _uploadProgress.value = progress
            }
        }
    }

    // -----------------------------------------------------------------------
    // Settings flows -- read reactively from DataStore via the repository.
    // WhileSubscribed(5000) keeps the flow alive for 5s after the last collector
    // disconnects, avoiding restarts during quick config changes.
    // -----------------------------------------------------------------------

    /** The signed-in Google account email, or null if not signed in. */
    val googleAccountEmail: StateFlow<String?> = settingsRepository.googleAccountEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The scheduled upload hour (0-23). Defaults to 3 (3:00 AM). */
    val scheduleHour: StateFlow<Int> = settingsRepository.scheduleHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)

    /** The scheduled upload minute (0-59). Defaults to 0. */
    val scheduleMinute: StateFlow<Int> = settingsRepository.scheduleMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The SAF URI of the selected local backup folder, or null if not set. */
    val localFolderUri: StateFlow<String?> = settingsRepository.localFolderUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The display name of the selected Drive destination folder, or null. */
    val driveFolderName: StateFlow<String?> = settingsRepository.driveFolderName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Initiates an upload of the latest Signal backup to Google Drive.
     *
     * Delegates to [ManualUploadUseCase] which enqueues a OneTimeWorkRequest through
     * WorkManager. This ensures the upload runs as a foreground service that survives
     * the Activity going to background.
     *
     * The work request uses EXPEDITED priority so it starts immediately and promotes
     * the worker to a foreground service on Android 12+. The worker acquires a WiFi lock
     * to prevent network drops during large uploads.
     *
     * The ViewModel observes the work status via [observeManualUploadWork] and updates
     * [uploadStatus] accordingly.
     */
    fun uploadNow() {
        _uploadStatus.value = UploadStatus.Uploading
        manualUploadUseCase()
        Log.d(TAG, "Manual upload work enqueued via ManualUploadUseCase")
    }

    /**
     * Observes WorkManager's work status for the manual upload and maps it to UploadStatus.
     * Also extracts progress data from WorkInfo.progress when the worker is RUNNING.
     *
     * WorkManager provides a LiveData<List<WorkInfo>> for each unique work name. We convert
     * it to a Flow and collect it in viewModelScope. The mapping is:
     * - ENQUEUED/BLOCKED -> Uploading (waiting to start)
     * - RUNNING -> Uploading (actively uploading) + extract progress data
     * - SUCCEEDED -> Success (upload complete)
     * - FAILED/CANCELLED -> Failed
     *
     * Note: WorkInfo does not carry the detailed UploadStatus (file name, size, error message)
     * because WorkManager's output data is limited. For detailed status after completion,
     * the user can check the upload history screen.
     */
    private fun observeManualUploadWork() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(ManualUploadUseCase.WORK_NAME)
                .collect { workInfos ->
                    // getWorkInfosForUniqueWork returns a list, but we only ever have one
                    // work request for this unique name (KEEP policy). Take the first.
                    val workInfo = workInfos.firstOrNull() ?: return@collect

                    val newStatus = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED,
                        WorkInfo.State.RUNNING -> UploadStatus.Uploading

                        WorkInfo.State.SUCCEEDED -> UploadStatus.Success(
                            fileName = workInfo.outputData.getString(UploadWorker.KEY_OUTPUT_FILE_NAME)
                                ?: "Backup uploaded",
                            fileSizeBytes = workInfo.outputData.getLong(UploadWorker.KEY_OUTPUT_FILE_SIZE, 0)
                        )

                        WorkInfo.State.FAILED -> UploadStatus.Failed(
                            "Upload failed. Check history for details."
                        )

                        WorkInfo.State.CANCELLED -> UploadStatus.Failed(
                            "Upload was cancelled"
                        )
                    }

                    _uploadStatus.value = newStatus
                }
        }
    }

    /**
     * Observes WorkManager's work status for scheduled uploads and maps it to UploadStatus.
     *
     * This mirrors [observeManualUploadWork] but tracks the scheduled upload work enqueued
     * by UploadAlarmReceiver. This ensures the Home screen UI reflects the status of
     * scheduled uploads (e.g., showing "Uploading" or "Success") just like manual ones.
     */
    private fun observeScheduledUploadWork() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(SCHEDULED_WORK_NAME)
                .collect { workInfos ->
                    val workInfo = workInfos.firstOrNull() ?: return@collect

                    val newStatus = when (workInfo.state) {
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.BLOCKED,
                        WorkInfo.State.RUNNING -> UploadStatus.Uploading

                        WorkInfo.State.SUCCEEDED -> UploadStatus.Success(
                            fileName = workInfo.outputData.getString(UploadWorker.KEY_OUTPUT_FILE_NAME)
                                ?: "Backup uploaded",
                            fileSizeBytes = workInfo.outputData.getLong(UploadWorker.KEY_OUTPUT_FILE_SIZE, 0)
                        )

                        WorkInfo.State.FAILED -> UploadStatus.Failed(
                            "Upload failed. Check history for details."
                        )

                        WorkInfo.State.CANCELLED -> UploadStatus.Failed(
                            "Upload was cancelled"
                        )
                    }

                    _uploadStatus.value = newStatus
                }
        }
    }

    /**
     * Cancels the currently running manual upload.
     *
     * Cancels the WorkManager work by unique name, then immediately resets the UI state
     * to Idle. WorkManager will also emit a CANCELLED WorkInfo, but we set Idle here for
     * instant UI feedback rather than waiting for the WorkInfo observer to fire.
     */
    fun cancelUpload() {
        workManager.cancelUniqueWork(ManualUploadUseCase.WORK_NAME)
        workManager.cancelUniqueWork(UploadAlarmReceiver.WORK_NAME)
        _uploadStatus.value = UploadStatus.Idle
        Log.d(TAG, "Upload cancelled by user")
    }

    /**
     * Called by the UI to set an explicit failure status (e.g., when the user
     * denies the Drive consent dialog).
     */
    fun setUploadFailed(message: String) {
        _uploadStatus.value = UploadStatus.Failed(message)
    }

    /**
     * Persists the Google account email to DataStore after a successful sign-in.
     * This email is later used by GoogleDriveService to authenticate API calls.
     */
    fun setGoogleAccountEmail(email: String) {
        viewModelScope.launch {
            settingsRepository.setGoogleAccountEmail(email)
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"
        private const val SCHEDULED_WORK_NAME = UploadAlarmReceiver.WORK_NAME
    }
}
