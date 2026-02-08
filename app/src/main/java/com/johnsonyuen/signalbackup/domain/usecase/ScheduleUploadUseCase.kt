/**
 * ScheduleUploadUseCase.kt - Use case for scheduling periodic backup uploads via WorkManager.
 *
 * This use case sets up a recurring daily upload using Android's WorkManager, which is
 * the recommended way to schedule reliable background work that survives app restarts
 * and device reboots.
 *
 * How the scheduling works:
 * 1. Read the user's preferred schedule time (hour + minute) from settings.
 * 2. Calculate the initial delay from now until the next occurrence of that time.
 * 3. Create a PeriodicWorkRequest that repeats every 24 hours with a 30-minute flex window.
 * 4. Enqueue the request with UPDATE policy (replaces any existing schedule).
 *
 * WorkManager concepts used:
 * - **PeriodicWorkRequest**: Runs the worker approximately every 24 hours. The 30-minute
 *   flex window means the actual execution can happen within 30 minutes of the target time.
 * - **Constraints**: Only runs when the device has network and the battery is not low.
 * - **BackoffPolicy.EXPONENTIAL**: If the upload fails and retries, each retry waits
 *   exponentially longer (15min, 30min, 60min...) to avoid hammering the network.
 * - **ExistingPeriodicWorkPolicy.UPDATE**: If a schedule already exists, replace it
 *   with the new timing (e.g., when the user changes the schedule time).
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/usecase package).
 * - Called by [SettingsViewModel.setScheduleTime()] whenever the user changes the schedule.
 *
 * @see worker.UploadWorker for the Worker that actually performs the upload
 * @see ui.screen.settings.SettingsViewModel for where the schedule is set
 */
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

/**
 * Use case that schedules (or reschedules) a daily periodic upload via WorkManager.
 *
 * @param workManager The WorkManager instance for enqueueing work requests.
 * @param settingsRepository For reading the user's preferred schedule time.
 */
class ScheduleUploadUseCase @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository
) {
    /**
     * Schedules a daily upload at the user's configured time.
     *
     * Calculates the initial delay so the first execution happens at the next occurrence
     * of the configured time. If the time has already passed today, it schedules for
     * tomorrow. Subsequent executions repeat every 24 hours.
     */
    suspend operator fun invoke() {
        val hour = settingsRepository.scheduleHour.first()
        val minute = settingsRepository.scheduleMinute.first()

        // Calculate the delay until the next scheduled time.
        val now = LocalDateTime.now()
        var target = now.with(LocalTime.of(hour, minute))

        // If the target time has already passed today, schedule for tomorrow.
        if (target.isBefore(now) || target.isEqual(now)) {
            target = target.plusDays(1)
        }
        val initialDelay = Duration.between(now, target)

        // Constraints ensure the upload only runs when conditions are favorable:
        // - Network is connected (needed for Drive upload)
        // - Battery is not low (upload can be power-intensive for large files)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Create a periodic work request that runs every 24 hours.
        // The 30-minute flex window means WorkManager can execute the worker
        // within the last 30 minutes of each 24-hour period, allowing for
        // battery-optimal scheduling while keeping the timing close to the target.
        val request = PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setInitialDelay(initialDelay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        // Enqueue with UPDATE policy: if a schedule already exists, replace it.
        // This is called every time the user changes the schedule time in Settings.
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /**
     * Cancels any scheduled upload work.
     * Could be used if the user wants to disable automatic backups entirely.
     */
    fun cancel() {
        workManager.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        /** Unique name for the periodic work, used to identify and update/cancel it. */
        const val WORK_NAME = "signal_backup_upload"
    }
}
