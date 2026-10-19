package com.johnsonyuen.signalbackup.domain.model

/**
 * The single in-flight resumable upload, persisted in Room (table `upload_session`) so it
 * survives process death. Only one large file is uploaded at a time.
 */
data class ResumableUploadSession(
    val sessionUri: String,
    /** SAF document URI of the local file. */
    val localFileUri: String,
    /** Path relative to the selected backup root, '/'-separated (e.g. "signal-backup-2026-10-01-03-00-00/backup.db"). */
    val relativePath: String,
    val fileName: String,
    val totalBytes: Long,
    /** Local file's lastModified when the session was created; a change invalidates the session. */
    val localLastModifiedMillis: Long,
    val bytesUploaded: Long,
    /** Drive folder the file is being created in. */
    val driveParentId: String,
    val createdAtMillis: Long,
    /** Set as soon as Drive answers 200/201, before verification. */
    val driveFileId: String? = null,
    val remoteMd5: String? = null,
    /** Consecutive failed resume attempts; the session is abandoned after a few. */
    val failureCount: Int = 0,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis - createdAtMillis > MAX_SESSION_AGE_MS

    companion object {
        const val MAX_SESSION_AGE_MS = 6L * 24L * 60L * 60L * 1000L
        const val MAX_FAILURES = 3
    }
}
