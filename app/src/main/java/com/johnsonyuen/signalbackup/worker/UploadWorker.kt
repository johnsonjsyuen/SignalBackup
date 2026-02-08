package com.johnsonyuen.signalbackup.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val performUploadUseCase: PerformUploadUseCase
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        return when (val status = performUploadUseCase(applicationContext)) {
            is UploadStatus.Success -> Result.success()
            is UploadStatus.Failed -> {
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val channelId = "upload_channel"
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            applicationContext.getString(R.string.upload_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
