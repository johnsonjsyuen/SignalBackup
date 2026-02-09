/**
 * UploadWorker.kt - WorkManager worker that performs backup uploads in the background.
 *
 * This worker is executed by WorkManager either on a schedule (set up by [ScheduleUploadUseCase])
 * or on demand for manual uploads triggered from the Home screen.
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
 * - **WiFi Lock**: Prevents Android from powering down the WiFi radio during large uploads,
 *   which would cause SocketException when the app is backgrounded.
 * - **WakeLock**: Keeps the CPU awake during the upload. While the foreground service should
 *   handle this, the WakeLock acts as a safety net in case foreground promotion fails.
 *
 * Progress reporting:
 * - The worker uses WorkManager's setProgress(Data) to publish upload progress (bytes uploaded,
 *   total bytes, speed, ETA) so that the ViewModel can observe it via WorkInfo.progress.
 * - The foreground notification is also updated with the upload percentage on each chunk.
 * - Upload speed is computed as a simple cumulative average (total bytes uploaded / elapsed time),
 *   which naturally smooths out as the upload progresses.
 *
 * Foreground promotion strategy:
 * - For **manual uploads** (identified by KEY_IS_MANUAL_UPLOAD input data), foreground promotion
 *   is critical. Without it, Android will kill the network connection when the app goes to
 *   background, causing SocketException. If foreground promotion fails for a manual upload,
 *   the worker returns Result.retry() to try again when conditions are better.
 * - For **scheduled uploads**, the worker can proceed without foreground promotion. WorkManager
 *   keeps the worker alive even without foreground status, though with lower priority.
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
 * - Scheduled by [ScheduleUploadUseCase] via WorkManager for periodic uploads.
 * - Enqueued as OneTimeWorkRequest by [ManualUploadUseCase] for manual uploads.
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
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase
import com.johnsonyuen.signalbackup.util.formatFileSize
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background worker that uploads the latest Signal backup to Google Drive.
 *
 * Acquires both a WiFi lock and a partial WakeLock for the duration of the upload to prevent
 * the network and CPU from being powered down when the app is in the background. The foreground
 * service promotion ensures Android keeps the process alive and the network connection open.
 *
 * Reports upload progress via WorkManager's setProgress(Data) and updates the foreground
 * notification with percentage, speed, and ETA.
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

    /** Tracks whether we successfully promoted to a foreground service. */
    private var foregroundPromoted = false

    /** Timestamp (System.currentTimeMillis) when the upload started, for speed calculation. */
    private var uploadStartTimeMs = 0L

    /**
     * Main entry point for the worker. Called by WorkManager when the scheduled time arrives
     * or when a manual upload is enqueued.
     *
     * The method follows this sequence:
     * 1. Promote to foreground service (required for surviving backgrounding).
     * 2. Acquire a WiFi lock and WakeLock to prevent network/CPU from being dropped.
     * 3. Execute the upload via PerformUploadUseCase with a progress listener.
     * 4. Release both locks in a finally block.
     *
     * @return Result.success() if the upload succeeded, Result.retry() if it failed but
     *         should be retried, or Result.failure() if it failed permanently.
     */
    override suspend fun doWork(): Result {
        val isManualUpload = inputData.getBoolean(KEY_IS_MANUAL_UPLOAD, false)

        // Ensure the notification channel exists before any notification operations.
        ensureNotificationChannel()

        // Promote to a foreground service BEFORE starting the upload. This shows a
        // notification and tells Android this is active, user-visible work that should
        // not be killed when the app goes to background.
        //
        // On Android 12+, this can throw ForegroundServiceStartNotAllowedException if the
        // app is in the background and was not recently in the foreground.
        foregroundPromoted = try {
            setForeground(createForegroundInfo())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote to foreground service: ${e.message}", e)
            false
        }

        // For manual uploads, foreground promotion is critical. Without it, Android will
        // terminate the network connection when the user switches away from the app,
        // causing SocketException. If promotion failed, retry rather than proceeding with
        // an upload that is doomed to fail on backgrounding.
        if (isManualUpload && !foregroundPromoted) {
            Log.w(TAG, "Manual upload requires foreground service; scheduling retry")
            return if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure()
        }

        // Acquire system locks to prevent the OS from suspending network and CPU resources
        // while the upload is in progress.
        val wifiLock = acquireWifiLock()
        val wakeLock = acquireWakeLock()

        // Record the upload start time for speed calculation in the progress listener.
        uploadStartTimeMs = System.currentTimeMillis()
        _progressFlow.value = null

        try {
            // Delegate to the use case with a progress listener that reports to WorkManager.
            // The progress listener is called by the Google Drive API after each chunk upload.
            return when (val status = performUploadUseCase(applicationContext) { bytesUploaded, totalBytes ->
                onUploadProgress(bytesUploaded, totalBytes)
            }) {
                is UploadStatus.Success -> {
                    val outputData = androidx.work.Data.Builder()
                        .putString(KEY_OUTPUT_FILE_NAME, status.fileName)
                        .putLong(KEY_OUTPUT_FILE_SIZE, status.fileSizeBytes)
                        .build()
                    Result.success(outputData)
                }
                is UploadStatus.Failed -> {
                    // Retry up to MAX_RETRY_COUNT times with exponential backoff.
                    if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure()
                }
                // NeedsConsent, Idle, Uploading -- these should not occur in a background worker
                // (consent requires UI interaction, and the others are intermediate states).
                else -> Result.failure()
            }
        } finally {
            _progressFlow.value = null
            // Always release both locks, even if the upload throws an exception.
            releaseWifiLock(wifiLock)
            releaseWakeLock(wakeLock)
        }
    }

    /**
     * Called after each chunk is uploaded to Google Drive. Computes upload speed and ETA,
     * then publishes progress via WorkManager's setProgress and updates the notification.
     *
     * Speed is calculated as a simple average (total bytes uploaded / elapsed time) which
     * naturally smooths out as the upload progresses. This avoids the complexity of windowed
     * averages while still giving a stable reading after the first few chunks.
     *
     * @param bytesUploaded Number of bytes uploaded so far.
     * @param totalBytes Total file size in bytes.
     */
    private fun onUploadProgress(bytesUploaded: Long, totalBytes: Long) {
        Log.d(TAG, "onUploadProgress: $bytesUploaded / $totalBytes (${(bytesUploaded * 100 / totalBytes.coerceAtLeast(1))}%)")

        val elapsedMs = System.currentTimeMillis() - uploadStartTimeMs
        // Avoid division by zero in the first millisecond.
        val elapsedSec = (elapsedMs / 1000.0).coerceAtLeast(MIN_ELAPSED_SECONDS)

        val speedBytesPerSec = (bytesUploaded / elapsedSec).toLong()

        val remainingBytes = totalBytes - bytesUploaded
        val estimatedSecondsRemaining = if (speedBytesPerSec > 0) {
            remainingBytes / speedBytesPerSec
        } else {
            -1L
        }

        val progress = UploadProgress(
            bytesUploaded = bytesUploaded,
            totalBytes = totalBytes,
            speedBytesPerSec = speedBytesPerSec,
            estimatedSecondsRemaining = estimatedSecondsRemaining,
        )

        // Publish progress directly via the static flow for instant UI updates.
        _progressFlow.value = progress

        // Update the foreground notification with progress details.
        if (foregroundPromoted) {
            updateNotificationWithProgress(progress)
        }
    }

    /**
     * Updates the foreground notification to show upload progress percentage, speed, and ETA.
     *
     * Uses NotificationManager.notify() directly (rather than setForeground) to avoid
     * the overhead of re-promoting to foreground service on each progress update. The
     * notification ID matches the original foreground notification so it is updated in place.
     *
     * @param progress The current upload progress snapshot.
     */
    private fun updateNotificationWithProgress(progress: UploadProgress) {
        try {
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val contentText = buildString {
                append("${formatFileSize(progress.bytesUploaded)} / ${formatFileSize(progress.totalBytes)}")
                append(" (${progress.percentComplete}%)")
                if (progress.speedBytesPerSec > 0) {
                    append(" - ${formatFileSize(progress.speedBytesPerSec)}/s")
                }
            }

            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setOngoing(true)
                .setProgress(PROGRESS_MAX, progress.percentComplete, false)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification with progress: ${e.message}")
        }
    }

    /**
     * Ensures the notification channel exists. Called once at the start of doWork() so that
     * both createForegroundInfo() and updateNotificationWithProgress() can use it.
     */
    private fun ensureNotificationChannel() {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Acquires a WiFi lock to prevent Android from powering down the WiFi radio while
     * the upload is in progress. Without this, large uploads fail with SocketException
     * when the device enters Doze mode or the app is backgrounded.
     *
     * @return The acquired WifiLock, or null if acquisition failed.
     */
    private fun acquireWifiLock(): WifiManager.WifiLock? {
        return try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                WIFI_LOCK_TAG
            )?.apply {
                // WifiLock.acquire() does not support a timeout (unlike WakeLock).
                // The lock is explicitly released in the finally block of doWork().
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WiFi lock: ${e.message}", e)
            null
        }
    }

    /**
     * Releases the WiFi lock if it is currently held.
     */
    private fun releaseWifiLock(wifiLock: WifiManager.WifiLock?) {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WiFi lock: ${e.message}", e)
        }
    }

    /**
     * Acquires a partial WakeLock to keep the CPU awake during the upload.
     *
     * The foreground service should handle CPU wakefulness, but the WakeLock acts as a
     * safety net in case foreground promotion was skipped (for scheduled uploads where
     * it is not critical). The lock has a timeout to prevent battery drain if the worker
     * hangs or crashes without reaching the finally block.
     *
     * @return The acquired WakeLock, or null if acquisition failed.
     */
    private fun acquireWakeLock(): PowerManager.WakeLock? {
        return try {
            val powerManager =
                applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )?.apply {
                // Timeout ensures the lock is released even if the worker crashes.
                acquire(LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}", e)
            null
        }
    }

    /**
     * Releases the WakeLock if it is currently held.
     */
    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock: ${e.message}", e)
        }
    }

    /**
     * Called by WorkManager when it needs foreground info for this worker.
     * Required for expedited work requests on Android 12+.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    /**
     * Creates the initial ForegroundInfo with a notification to show during the upload.
     *
     * The notification channel is created by [ensureNotificationChannel] which is called
     * before this method. The notification starts with an indeterminate progress bar and
     * is updated with determinate progress once chunks begin uploading.
     */
    private fun createForegroundInfo(): ForegroundInfo {
        // Build a minimal, non-intrusive notification with indeterminate progress.
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setContentText(NOTIFICATION_PREPARING_TEXT)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
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

        /** WiFi lock tag for identifying this lock in system diagnostics. */
        private const val WIFI_LOCK_TAG = "SignalBackup:UploadWifiLock"

        /** WakeLock tag for identifying this lock in system diagnostics. */
        private const val WAKE_LOCK_TAG = "SignalBackup:UploadWakeLock"

        /** Maximum number of retry attempts before giving up. */
        private const val MAX_RETRY_COUNT = 3

        /**
         * Timeout for the WakeLock (30 minutes). Provides a generous window for large
         * backup uploads while ensuring the lock is released if the worker hangs.
         * Note: The WiFi lock does not support a timeout and is released explicitly
         * in the finally block of doWork().
         */
        private const val LOCK_TIMEOUT_MS = 30L * 60L * 1000L

        /** Maximum value for the notification progress bar (represents 100%). */
        private const val PROGRESS_MAX = 100

        /** Minimum elapsed seconds for speed calculation to avoid division by zero. */
        private const val MIN_ELAPSED_SECONDS = 0.001

        /** Default notification content text shown while the upload is being prepared. */
        private const val NOTIFICATION_PREPARING_TEXT = "Preparing upload..."

        /**
         * Input data key that indicates whether this is a manual upload (user-triggered).
         * When true, the worker will fail-fast if it cannot promote to a foreground service,
         * because without foreground status the upload will be killed on backgrounding.
         */
        const val KEY_IS_MANUAL_UPLOAD = "is_manual_upload"

        /** Output data key for the uploaded file name, set on successful upload. */
        const val KEY_OUTPUT_FILE_NAME = "output_file_name"

        /** Output data key for the uploaded file size in bytes, set on successful upload. */
        const val KEY_OUTPUT_FILE_SIZE = "output_file_size"

        private val _progressFlow = MutableStateFlow<UploadProgress?>(null)
        /** In-process progress flow for the UI to collect directly. */
        val progressFlow: StateFlow<UploadProgress?> = _progressFlow.asStateFlow()
    }
}
