package com.johnsonyuen.signalbackup.domain.model

/** Live upload progress for the current run. */
data class UploadProgress(
    val bytesUploaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    /** -1 when unknown. */
    val estimatedSecondsRemaining: Long,
    /** File currently being sent (mirror runs may upload several). */
    val currentFileName: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesUploaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val percentComplete: Int
        get() = (fraction * 100).toInt()
}
