package com.johnsonyuen.signalbackup.di

import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.notification.BackupNotifier
import com.johnsonyuen.signalbackup.notification.BackupNotifierImpl
import com.johnsonyuen.signalbackup.work.UploadSchedulerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds scheduling and notification implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkModule {
    @Binds
    @Singleton
    abstract fun bindUploadScheduler(impl: UploadSchedulerImpl): UploadScheduler

    @Binds
    @Singleton
    abstract fun bindBackupNotifier(impl: BackupNotifierImpl): BackupNotifier
}
