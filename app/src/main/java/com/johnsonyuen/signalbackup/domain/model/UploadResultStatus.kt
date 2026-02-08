/**
 * UploadResultStatus.kt - Enum representing the final outcome of a completed upload.
 *
 * Unlike [UploadStatus] (which tracks the live/in-progress state), this enum represents
 * the persisted historical result -- was the upload ultimately successful or did it fail?
 *
 * This enum is used by [UploadRecord] (the domain model) for type-safe status representation,
 * while the database stores the status as a plain String (see [UploadHistoryEntity]).
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Used in [UploadRecord] and rendered by [HistoryItem] in the History screen.
 * - Mapped from the database String via [UploadHistoryMapper.toUploadRecord()].
 *
 * @see domain.model.UploadRecord for the domain model that uses this
 * @see data.local.entity.UploadHistoryEntity for the database representation
 * @see data.local.entity.UploadHistoryMapper for the String-to-enum conversion
 */
package com.johnsonyuen.signalbackup.domain.model

/**
 * The final outcome of a completed upload attempt.
 */
enum class UploadResultStatus {
    /** The backup file was successfully uploaded to Google Drive. */
    SUCCESS,

    /** The upload attempt failed (see UploadRecord.errorMessage for details). */
    FAILED
}
