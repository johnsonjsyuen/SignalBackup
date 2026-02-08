/**
 * UploadHistoryEntity.kt - Room database entity for persisting upload history records.
 *
 * This file defines the schema for the `upload_history` table in the local SQLite database.
 * Every time the app uploads (or attempts to upload) a backup file to Google Drive, a row
 * is inserted here to record what happened. This allows the History screen to display a
 * chronological log of all upload attempts.
 *
 * Architecture context:
 * - This is a **data layer** class -- it sits at the lowest level of the data layer,
 *   directly representing the database schema.
 * - The domain layer uses [UploadRecord] as its model; conversion from Entity to Record
 *   happens in [HistoryViewModel].
 * - The entity is accessed exclusively through [UploadHistoryDao], never directly.
 *
 * Room annotations used:
 * - [@Entity]: Marks this data class as a Room table definition.
 * - [@PrimaryKey]: Designates the unique identifier column, with auto-generation.
 *
 * @see data.local.db.UploadHistoryDao for database access methods
 * @see data.local.db.AppDatabase for the Room database definition
 * @see domain.model.UploadRecord for the domain-layer equivalent
 */
package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a single row in the `upload_history` SQLite table.
 *
 * Each instance records the result of one backup upload attempt, including whether it
 * succeeded or failed, file metadata, and the Drive location it was uploaded to.
 *
 * @property id Auto-generated primary key. Defaults to 0 to let Room auto-assign.
 * @property timestamp Epoch milliseconds when the upload was performed (System.currentTimeMillis()).
 * @property fileName The name of the .backup file that was uploaded (e.g., "signal-2024-01-15.backup").
 * @property fileSizeBytes The size of the uploaded file in bytes, used for display formatting.
 * @property status Either [STATUS_SUCCESS] or [STATUS_FAILED] -- stored as a String
 *           rather than an enum to keep the database schema simple and forward-compatible.
 * @property errorMessage Human-readable error description if the upload failed; null on success.
 * @property driveFolderId The Google Drive folder ID where the file was uploaded (or intended).
 * @property driveFileId The Google Drive file ID of the uploaded file; null if the upload failed.
 */
@Entity(tableName = "upload_history")
data class UploadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(index = true)
    val timestamp: Long,
    val fileName: String,
    val fileSizeBytes: Long,
    val status: String,
    val errorMessage: String?,
    val driveFolderId: String,
    val driveFileId: String?,
) {
    companion object {
        /** Status value indicating a successful upload. */
        const val STATUS_SUCCESS = "SUCCESS"

        /** Status value indicating a failed upload attempt. */
        const val STATUS_FAILED = "FAILED"
    }
}
