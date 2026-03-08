package com.johnsonyuen.signalbackup.domain.model

/**
 * Thrown when an upload is attempted on a metered (non-Wi-Fi) network while the user
 * has enabled the "Wi-Fi only" setting.
 *
 * This exception is caught by [UploadWorker] to gracefully pause the upload and return
 * [Result.retry()]. The resumable upload session is preserved in DataStore, so the upload
 * will resume from the last confirmed byte offset when Wi-Fi becomes available.
 *
 * @see domain.usecase.PerformUploadUseCase.checkWifiConstraint
 * @see worker.UploadWorker.doWork
 */
class WifiRequiredException : Exception("Wi-Fi is required but the device is on a metered network")
