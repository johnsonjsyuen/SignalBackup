package com.johnsonyuen.signalbackup.data.auth

import android.app.Activity
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.johnsonyuen.signalbackup.BuildConfig
import javax.inject.Inject

/** [GoogleAccountSignIn] backed by Credential Manager and the googleid library. */
class GoogleAccountSignInImpl @Inject constructor() : GoogleAccountSignIn {

    override suspend fun signIn(activity: Activity): GoogleSignInResult {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential

        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Log.w(TAG, "Unexpected credential type: ${credential.type}")
            return GoogleSignInResult.UnexpectedCredential(credential.type)
        }
        val email = try {
            GoogleIdTokenCredential.createFrom(credential.data).id
        } catch (e: GoogleIdTokenParsingException) {
            Log.w(TAG, "Invalid Google ID token response")
            return GoogleSignInResult.InvalidToken
        }
        return if (email.isNotBlank()) GoogleSignInResult.Success(email) else GoogleSignInResult.NoEmail
    }

    private companion object {
        const val TAG = "GoogleAccountSignIn"
    }
}
