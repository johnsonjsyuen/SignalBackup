package com.johnsonyuen.signalbackup.domain.usecase

import android.content.Context
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.receiver.UploadAlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Use case that schedules (or cancels) a daily backup upload via AlarmManager.
 *
 * Reads the user's preferred schedule time from settings and delegates
 * alarm scheduling to [UploadAlarmReceiver].
 */
class ScheduleUploadUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    /** Schedules a daily upload alarm at the user's configured time. */
    suspend operator fun invoke() {
        val hour = settingsRepository.scheduleHour.first()
        val minute = settingsRepository.scheduleMinute.first()
        UploadAlarmReceiver.scheduleNextAlarm(context, hour, minute)
    }

    /** Cancels any scheduled upload alarm. */
    fun cancel() {
        UploadAlarmReceiver.cancelAlarm(context)
    }
}
