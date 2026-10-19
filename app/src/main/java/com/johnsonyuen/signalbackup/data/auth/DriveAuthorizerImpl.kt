package com.johnsonyuen.signalbackup.data.auth

import android.accounts.Account
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.NeedsConsentException
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** [DriveAuthorizer] backed by Play services' [AuthorizationClient] for the `drive.file` scope. */
@Singleton
class DriveAuthorizerImpl @Inject constructor(
    private val client: AuthorizationClient,
    private val settings: SettingsRepository,
) : DriveAuthorizer {

    override suspend fun getAccessToken(): String {
        val result = authorize(requireAccount())
        if (result.hasResolution()) throw NeedsConsentException()
        return result.accessToken?.takeIf { it.isNotEmpty() } ?: throw NeedsConsentException()
    }

    override suspend fun invalidateToken(token: String) {
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clearToken failed: ${e.javaClass.simpleName}")
        }
    }

    override suspend fun requestAuthorization(): PendingIntent? {
        val result = authorize(requireAccount())
        return if (result.hasResolution()) result.pendingIntent else null
    }

    override suspend fun handleAuthorizationResult(data: Intent?): Boolean {
        if (data == null) return false
        return try {
            val result = client.getAuthorizationResultFromIntent(data)
            !result.accessToken.isNullOrEmpty() || DRIVE_FILE_SCOPE in result.grantedScopes
        } catch (e: ApiException) {
            Log.w(TAG, "Authorization not granted (status ${e.statusCode})")
            false
        }
    }

    override suspend fun revokeAccess() {
        try {
            val email = settings.current().googleAccountEmail ?: return
            val request = RevokeAccessRequest.builder()
                .setAccount(Account(email, ACCOUNT_TYPE))
                .setScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
                .build()
            client.revokeAccess(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "revokeAccess failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun requireAccount(): Account {
        val email = settings.current().googleAccountEmail ?: throw NotSignedInException()
        return Account(email, ACCOUNT_TYPE)
    }

    private suspend fun authorize(account: Account): AuthorizationResult {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .setAccount(account)
            .build()
        return try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            throw when (e.statusCode) {
                CommonStatusCodes.SIGN_IN_REQUIRED,
                CommonStatusCodes.RESOLUTION_REQUIRED -> NeedsConsentException()
                else -> IOException("Drive authorization failed (status ${e.statusCode})")
            }
        }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val ACCOUNT_TYPE = "com.google"
        private const val TAG = "DriveAuthorizer"
    }
}
