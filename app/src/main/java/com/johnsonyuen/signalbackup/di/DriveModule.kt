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

@Module
@InstallIn(SingletonComponent::class)
object DriveModule {

    @Provides
    @Singleton
    fun provideGoogleAccountCredential(@ApplicationContext context: Context): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )

    @Provides
    @Singleton
    fun provideAuthorizationClient(@ApplicationContext context: Context): AuthorizationClient =
        Identity.getAuthorizationClient(context)
}
