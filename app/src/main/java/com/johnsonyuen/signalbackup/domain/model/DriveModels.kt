package com.johnsonyuen.signalbackup.domain.model

import java.io.IOException

/** Metadata for a file or folder on Drive. */
data class DriveFileMeta(
    val id: String,
    val name: String,
    val sizeBytes: Long?,
    val md5Checksum: String?,
    val createdTimeMillis: Long?,
    val isFolder: Boolean,
    /** True when the item carries this app's appProperties tag (see DriveRepository). */
    val uploadedByApp: Boolean,
    /** True when the signed-in user owns the item. */
    val ownedByMe: Boolean,
)

/** Result of sending one chunk of a resumable upload. */
sealed interface ChunkUploadResult {
    /** 308: the server has persisted bytes [0, confirmedBytes). May be less than what was sent. */
    data class InProgress(val confirmedBytes: Long) : ChunkUploadResult
    /** 200/201: upload finished. */
    data class Complete(val file: DriveFileMeta) : ChunkUploadResult
}

/** Result of querying a resumable session with `Content-Range: bytes * /total`. */
sealed interface SessionProgress {
    data class InProgress(val confirmedBytes: Long) : SessionProgress
    data class Complete(val file: DriveFileMeta) : SessionProgress
    /** 404 or 410: the session can't be resumed; start a new one. */
    data object Expired : SessionProgress
}

/** Drive authorization requires user interaction. */
class NeedsConsentException(message: String = "Google Drive authorization required") : Exception(message)

/** No Google account is signed in. */
class NotSignedInException(message: String = "No Google account signed in") : Exception(message)

/**
 * A non-success HTTP response from Drive.
 * @param reason The first `errors[].reason` from the JSON error body when present
 *        (e.g. "storageQuotaExceeded", "rateLimitExceeded", "userRateLimitExceeded", "notFound").
 */
class DriveHttpException(
    val statusCode: Int,
    val reason: String?,
    message: String,
) : IOException(message) {
    val isRetryable: Boolean
        get() = statusCode >= 500 || statusCode == 429 || statusCode == 408 ||
            (statusCode == 403 && reason in RATE_LIMIT_REASONS)
    val isQuotaExceeded: Boolean get() = reason == "storageQuotaExceeded"

    companion object {
        val RATE_LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded")
    }
}
