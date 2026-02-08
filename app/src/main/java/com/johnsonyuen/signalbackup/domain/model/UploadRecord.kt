package com.johnsonyuen.signalbackup.domain.model

import java.time.Instant

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
