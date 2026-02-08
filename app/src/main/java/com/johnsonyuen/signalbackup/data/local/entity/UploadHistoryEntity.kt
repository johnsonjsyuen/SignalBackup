package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_history")
data class UploadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val fileName: String,
    val fileSizeBytes: Long,
    val status: String,
    val errorMessage: String?,
    val driveFolderId: String,
    val driveFileId: String?,
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
    }
}
