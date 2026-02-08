/**
 * UploadProgress.kt - Data class representing the progress of an active upload.
 *
 * This model carries all the metrics needed to display a meaningful progress UI:
 * bytes transferred, total size, transfer speed, and estimated time remaining.
 *
 * The data flows from the Google Drive API's progress listener through the worker
 * layer (via WorkManager's setProgress) to the ViewModel and finally the UI.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Produced by [UploadWorker] from [PerformUploadUseCase]'s manual chunked upload callbacks.
 * - Consumed by [HomeViewModel] to populate the UI's progress display.
 * - Serialized to/from WorkManager's [Data] via companion helper methods.
 *
 * @see worker.UploadWorker for where progress is reported via WorkManager
 * @see ui.screen.home.HomeViewModel for where progress is observed
 * @see ui.component.UploadProgressCard for where progress is displayed
 */
package com.johnsonyuen.signalbackup.domain.model

import androidx.work.Data

/**
 * Snapshot of upload progress at a point in time.
 *
 * @property bytesUploaded Number of bytes uploaded so far.
 * @property totalBytes Total file size in bytes. Zero if unknown.
 * @property speedBytesPerSec Rolling average upload speed in bytes per second.
 * @property estimatedSecondsRemaining Estimated seconds until upload completes.
 *         -1 if the estimate is not yet available (e.g., speed is zero).
 */
data class UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val estimatedSecondsRemaining: Long,
) {
    /**
     * Upload completion percentage as a float in 0.0..1.0 range.
     * Returns 0 if totalBytes is unknown (zero).
     */
    val fraction: Float
        get() = if (totalBytes > 0) (bytesUploaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    /**
     * Upload completion percentage as an integer in 0..100 range.
     */
    val percentComplete: Int
        get() = (fraction * 100).toInt()

    /**
     * Serializes this progress snapshot into WorkManager's [Data] format.
     * WorkManager's setProgress(Data) is the channel for communicating progress
     * from a worker to observers (like the ViewModel).
     */
    fun toWorkData(): Data = Data.Builder()
        .putLong(KEY_BYTES_UPLOADED, bytesUploaded)
        .putLong(KEY_TOTAL_BYTES, totalBytes)
        .putLong(KEY_SPEED_BYTES_PER_SEC, speedBytesPerSec)
        .putLong(KEY_ESTIMATED_SECONDS_REMAINING, estimatedSecondsRemaining)
        .build()

    companion object {
        private const val KEY_BYTES_UPLOADED = "bytes_uploaded"
        private const val KEY_TOTAL_BYTES = "total_bytes"
        private const val KEY_SPEED_BYTES_PER_SEC = "speed_bytes_per_sec"
        private const val KEY_ESTIMATED_SECONDS_REMAINING = "estimated_seconds_remaining"

        /**
         * Deserializes an [UploadProgress] from WorkManager's [Data], or returns null
         * if the data does not contain progress information.
         *
         * @param data The WorkManager progress Data from WorkInfo.progress.
         * @return An [UploadProgress] instance, or null if the data is empty.
         */
        fun fromWorkData(data: Data): UploadProgress? {
            // WorkManager emits empty Data when no progress has been set yet.
            val totalBytes = data.getLong(KEY_TOTAL_BYTES, -1L)
            if (totalBytes < 0) return null

            return UploadProgress(
                bytesUploaded = data.getLong(KEY_BYTES_UPLOADED, 0L),
                totalBytes = totalBytes,
                speedBytesPerSec = data.getLong(KEY_SPEED_BYTES_PER_SEC, 0L),
                estimatedSecondsRemaining = data.getLong(KEY_ESTIMATED_SECONDS_REMAINING, -1L),
            )
        }
    }
}
