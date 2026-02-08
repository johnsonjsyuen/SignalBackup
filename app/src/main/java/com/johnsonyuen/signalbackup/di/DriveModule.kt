/**
 * DriveModule.kt - Hilt module providing Google Drive authentication dependencies.
 *
 * This module provides the objects needed for Google Drive API authentication:
 * - [GoogleAccountCredential]: The OAuth2 credential used to authenticate Drive API calls.
 * - [AuthorizationClient]: Used by the UI to request OAuth consent from the user.
 *
 * Authentication flow overview:
 * 1. User signs in via GoogleSignIn on the Home screen.
 * 2. The email is stored in DataStore.
 * 3. Before each Drive API call, GoogleDriveService reads the email and sets it on
 *    the GoogleAccountCredential.
 * 4. GoogleAccountCredential uses the Android account system to obtain an OAuth token.
 * 5. If the token is expired or the scope hasn't been granted, the Drive API throws
 *    UserRecoverableAuthIOException, and the UI launches the consent Intent.
 *
 * Architecture context:
 * - Part of the **DI layer** (di package).
 * - Both providers are singletons because there should be exactly one credential
 *   and one authorization client instance per app.
 *
 * @see data.remote.GoogleDriveService for where the credential is used
 * @see ui.screen.home.HomeScreen for where sign-in and consent are handled
 */
package com.johnsonyuen.signalbackup.di

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.Identity
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Google Drive and Google Sign-In dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    /**
     * Provides the OAuth2 credential for Google Drive API authentication.
     *
     * Configured with [DriveScopes.DRIVE] which grants the app full read/write access
     * to the user's Google Drive. This broader scope is required so the folder picker
     * can list ALL existing folders in the user's Drive -- not just folders created by
     * this app. The narrower DRIVE_FILE scope only allows access to files/folders the
     * app itself created, which causes the folder picker to show an empty list.
     *
     * The credential's `selectedAccountName` is set dynamically by GoogleDriveService
     * before each API call -- it is NOT set here during construction.
     */
    @Provides
    @Singleton
    fun provideGoogleAccountCredential(@ApplicationContext context: Context): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE)
        )

    /**
     * Provides the Google Identity AuthorizationClient.
     *
     * Used to request authorization (OAuth consent) from the user for Drive access.
     * This is part of Google's newer Identity Services API.
     */
    @Provides
    @Singleton
    fun provideAuthorizationClient(@ApplicationContext context: Context): AuthorizationClient =
        Identity.getAuthorizationClient(context)
}
