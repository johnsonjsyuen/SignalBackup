package com.johnsonyuen.signalbackup.data.auth

import android.app.Activity

/** Credential Manager "Sign in with Google" (email only). Interface so ViewModel tests can fake it. */
interface GoogleAccountSignIn {
    /**
     * Shows the account picker. Needs an Activity context. Returns the outcome for a credential
     * that came back; failures from Credential Manager itself are thrown.
     *
     * @throws androidx.credentials.exceptions.GetCredentialException when Credential Manager fails
     *   or is cancelled (Play services also reports some configuration errors as cancellation).
     */
    suspend fun signIn(activity: Activity): GoogleSignInResult
}

/** Outcome of [GoogleAccountSignIn.signIn] when Credential Manager returned a credential. */
sealed interface GoogleSignInResult {
    data class Success(val email: String) : GoogleSignInResult
    /** The credential was not a Google ID token. [type] is the credential type (safe to log). */
    data class UnexpectedCredential(val type: String) : GoogleSignInResult
    /** The Google ID token could not be parsed. */
    data object InvalidToken : GoogleSignInResult
    /** The token carried no email address. */
    data object NoEmail : GoogleSignInResult
}
