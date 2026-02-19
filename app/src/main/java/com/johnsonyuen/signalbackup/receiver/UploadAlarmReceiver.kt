package com.johnsonyuen.signalbackup.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.johnsonyuen.signalbackup.worker.UploadWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Calendar
import java.util.concurrent.TimeUnit

class UploadAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm fired, enqueuing scheduled upload work")

        // Read the Wi-Fi only preference from DataStore.
        // runBlocking is acceptable here because onReceive() runs on the main thread
        // with a short time limit, and DataStore reads from disk are fast.
        val wifiOnly = runBlocking {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                UploadAlarmReceiverEntryPoint::class.java
            )
            entryPoint.settingsRepository().wifiOnly.first()
        }
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )

        // Re-schedule alarm for tomorrow at the same time
        val hour = intent.getIntExtra(EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(EXTRA_MINUTE, -1)
        if (hour >= 0 && minute >= 0) {
            scheduleNextAlarm(context, hour, minute)
        } else {
            Log.w(TAG, "Missing hour/minute extras, cannot re-schedule alarm")
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface UploadAlarmReceiverEntryPoint {
        fun settingsRepository(): SettingsRepository
    }

    companion object {
        private const val TAG = "UploadAlarmReceiver"
        const val WORK_NAME = "signal_backup_scheduled_upload"
        private const val EXTRA_HOUR = "extra_hour"
        private const val EXTRA_MINUTE = "extra_minute"
        private const val REQUEST_CODE = 1001

        fun scheduleNextAlarm(context: Context, hour: Int, minute: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val triggerAtMillis = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // If the time has already passed today, schedule for tomorrow
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }.timeInMillis

            val pendingIntent = createPendingIntent(context, hour, minute)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
            }

            Log.d(TAG, "Scheduled alarm for %02d:%02d".format(hour, minute))
        }

        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = createPendingIntent(context, 0, 0)
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Cancelled scheduled alarm")
        }

        private fun createPendingIntent(context: Context, hour: Int, minute: Int): PendingIntent {
            val intent = Intent(context, UploadAlarmReceiver::class.java).apply {
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
