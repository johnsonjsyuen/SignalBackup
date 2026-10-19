package com.johnsonyuen.signalbackup.di

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.Identity
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.gson.GsonFactory
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizer
import com.johnsonyuen.signalbackup.data.auth.DriveAuthorizerImpl
import com.johnsonyuen.signalbackup.data.auth.GoogleAccountSignIn
import com.johnsonyuen.signalbackup.data.auth.GoogleAccountSignInImpl
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.DriveRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Drive HTTP stack, Play services authorization, and the Drive repository binding. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DriveModule {

    @Binds
    @Singleton
    abstract fun bindDriveRepository(impl: DriveRepositoryImpl): DriveRepository

    @Binds
    @Singleton
    abstract fun bindDriveAuthorizer(impl: DriveAuthorizerImpl): DriveAuthorizer

    @Binds
    abstract fun bindGoogleAccountSignIn(impl: GoogleAccountSignInImpl): GoogleAccountSignIn

    companion object {
        @Provides
        @Singleton
        fun provideHttpTransport(): HttpTransport = NetHttpTransport()

        @Provides
        @Singleton
        fun provideJsonFactory(): JsonFactory = GsonFactory.getDefaultInstance()

        @Provides
        @Singleton
        fun provideAuthorizationClient(@ApplicationContext context: Context): AuthorizationClient =
            Identity.getAuthorizationClient(context)
    }
}
