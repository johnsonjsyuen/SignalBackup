/**
 * UploadRecord.kt - Domain model for a historical upload record.
 *
 * This data class represents a completed (or attempted) upload in the domain layer.
 * It uses proper Kotlin/Java types (Instant, UploadResultStatus enum) rather than
 * raw database types (Long timestamps, String status), making it safer and more
 * ergonomic for the UI layer to consume.
 *
 * Architecture context:
 * - Part of the **domain layer** (domain/model package).
 * - Mapped from [UploadHistoryEntity] by the [toUploadRecord()] extension function.
 * - Consumed by [HistoryViewModel] and rendered by [HistoryItem] and [HistoryScreen].
 *
 * @see data.local.entity.UploadHistoryEntity for the database representation
 * @see data.local.entity.UploadHistoryMapper for the entity-to-record mapping
 * @see ui.screen.history.HistoryViewModel for where records are collected
 * @see ui.component.HistoryItem for where records are rendered
 */
package com.johnsonyuen.signalbackup.domain.model

import java.time.Instant

/**
 * A historical record of a single backup upload attempt.
 *
 * @property id Unique database ID.
 * @property timestamp When the upload was performed, as a Java time [Instant].
 * @property fileName The name of the backup file (e.g., "signal-2024-01-15.backup").
 * @property fileSizeBytes The size of the file in bytes, used for display formatting.
 * @property status Whether the upload succeeded or failed, as a type-safe enum.
 * @property errorMessage Error description if the upload failed; null on success.
 * @property driveFolderId The Google Drive folder where the file was uploaded (or intended).
 * @property driveFileId The Google Drive file ID; null if the upload failed.
 */
data class UploadRecord(
    val id: Long,
    val timestamp: Instant,
    val fileName: String,
    val fileSizeBytes: Long,
    val status: UploadResultStatus,
    val errorMessage: String?,
    val driveFolderId: String,
    val driveFileId: String?
)
