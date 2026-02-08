/**
 * SignalBackupApp.kt - Custom Application class for the Signal Backup app.
 *
 * This file is the entry point for the entire Android application. It serves two critical roles:
 *
 * 1. **Hilt Dependency Injection**: The [@HiltAndroidApp] annotation triggers Hilt's code
 *    generation at compile time, creating the Dagger component hierarchy that makes
 *    dependency injection work throughout the app. Every Hilt-enabled Android app MUST
 *    have exactly one Application class annotated with [@HiltAndroidApp].
 *
 * 2. **Custom WorkManager Initialization**: By implementing [Configuration.Provider], this
 *    class takes control of how WorkManager is initialized. Instead of using the default
 *    automatic initializer (which we disable in AndroidManifest.xml), we provide a custom
 *    configuration that uses Hilt's [HiltWorkerFactory]. This allows our [UploadWorker]
 *    to receive injected dependencies (like [PerformUploadUseCase]) via Hilt's
 *    @AssistedInject mechanism.
 *
 * Without this custom WorkManager setup, there would be no way to inject dependencies
 * into WorkManager workers, and we would have to manually construct them -- defeating
 * the purpose of using a DI framework.
 *
 * @see worker.UploadWorker for the Worker that benefits from this DI setup
 * @see di.AppModule for the WorkManager instance provider
 */
package com.johnsonyuen.signalbackup

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.johnsonyuen.signalbackup.domain.usecase.ScheduleUploadUseCase
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Application class for Signal Backup.
 *
 * Annotated with [@HiltAndroidApp] to enable Hilt dependency injection across the entire app.
 * Implements [Configuration.Provider] to supply a custom WorkManager configuration that
 * integrates Hilt's worker factory for DI-enabled workers.
 */
@HiltAndroidApp
class SignalBackupApp : Application(), Configuration.Provider {

    /**
     * Hilt-provided worker factory that knows how to create @HiltWorker-annotated workers.
     *
     * This is injected by Hilt after the Application is created. The [@Inject] annotation
     * on a `lateinit var` field tells Hilt to perform field injection -- it finds the
     * [HiltWorkerFactory] in the dependency graph and assigns it here.
     *
     * We use field injection (rather than constructor injection) because Android creates
     * the Application class itself -- we cannot control its constructor parameters.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Use case for scheduling periodic backup uploads via WorkManager.
     *
     * Injected here so we can ensure the periodic upload schedule is always enqueued
     * on app startup. Without this, the schedule would only be set when the user
     * explicitly changes the schedule time in Settings, meaning it would be lost
     * after a device reboot or app force-stop.
     */
    @Inject
    lateinit var scheduleUploadUseCase: ScheduleUploadUseCase

    override fun onCreate() {
        super.onCreate()
        // Ensure the upload alarm is registered on every app startup.
        // Safe to call repeatedly — it replaces any existing alarm.
        CoroutineScope(Dispatchers.Default).launch {
            scheduleUploadUseCase()
        }
    }

    /**
     * Custom WorkManager configuration that uses the Hilt worker factory.
     *
     * This property is called by WorkManager when it initializes (on first use).
     * By returning a Configuration that includes our [workerFactory], WorkManager
     * will delegate worker creation to Hilt, enabling dependency injection in
     * our UploadWorker class.
     *
     * The `get()` syntax makes this a computed property -- it builds a fresh
     * Configuration each time it is accessed (though in practice WorkManager
     * only reads it once during initialization).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
