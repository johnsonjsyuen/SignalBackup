package com.johnsonyuen.signalbackup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.johnsonyuen.signalbackup.domain.usecase.ScheduleUploadUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-registers the scheduled upload alarm after device reboot.
 *
 * AlarmManager alarms and WorkManager periodic schedules can be lost on reboot,
 * so this receiver re-invokes [ScheduleUploadUseCase] to restore the schedule.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduleUploadUseCase: ScheduleUploadUseCase

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Boot completed, re-scheduling upload")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                scheduleUploadUseCase()
                Log.d(TAG, "Upload schedule restored after reboot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-schedule upload after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
