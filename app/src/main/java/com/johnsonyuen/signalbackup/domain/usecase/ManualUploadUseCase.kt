/**
 * ManualUploadUseCase.kt - Use case for triggering a one-time manual backup upload via WorkManager.
 *
 * Unlike [PerformUploadUseCase] which contains the actual upload logic, this use case
 * enqueues the upload as a WorkManager [OneTimeWorkRequest]. This ensures the upload:
 * - Survives the app going to background (Activity destruction).
 * - Runs as a foreground service with a notification.
 * - Holds proper system locks (WakeLock, WifiLock) to prevent network drops.
 *
 * Previously, the manual "Upload Now" button ran the upload directly in the ViewModel's
 * coroutine scope, which was tied to the Activity lifecycle. When the user pressed home
 * or switched apps, Android would kill the network connection, causing
 * SocketException: Software caused connection abort.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/usecase package).
 * - Called by [HomeViewModel.uploadNow()] for manual uploads.
 * - The ViewModel observes its progress via WorkManager's unique work name.
 *
 * @see worker.UploadWorker for the worker that actually performs the upload
 * @see PerformUploadUseCase for the actual upload logic inside the worker
 * @see ScheduleUploadUseCase for the periodic scheduled upload equivalent
 */
package com.johnsonyuen.signalbackup.domain.usecase

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.johnsonyuen.signalbackup.worker.UploadWorker
import javax.inject.Inject

/**
 * Use case that enqueues a one-time manual upload via WorkManager.
 *
 * The caller observes upload state via WorkManager's unique work name
 * ([WORK_NAME]) rather than a work request ID.
 *
 * @param workManager The WorkManager instance for enqueueing work requests.
 */
class ManualUploadUseCase @Inject constructor(
    private val workManager: WorkManager
) {
    /**
     * Enqueues a one-time upload work request.
     *
     * Uses [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] as a fallback
     * if the system's expedited work quota is exhausted. The worker will still
     * call setForeground() to promote itself to a foreground service.
     *
     * Uses [ExistingWorkPolicy.KEEP] to prevent duplicate manual uploads --
     * if an upload is already running, the new request is ignored.
     */
    operator fun invoke() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(constraints)
            .setInputData(workDataOf(UploadWorker.KEY_IS_MANUAL_UPLOAD to true))
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        /** Unique name for manual upload work, used to prevent duplicate enqueue. */
        const val WORK_NAME = "signal_backup_manual_upload"
    }
}
