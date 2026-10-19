package com.johnsonyuen.signalbackup.domain.upload

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings for the upload engine's Android-backed seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds
    abstract fun bindLocalFileSource(impl: SafLocalFileSource): LocalFileSource

    @Binds
    abstract fun bindUnmeteredNetworkChecker(impl: AndroidUnmeteredNetworkChecker): UnmeteredNetworkChecker
}
