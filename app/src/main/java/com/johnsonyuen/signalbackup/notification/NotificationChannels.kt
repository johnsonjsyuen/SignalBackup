package com.johnsonyuen.signalbackup.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.johnsonyuen.signalbackup.MainActivity
import com.johnsonyuen.signalbackup.R

/** Notification channel ids, notification ids and shared helpers. */
object NotificationChannels {
    const val CHANNEL_PROGRESS = "upload_progress"
    const val CHANNEL_PROBLEMS = "backup_problems"

    /** Channel used by older versions; deleted on start. */
    private const val LEGACY_CHANNEL = "upload_channel"

    const val ID_PROGRESS = 1001
    const val ID_FAILURE = 2001
    const val ID_CONSENT = 2002
    const val ID_STALE = 2003

    /** Creates (or updates) both channels. Safe to call repeatedly. */
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            context.getString(R.string.notif_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = context.getString(R.string.notif_channel_progress_desc) }
        val problems = NotificationChannel(
            CHANNEL_PROBLEMS,
            context.getString(R.string.notif_channel_problems_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.notif_channel_problems_desc) }
        manager.createNotificationChannels(listOf(progress, problems))
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
    }

    /** True when the app may post notifications (permission granted and not blocked). */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Opens MainActivity, optionally with a [NotificationIntents.EXTRA_FIX_ACTION]. */
    fun openAppIntent(context: Context, fixAction: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (fixAction != null) putExtra(NotificationIntents.EXTRA_FIX_ACTION, fixAction)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
