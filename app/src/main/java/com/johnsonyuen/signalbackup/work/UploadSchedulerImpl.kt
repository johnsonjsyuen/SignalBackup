package com.johnsonyuen.signalbackup.work

import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import com.johnsonyuen.signalbackup.domain.scheduling.UploadProgressTracker
import com.johnsonyuen.signalbackup.domain.scheduling.UploadRunState
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.worker.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [UploadScheduler]. The daily run is unique periodic work whose next run is pinned
 * to the chosen wall-clock time with `setNextScheduleTimeOverride`, re-pinned after every scheduled run.
 */
@Singleton
class UploadSchedulerImpl @Inject constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    private val progressTracker: UploadProgressTracker,
    private val clock: Clock,
) : UploadScheduler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scheduleMutex = Mutex()

    override suspend fun applySchedule() {
        scheduleMutex.withLock {
            LEGACY_WORK_NAMES.forEach { workManager.cancelUniqueWork(it) }
            val settings = settingsRepository.current()
            if (!settings.scheduleEnabled || !settings.isSetupComplete) {
                Log.d(TAG, "Daily schedule off")
                workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }
            val existing = currentPeriodicWork()
            val tag = scheduleTag(settings)
            when {
                // Nothing armed: start a fresh period pinned to the chosen time.
                existing == null ->
                    enqueuePeriodic(settings, replace = true, pin = true)
                // Same time: only constraints may differ; keep the current timing.
                tag in existing.tags ->
                    enqueuePeriodic(settings, replace = false, pin = false)
                // Time changed while a run is in progress: don't interrupt it, re-pin the next run.
                existing.state == WorkInfo.State.RUNNING ->
                    enqueuePeriodic(settings, replace = false, pin = true)
                else ->
                    enqueuePeriodic(settings, replace = true, pin = true)
            }
        }
    }

    override suspend fun onScheduledRunFinished() {
        scheduleMutex.withLock {
            val settings = settingsRepository.current()
            if (!settings.scheduleEnabled || !settings.isSetupComplete) return
            enqueuePeriodic(settings, replace = false, pin = true)
        }
    }

    override suspend fun enqueueManualUpload() {
        val settings = settingsRepository.current()
        // Expedited work only supports network and storage constraints.
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(networkType(settings.wifiOnly)).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY)
            .setInputData(workDataOf(UploadWorker.KEY_MANUAL to true))
            .addTag(MANUAL_TAG)
            .build()
        workManager.enqueueUniqueWork(MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelRunningUploads() {
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
        progressTracker.update(null)
        scope.launch {
            try {
                scheduleMutex.withLock {
                    val existing = currentPeriodicWork() ?: return@withLock
                    val busy = existing.state == WorkInfo.State.RUNNING || existing.runAttemptCount > 0
                    if (!busy) return@withLock
                    val settings = settingsRepository.current()
                    if (settings.scheduleEnabled && settings.isSetupComplete) {
                        // Replaces (and so stops) the current run while keeping the daily schedule armed.
                        enqueuePeriodic(settings, replace = true, pin = true)
                    } else {
                        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-arm schedule after cancel: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(MANUAL_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        LEGACY_WORK_NAMES.forEach { workManager.cancelUniqueWork(it) }
        progressTracker.update(null)
    }

    override fun observeRunState(): Flow<UploadRunState> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(MANUAL_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK_NAME),
        progressTracker.progress,
    ) { manual, periodic, progress ->
        runStateOf(manual, periodic, progress, clock.millis())
    }.distinctUntilChanged()

    override fun observeNextScheduledRunMillis(): Flow<Long?> =
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK_NAME)
            .map { infos ->
                infos.firstOrNull { !it.state.isFinished }
                    ?.nextScheduleTimeMillis
                    ?.takeIf { it != Long.MAX_VALUE }
            }
            .distinctUntilChanged()

    private suspend fun currentPeriodicWork(): WorkInfo? =
        workManager.getWorkInfosForUniqueWorkFlow(PERIODIC_WORK_NAME).first()
            .firstOrNull { !it.state.isFinished }

    /**
     * Enqueues the daily work. [replace] starts a fresh period (cancelling any current run first);
     * otherwise the existing work is updated in place.
     *
     * WorkManager rejects a next-schedule-time override combined with CANCEL_AND_REENQUEUE, so a
     * replacement is an explicit cancel followed by UPDATE (which enqueues anew once the old work is finished).
     */
    private suspend fun enqueuePeriodic(
        settings: AppSettings,
        replace: Boolean,
        pin: Boolean,
    ) {
        if (replace) workManager.cancelUniqueWork(PERIODIC_WORK_NAME).await()
        val now = ZonedDateTime.now(clock)
        val next = DailySchedule.nextRun(now, settings.scheduleHour, settings.scheduleMinute)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType(settings.wifiOnly))
            .setRequiresBatteryNotLow(true)
            .build()
        val builder = PeriodicWorkRequestBuilder<UploadWorker>(REPEAT_INTERVAL, FLEX_INTERVAL)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_DELAY)
            .setInitialDelay(Duration.between(now, next))
            .addTag(scheduleTag(settings))
        // Flex would otherwise push the first run (interval - flex) past the initial delay.
        if (pin) builder.setNextScheduleTimeOverride(next.toInstant().toEpochMilli())
        workManager
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, builder.build())
            .await()
        Log.d(TAG, "Daily schedule applied (replaced=$replace, pinned=$pin)")
    }

    private fun scheduleTag(settings: AppSettings): String =
        String.format(
            Locale.ROOT, "%s%02d:%02d@%s",
            SCHEDULE_TAG_PREFIX, settings.scheduleHour, settings.scheduleMinute, clock.zone.id,
        )

    companion object {
        private const val TAG = "UploadScheduler"
        const val PERIODIC_WORK_NAME = "signal_backup_daily"
        const val MANUAL_WORK_NAME = "signal_backup_manual"
        const val MANUAL_TAG = "manual_upload"
        const val SCHEDULE_TAG_PREFIX = "schedule:"
        val REPEAT_INTERVAL: Duration = Duration.ofHours(24)
        val FLEX_INTERVAL: Duration = Duration.ofHours(2)
        val BACKOFF_DELAY: Duration = Duration.ofMinutes(15)

        /** Unique work names used by older app versions. */
        private val LEGACY_WORK_NAMES = listOf(
            "signal_backup_scheduled_upload",
            "signal_backup_retry_upload",
            "signal_backup_manual_upload",
        )

        private fun networkType(wifiOnly: Boolean) =
            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        /** Maps WorkManager state plus live progress to a UI-facing run state. */
        internal fun runStateOf(
            manual: List<WorkInfo>,
            periodic: List<WorkInfo>,
            progress: UploadProgress?,
            nowMillis: Long,
        ): UploadRunState {
            val all = manual + periodic
            if (all.any { it.state == WorkInfo.State.RUNNING }) return UploadRunState.Running(progress)
            if (manual.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) {
                return UploadRunState.Queued(manual = true)
            }
            val periodicWaiting = periodic.any {
                it.state == WorkInfo.State.ENQUEUED &&
                    (it.runAttemptCount > 0 || it.nextScheduleTimeMillis <= nowMillis)
            }
            return if (periodicWaiting) UploadRunState.Queued(manual = false) else UploadRunState.Idle
        }
    }
}
