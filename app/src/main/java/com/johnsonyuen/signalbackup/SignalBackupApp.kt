package com.johnsonyuen.signalbackup

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.johnsonyuen.signalbackup.domain.scheduling.UploadScheduler
import com.johnsonyuen.signalbackup.notification.NotificationChannels
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Hilt application; supplies WorkManager's configuration and arms the daily schedule on start. */
@HiltAndroidApp
class SignalBackupApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Lazy so WorkManager isn't initialized before [workerFactory] is injected. */
    @Inject
    lateinit var uploadScheduler: Lazy<UploadScheduler>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.create(this)
        appScope.launch {
            try {
                uploadScheduler.get().applySchedule()
            } catch (e: Exception) {
                Log.w(TAG, "Applying schedule failed: ${e.javaClass.simpleName}")
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private companion object {
        const val TAG = "SignalBackupApp"
    }
}
