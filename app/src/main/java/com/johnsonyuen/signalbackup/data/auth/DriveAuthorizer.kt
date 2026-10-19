package com.johnsonyuen.signalbackup.data.auth

import android.app.PendingIntent
import android.content.Intent

/**
 * Drive authorization via Play services' AuthorizationClient (Identity.getAuthorizationClient)
 * for the `https://www.googleapis.com/auth/drive.file` scope, for the account stored in settings.
 */
interface DriveAuthorizer {
    /**
     * Background-safe. Returns an OAuth access token.
     * @throws com.johnsonyuen.signalbackup.domain.model.NotSignedInException if no account is set.
     * @throws com.johnsonyuen.signalbackup.domain.model.NeedsConsentException if the user must approve.
     */
    suspend fun getAccessToken(): String

    /** Drops a cached token after Drive rejected it with 401, so the next call fetches a new one. */
    suspend fun invalidateToken(token: String)

    /**
     * Foreground. Returns null when already authorized, or a PendingIntent whose IntentSender the
     * UI launches to show Google's consent screen.
     */
    suspend fun requestAuthorization(): PendingIntent?

    /** Handles the consent screen's result Intent. Returns true when access was granted. */
    suspend fun handleAuthorizationResult(data: Intent?): Boolean

    /** Revokes the Drive grant and clears cached tokens (sign-out). Never throws. */
    suspend fun revokeAccess()
}
