package com.johnsonyuen.signalbackup.domain.usecase

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.worker.UploadWorker
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class ScheduleUploadUseCase @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke() {
        val hour = settingsRepository.scheduleHour.first()
        val minute = settingsRepository.scheduleMinute.first()

        val now = LocalDateTime.now()
        var target = now.with(LocalTime.of(hour, minute))
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1)
        }
        val initialDelay = Duration.between(now, target)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "signal_backup_upload"
    }
}
