/**
 * ResumableUploadSession.kt - Domain model representing a persisted resumable upload session.
 *
 * When a large file upload to Google Drive is interrupted (by app kill, network loss, etc.),
 * the session URI issued by Google remains valid for approximately one week. This data class
 * holds all the information needed to resume the upload from where it left off.
 *
 * The session is persisted to DataStore so it survives process death. On the next upload
 * attempt, the app checks for a saved session, verifies it is still valid by querying
 * Google for the number of bytes received, and resumes from that offset.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Created by [GoogleDriveService] when a resumable upload is initiated.
 * - Persisted via [SettingsDataStore] / [SettingsRepository].
 * - Consumed by [PerformUploadUseCase] to decide whether to resume or start fresh.
 *
 * @see data.remote.GoogleDriveService for the resumable upload protocol implementation
 * @see domain.usecase.PerformUploadUseCase for the resume-or-start-fresh logic
 */
package com.johnsonyuen.signalbackup.domain.model

/**
 * Holds the state of an in-progress resumable upload to Google Drive.
 *
 * @property sessionUri The resumable session URI returned by Google Drive's initiation request.
 *        This URI is used for all subsequent chunk uploads and progress queries.
 * @property localFileUri The SAF content:// URI of the local backup file being uploaded.
 *        Used to verify the same file is still being uploaded on resume.
 * @property fileName The display name of the backup file (e.g., "signal-2024-01-15.backup").
 * @property bytesUploaded The number of bytes confirmed as received by Google Drive.
 *        On resume, this is updated by querying the session URI.
 * @property totalBytes The total size of the file in bytes.
 * @property driveFolderId The Google Drive folder ID the file is being uploaded to.
 * @property createdAtMillis Epoch millis when the session was initiated. Used to detect
 *        sessions that are likely expired (Google keeps them for ~1 week).
 */
data class ResumableUploadSession(
    val sessionUri: String,
    val localFileUri: String,
    val fileName: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
    val driveFolderId: String,
    val createdAtMillis: Long,
) {
    companion object {
        /**
         * Maximum age of a resumable session URI before we consider it expired and start fresh.
         * Google keeps session URIs valid for approximately 1 week, but we use 6 days to
         * provide a safety margin.
         */
        const val MAX_SESSION_AGE_MS = 6L * 24L * 60L * 60L * 1000L // 6 days
    }

    /**
     * Returns true if this session is likely still valid based on its age.
     * Google Drive resumable session URIs expire after approximately 1 week.
     */
    fun isExpired(): Boolean {
        return (System.currentTimeMillis() - createdAtMillis) > MAX_SESSION_AGE_MS
    }
}
