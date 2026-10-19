package com.johnsonyuen.signalbackup.domain.model

import java.time.Instant

/** A row of upload history as read by the UI. */
data class UploadRecord(
    val id: Long,
    val timestamp: Instant,
    /** Newest backup item name, or the file involved in a failure; null when unknown. */
    val fileName: String?,
    /** Size of the backup item (0 when unknown). */
    val fileSizeBytes: Long,
    /** Bytes sent during the run. */
    val bytesUploaded: Long,
    val status: UploadResultStatus,
    val failureReason: FailureReason?,
    /** Short, user-safe detail (no URLs, tokens or response bodies). */
    val errorMessage: String?,
    val driveFolderId: String?,
    /** Drive id of the uploaded file or snapshot folder, when the run produced one. */
    val driveFileId: String?,
    /** MD5 of the uploaded file as verified against Drive (legacy single-file backups). */
    val md5Checksum: String?,
)

/** A history row to insert; id and mapping are handled by the repository. */
data class NewUploadRecord(
    val timestamp: Instant,
    val fileName: String?,
    val fileSizeBytes: Long,
    val bytesUploaded: Long,
    val status: UploadResultStatus,
    val failureReason: FailureReason? = null,
    val errorMessage: String? = null,
    val driveFolderId: String? = null,
    val driveFileId: String? = null,
    val md5Checksum: String? = null,
)
