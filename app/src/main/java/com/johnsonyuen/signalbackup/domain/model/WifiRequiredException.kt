/**
 * WifiRequiredException.kt - Exception thrown when a Wi-Fi-only upload detects a metered network.
 *
 * This exception is thrown by [PerformUploadUseCase] during the chunked upload loop when the
 * user has the "Wi-Fi only" setting enabled but the device has switched to a metered (mobile
 * data) network mid-upload.
 *
 * [UploadWorker] catches this exception and returns [Result.retry()] so that WorkManager
 * re-enqueues the upload with an UNMETERED network constraint. The resumable upload session
 * is preserved in DataStore, so the upload will resume from the last confirmed byte offset
 * when Wi-Fi becomes available again.
 *
 * @see domain.usecase.PerformUploadUseCase for where this is thrown
 * @see worker.UploadWorker for where this is caught
 */
package com.johnsonyuen.signalbackup.domain.model

/**
 * Signals that the upload was aborted because the device is on a metered network
 * and the user requires Wi-Fi for uploads.
 *
 * This is a transient, retryable condition -- not a permanent failure.
 */
class WifiRequiredException(
    message: String = "Upload paused: Wi-Fi required but device is on a metered network"
) : Exception(message)
