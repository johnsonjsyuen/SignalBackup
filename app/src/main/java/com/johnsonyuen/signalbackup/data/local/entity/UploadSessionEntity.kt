package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The single in-flight resumable upload (table `upload_session`, row id = 1). */
@Entity(tableName = "upload_session")
data class UploadSessionEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val sessionUri: String,
    val localFileUri: String,
    val relativePath: String,
    val fileName: String,
    val totalBytes: Long,
    val localLastModifiedMillis: Long,
    val bytesUploaded: Long,
    val driveParentId: String,
    val createdAtMillis: Long,
    val driveFileId: String?,
    val remoteMd5: String?,
    val failureCount: Int,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
