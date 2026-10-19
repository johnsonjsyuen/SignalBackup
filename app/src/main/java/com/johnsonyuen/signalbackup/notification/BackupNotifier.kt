package com.johnsonyuen.signalbackup.notification

import com.johnsonyuen.signalbackup.domain.model.FailureReason

/**
 * User-visible problem notifications. Each opens MainActivity with
 * [NotificationIntents.EXTRA_FIX_ACTION] set so the app can take the user straight to the fix.
 * Implementations are no-ops when notification permission is missing.
 */
interface BackupNotifier {
    fun notifyFailure(reason: FailureReason, detail: String)
    fun notifyNeedsConsent()
    /** Backups haven't succeeded, or Signal hasn't written a new backup, for too long. */
    fun notifyStale(lastSuccessMillis: Long?, newestBackupTimeMillis: Long?)
    fun clearProblemNotifications()
}

object NotificationIntents {
    /** String extra on the MainActivity intent. */
    const val EXTRA_FIX_ACTION = "com.johnsonyuen.signalbackup.FIX_ACTION"
    /** Re-run Drive authorization, then upload. */
    const val ACTION_CONSENT = "consent"
    /** Open the Settings tab (folder, permissions). */
    const val ACTION_SETTINGS = "settings"
    /** Open the History tab. */
    const val ACTION_HISTORY = "history"
}
