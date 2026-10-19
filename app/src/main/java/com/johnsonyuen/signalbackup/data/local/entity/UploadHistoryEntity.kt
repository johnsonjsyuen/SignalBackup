package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row of table `upload_history` (schema v5). [status] and [failureReason] hold enum names
 * (UploadResultStatus / FailureReason) as text.
 */
@Entity(tableName = "upload_history")
data class UploadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(index = true)
    val timestamp: Long,
    val fileName: String?,
    val fileSizeBytes: Long,
    @ColumnInfo(defaultValue = "0")
    val bytesUploaded: Long = 0,
    val status: String,
    val failureReason: String? = null,
    val errorMessage: String? = null,
    val driveFolderId: String? = null,
    val driveFileId: String? = null,
    val md5Checksum: String? = null,
)
