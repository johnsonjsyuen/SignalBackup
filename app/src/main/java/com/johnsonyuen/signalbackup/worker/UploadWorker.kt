package com.johnsonyuen.signalbackup.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.domain.model.MirrorProgressListener
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.model.UploadResult
import com.johnsonyuen.signalbackup.domain.scheduling.UploadProgressTracker
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.domain.usecase.PerformUploadUseCase
import com.johnsonyuen.signalbackup.notification.BackupNotifier
import com.johnsonyuen.signalbackup.notification.NotificationChannels
import com.johnsonyuen.signalbackup.notification.UploadProgressNotifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration

/** Runs one upload pass (scheduled or manual) as a dataSync foreground worker. */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val performUpload: PerformUploadUseCase,
    private val progressTracker: UploadProgressTracker,
    private val notifier: BackupNotifier,
    private val progressNotifications: UploadProgressNotifications,
    private val historyRepository: UploadHistoryRepository,
    private val scheduler: UploadScheduler,
    private val clock: Clock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val promoted = promoteToForeground()
        if (!promoted && manual && runAttemptCount < MAX_ATTEMPTS - 1) {
            Log.w(TAG, "Foreground promotion failed for manual run; retrying")
            return Result.retry()
        }
        if (!promoted) Log.w(TAG, "Running without foreground service")

        val wifiLock = acquireWifiLock()
        val wakeLock = acquireWakeLock()
        progressTracker.update(null)
        try {
            val result = performUpload(progressListener())
            Log.d(TAG, "Run finished: ${result.javaClass.simpleName}")
            return handleResult(result, manual)
        } catch (e: CancellationException) {
            logStopReason()
            throw e
        } finally {
            progressTracker.update(null)
            if (!promoted) progressNotifications.cancel()
            release(wifiLock, wakeLock)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(null)

    private suspend fun handleResult(result: UploadResult, manual: Boolean): Result = when (result) {
        is UploadResult.Success -> {
            notifier.clearProblemNotifications()
            finishScheduledRun(manual)
            Result.success()
        }
        is UploadResult.NoNewBackup -> {
            notifier.notifyStale(latestSuccessMillis(), result.newestBackupTimeMillis)
            finishScheduledRun(manual)
            Result.success()
        }
        UploadResult.NeedsConsent -> {
            notifier.notifyNeedsConsent()
            notifyIfStaleAfterFailure()
            finishScheduledRun(manual)
            Result.failure()
        }
        UploadResult.WaitingForWifi -> Result.retry()
        UploadResult.AlreadyRunning -> Result.success()
        is UploadResult.Failed -> {
            if (result.retryable && runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                notifier.notifyFailure(result.reason, result.detail)
                notifyIfStaleAfterFailure()
                finishScheduledRun(manual)
                Result.failure()
            }
        }
    }

    private suspend fun latestSuccessMillis(): Long? = try {
        historyRepository.getLatestSuccess().first()?.timestamp?.toEpochMilli()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not read history: ${e.javaClass.simpleName}")
        null
    }

    /** After a failed run, warn when the last successful backup is older than [STALE_AFTER]. */
    private suspend fun notifyIfStaleAfterFailure() {
        val lastSuccess = latestSuccessMillis() ?: return
        if (clock.millis() - lastSuccess > STALE_AFTER.toMillis()) {
            notifier.notifyStale(lastSuccess, null)
        }
    }

    /** Re-pins the next daily run to the chosen time (scheduled runs only). */
    private suspend fun finishScheduledRun(manual: Boolean) {
        if (manual) return
        try {
            scheduler.onScheduledRunFinished()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not re-pin schedule: ${e.javaClass.simpleName}")
        }
    }

    private fun progressListener(): MirrorProgressListener {
        val meter = TransferSpeedMeter()
        var lastNotifyMillis = 0L
        var lastPercent = -1
        return MirrorProgressListener { bytesUploaded, totalBytes, currentFileName ->
            val now = clock.millis()
            val speed = meter.record(now, bytesUploaded)
            val remaining = (totalBytes - bytesUploaded).coerceAtLeast(0)
            val eta = if (speed > 0) remaining / speed else -1L
            val progress = UploadProgress(
                bytesUploaded = bytesUploaded,
                totalBytes = totalBytes,
                speedBytesPerSec = speed,
                estimatedSecondsRemaining = eta,
                currentFileName = currentFileName,
            )
            progressTracker.update(progress)
            val percent = progress.percentComplete
            if (now - lastNotifyMillis >= NOTIFY_INTERVAL_MILLIS || percent == 100 && lastPercent != 100) {
                lastNotifyMillis = now
                lastPercent = percent
                progressNotifications.show(progress)
            }
        }
    }

    private suspend fun promoteToForeground(): Boolean = try {
        setForeground(foregroundInfo(null))
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // E.g. ForegroundServiceStartNotAllowedException, or the Android 15 dataSync time limit.
        Log.w(TAG, "Foreground promotion failed: ${e.javaClass.simpleName}")
        false
    }

    private fun foregroundInfo(progress: UploadProgress?): ForegroundInfo {
        val notification = progressNotifications.build(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NotificationChannels.ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NotificationChannels.ID_PROGRESS, notification)
        }
    }

    private fun logStopReason() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(TAG, "Upload stopped")
            return
        }
        val reason = stopReason
        if (reason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
            Log.w(TAG, "Upload stopped: dataSync foreground time limit reached; will resume later")
        } else {
            Log.i(TAG, "Upload stopped, reason=$reason")
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock(): WifiManager.WifiLock? = try {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Wi-Fi lock unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(LOCK_TIMEOUT.toMillis())
        }
    } catch (e: Exception) {
        Log.w(TAG, "Wake lock unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun release(wifiLock: WifiManager.WifiLock?, wakeLock: PowerManager.WakeLock?) {
        try {
            if (wifiLock?.isHeld == true) wifiLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi lock release failed")
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock release failed")
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        /** Boolean input: true for user-initiated runs. */
        const val KEY_MANUAL = "manual"
        /** Attempts per run (first try + retries). */
        const val MAX_ATTEMPTS = 5
        val STALE_AFTER: Duration = Duration.ofHours(36)
        private val LOCK_TIMEOUT: Duration = Duration.ofHours(4)
        private const val NOTIFY_INTERVAL_MILLIS = 1_000L
        private const val WIFI_LOCK_TAG = "SignalBackup:UploadWifiLock"
        private const val WAKE_LOCK_TAG = "SignalBackup:UploadWakeLock"
    }
}
