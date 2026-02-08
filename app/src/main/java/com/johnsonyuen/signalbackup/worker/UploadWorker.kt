/**
 * UploadWorker.kt - WorkManager worker that performs backup uploads in the background.
 *
 * This worker is executed by WorkManager on a schedule (set up by [ScheduleUploadUseCase])
 * to automatically upload Signal backups to Google Drive without user interaction.
 *
 * Key Android/WorkManager concepts:
 * - **CoroutineWorker**: A WorkManager worker that uses Kotlin coroutines (suspend functions)
 *   instead of blocking threads. WorkManager provides the coroutine scope.
 * - **@HiltWorker**: Tells Hilt to enable dependency injection for this worker.
 * - **@AssistedInject**: WorkManager workers have special constructor requirements (Context
 *   and WorkerParameters are provided by WorkManager, not Hilt). @AssistedInject lets Hilt
 *   inject our dependencies (like PerformUploadUseCase) while WorkManager provides the
 *   assisted parameters (Context and WorkerParameters).
 * - **Foreground Service**: On Android 12+, WorkManager workers that need to show a
 *   notification must call setForeground(). This promotes the worker to a foreground service,
 *   which is less likely to be killed by the system. We use FOREGROUND_SERVICE_TYPE_DATA_SYNC
 *   because we are syncing data to the cloud.
 *
 * Retry logic:
 * - If the upload fails, the worker returns Result.retry() (up to MAX_RETRY_COUNT times).
 * - WorkManager will re-execute the worker with exponential backoff (configured in
 *   ScheduleUploadUseCase).
 * - After MAX_RETRY_COUNT attempts, the worker gives up and returns Result.failure().
 *
 * Architecture context:
 * - Part of the **worker** package.
 * - Delegates the actual upload logic to [PerformUploadUseCase].
 * - Scheduled by [ScheduleUploadUseCase] via WorkManager.
 * - Requires custom WorkManager initialization in [SignalBackupApp] to support Hilt DI.
 *
 * @see domain.usecase.PerformUploadUseCase for the actual upload logic
 * @see domain.usecase.ScheduleUploadUseCase for how this worker is scheduled
 * @see SignalBackupApp for the custom WorkManager initialization
 */
package com.johnsonyuen.signalbackup.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background worker that uploads the latest Signal backup to Google Drive.
 *
 * @param appContext Application context, provided by WorkManager via @Assisted.
 * @param workerParams Worker parameters (input data, tags, etc.), provided by WorkManager.
 * @param performUploadUseCase The use case that does the actual upload, injected by Hilt.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val performUploadUseCase: PerformUploadUseCase
) : CoroutineWorker(appContext, workerParams) {

    /**
     * Main entry point for the worker. Called by WorkManager when the scheduled time arrives.
     *
     * @return Result.success() if the upload succeeded, Result.retry() if it failed but
     *         should be retried, or Result.failure() if it failed permanently.
     */
    override suspend fun doWork(): Result {
        // Try to promote to a foreground service to show a notification and reduce
        // the chance of being killed by the system during the upload.
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException on Android 12+ when the app is
            // not in the foreground. The worker still runs, it just cannot show a
            // foreground notification. This is expected behavior.
            Log.w(TAG, "Cannot start foreground service, continuing in background", e)
        }

        // Delegate to the use case and map the result to WorkManager's Result type.
        return when (val status = performUploadUseCase(applicationContext)) {
            is UploadStatus.Success -> Result.success()
            is UploadStatus.Failed -> {
                // Retry up to MAX_RETRY_COUNT times with exponential backoff.
                if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure()
            }
            // NeedsConsent, Idle, Uploading -- these should not occur in a background worker
            // (consent requires UI interaction, and the others are intermediate states).
            else -> Result.failure()
        }
    }

    /**
     * Called by WorkManager when it needs foreground info for this worker.
     * Required for expedited work requests on Android 12+.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    /**
     * Creates the ForegroundInfo with a notification to show during the upload.
     *
     * On Android 8+ (API 26+), notifications require a channel. We create the channel
     * here with IMPORTANCE_LOW so the notification is silent (no sound or vibration).
     * The notification is marked as ongoing so it cannot be dismissed by the user.
     */
    private fun createForegroundInfo(): ForegroundInfo {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the notification channel (idempotent -- safe to call multiple times).
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        // Build a minimal, non-intrusive notification.
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()

        // FOREGROUND_SERVICE_TYPE_DATA_SYNC is required on Android 14+ for workers
        // that sync data to the cloud. Must match the manifest declaration.
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "upload_channel"

        /** Maximum number of retry attempts before giving up. */
        private const val MAX_RETRY_COUNT = 3
    }
}
