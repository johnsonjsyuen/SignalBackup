package com.johnsonyuen.signalbackup.domain.scheduling

import com.johnsonyuen.signalbackup.domain.model.UploadProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Owns all upload WorkManager work. */
interface UploadScheduler {
    /**
     * Reads settings and (re)applies the daily schedule: enqueues/updates unique periodic work when
     * scheduling is enabled and setup is complete, otherwise cancels it. Safe to call repeatedly;
     * call on app start and whenever schedule time, Wi-Fi-only, enabled or setup state changes.
     */
    suspend fun applySchedule()

    /** Enqueues an expedited one-time upload (no-op if one is already queued or running). */
    suspend fun enqueueManualUpload()

    /** Cancels queued/running manual and scheduled runs (the daily schedule stays armed). */
    fun cancelRunningUploads()

    /** Cancels everything, including the daily schedule (sign-out). */
    fun cancelAll()

    /** Combined state of upload work for the UI. */
    fun observeRunState(): Flow<UploadRunState>

    /** Epoch millis of the next scheduled daily run, or null when not scheduled. */
    fun observeNextScheduledRunMillis(): Flow<Long?>
}

sealed interface UploadRunState {
    data object Idle : UploadRunState
    /** Waiting for constraints (network, Wi-Fi) or quota. */
    data class Queued(val manual: Boolean) : UploadRunState
    data class Running(val progress: UploadProgress?) : UploadRunState
}

/** Process-wide live progress, written by UploadWorker and read by UploadScheduler/UI. */
@Singleton
class UploadProgressTracker @Inject constructor() {
    private val _progress = MutableStateFlow<UploadProgress?>(null)
    val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()
    fun update(progress: UploadProgress?) {
        _progress.value = progress
    }
}
