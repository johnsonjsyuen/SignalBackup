package com.johnsonyuen.signalbackup.notification

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.johnsonyuen.signalbackup.R
import com.johnsonyuen.signalbackup.domain.model.FailureReason
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/** [BackupNotifier] posting to the "Backup problems" channel. */
@Singleton
class BackupNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupNotifier {

    override fun notifyFailure(reason: FailureReason, detail: String) {
        if (reason == FailureReason.NEEDS_CONSENT) {
            notifyNeedsConsent()
            return
        }
        val text = context.getString(failureTextRes(reason))
        val bigText = if (detail.isBlank()) text else "$text\n$detail"
        post(
            id = NotificationChannels.ID_FAILURE,
            title = context.getString(R.string.notif_failure_title),
            text = text,
            bigText = bigText,
            fixAction = fixActionFor(reason),
        )
    }

    override fun notifyNeedsConsent() {
        post(
            id = NotificationChannels.ID_CONSENT,
            title = context.getString(R.string.notif_consent_title),
            text = context.getString(R.string.notif_consent_text),
            bigText = null,
            fixAction = NotificationIntents.ACTION_CONSENT,
        )
    }

    override fun notifyStale(lastSuccessMillis: Long?, newestBackupTimeMillis: Long?) {
        val text = when {
            newestBackupTimeMillis != null ->
                context.getString(R.string.notif_stale_no_new_backup, formatDate(newestBackupTimeMillis))
            lastSuccessMillis != null ->
                context.getString(R.string.notif_stale_last_success, formatDate(lastSuccessMillis))
            else -> context.getString(R.string.notif_stale_never)
        }
        post(
            id = NotificationChannels.ID_STALE,
            title = context.getString(R.string.notif_stale_title),
            text = text,
            bigText = text,
            fixAction = NotificationIntents.ACTION_HISTORY,
        )
    }

    override fun clearProblemNotifications() {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NotificationChannels.ID_FAILURE)
        manager.cancel(NotificationChannels.ID_CONSENT)
        manager.cancel(NotificationChannels.ID_STALE)
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, title: String, text: String, bigText: String?, fixAction: String) {
        if (!NotificationChannels.canPost(context)) return
        val builder = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(NotificationChannels.openAppIntent(context, fixAction, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (bigText != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "Problem notification not allowed")
        }
    }

    private fun formatDate(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    companion object {
        private const val TAG = "BackupNotifier"

        /** Which screen fixes a failure: consent, Settings for setup problems, else History. */
        fun fixActionFor(reason: FailureReason): String = when (reason) {
            FailureReason.NEEDS_CONSENT -> NotificationIntents.ACTION_CONSENT
            FailureReason.NOT_SIGNED_IN,
            FailureReason.LOCAL_FOLDER_NOT_SET,
            FailureReason.LOCAL_FOLDER_INACCESSIBLE,
            FailureReason.NO_BACKUP_FOUND,
            FailureReason.DRIVE_FOLDER_INACCESSIBLE -> NotificationIntents.ACTION_SETTINGS
            FailureReason.DRIVE_QUOTA_EXCEEDED,
            FailureReason.NETWORK,
            FailureReason.DRIVE_SERVER_ERROR,
            FailureReason.CHECKSUM_MISMATCH,
            FailureReason.LOCAL_READ_ERROR,
            FailureReason.UNKNOWN -> NotificationIntents.ACTION_HISTORY
        }

        @StringRes
        fun failureTextRes(reason: FailureReason): Int = when (reason) {
            FailureReason.NOT_SIGNED_IN -> R.string.notif_fail_not_signed_in
            FailureReason.LOCAL_FOLDER_NOT_SET -> R.string.notif_fail_local_folder_not_set
            FailureReason.LOCAL_FOLDER_INACCESSIBLE -> R.string.notif_fail_local_folder_inaccessible
            FailureReason.NO_BACKUP_FOUND -> R.string.notif_fail_no_backup
            FailureReason.NEEDS_CONSENT -> R.string.notif_consent_text
            FailureReason.DRIVE_FOLDER_INACCESSIBLE -> R.string.notif_fail_drive_folder
            FailureReason.DRIVE_QUOTA_EXCEEDED -> R.string.notif_fail_quota
            FailureReason.NETWORK -> R.string.notif_fail_network
            FailureReason.DRIVE_SERVER_ERROR -> R.string.notif_fail_server
            FailureReason.CHECKSUM_MISMATCH -> R.string.notif_fail_checksum
            FailureReason.LOCAL_READ_ERROR -> R.string.notif_fail_local_read
            FailureReason.UNKNOWN -> R.string.notif_fail_unknown
        }
    }
}
