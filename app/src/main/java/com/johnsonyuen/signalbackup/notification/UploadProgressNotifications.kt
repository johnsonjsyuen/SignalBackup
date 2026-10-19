package com.johnsonyuen.signalbackup.notification

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds and posts the ongoing "upload progress" notification used for the foreground worker. */
@Singleton
class UploadProgressNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Progress notification; [progress] null shows an indeterminate "preparing" state. */
    fun build(progress: UploadProgress?): Notification {
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(context.getString(R.string.notif_progress_title))
            .setContentIntent(NotificationChannels.openAppIntent(context, null, REQUEST_CODE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress == null || progress.totalBytes <= 0) {
            builder.setContentText(context.getString(R.string.notif_progress_preparing))
                .setProgress(0, 0, true)
        } else {
            val sent = Formatter.formatShortFileSize(context, progress.bytesUploaded)
            val total = Formatter.formatShortFileSize(context, progress.totalBytes)
            val text = if (progress.speedBytesPerSec > 0) {
                val speed = Formatter.formatShortFileSize(context, progress.speedBytesPerSec)
                context.getString(R.string.notif_progress_text_speed, sent, total, speed)
            } else {
                context.getString(R.string.notif_progress_text, sent, total)
            }
            builder.setContentText(text)
                .setSubText(progress.currentFileName)
                .setProgress(PROGRESS_MAX, progress.percentComplete, false)
        }
        return builder.build()
    }

    /** Posts/updates the progress notification; no-op without notification permission. */
    @SuppressLint("MissingPermission")
    fun show(progress: UploadProgress?) {
        if (!NotificationChannels.canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(NotificationChannels.ID_PROGRESS, build(progress))
        } catch (e: SecurityException) {
            Log.w(TAG, "Progress notification not allowed")
        }
    }

    fun cancel() {
        NotificationManagerCompat.from(context).cancel(NotificationChannels.ID_PROGRESS)
    }

    private companion object {
        const val TAG = "UploadProgressNotif"
        const val PROGRESS_MAX = 100
        const val REQUEST_CODE = 100
    }
}
