package com.johnsonyuen.signalbackup.domain.usecase

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.worker.UploadWorker
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ScheduleRetryUseCase @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(error: String) {
        // Check if next regular backup is within 1 hour
        val scheduleHour = settingsRepository.scheduleHour.first()
        val scheduleMinute = settingsRepository.scheduleMinute.first()

        val now = LocalDateTime.now()
        var nextScheduled = now.with(LocalTime.of(scheduleHour, scheduleMinute))
        if (nextScheduled.isBefore(now) || nextScheduled.isEqual(now)) {
            nextScheduled = nextScheduled.plusDays(1)
        }

        val timeUntilScheduled = Duration.between(now, nextScheduled)
        if (timeUntilScheduled.toMinutes() <= 60) {
            Log.d(TAG, "Next regular backup in ${timeUntilScheduled.toMinutes()}m, skipping retry")
            return
        }

        // Read wifi preference for constraints
        val wifiOnly = settingsRepository.wifiOnly.first()
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInitialDelay(RETRY_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        val retryAtMillis = System.currentTimeMillis() + RETRY_DELAY_MINUTES * 60 * 1000
        settingsRepository.setRetryScheduled(retryAtMillis, error)

        Log.d(TAG, "Retry scheduled in ${RETRY_DELAY_MINUTES}m for error: $error")
    }

    suspend fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
        settingsRepository.clearRetryScheduled()
        Log.d(TAG, "Retry cancelled")
    }

    companion object {
        private const val TAG = "ScheduleRetryUseCase"
        const val WORK_NAME = "signal_backup_retry_upload"
        private const val RETRY_DELAY_MINUTES = 30L
    }
}
