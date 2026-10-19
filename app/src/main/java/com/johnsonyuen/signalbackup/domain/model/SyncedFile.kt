package com.johnsonyuen.signalbackup.domain.model

/**
 * One local file or directory that has been mirrored to Drive and verified.
 * Stored in Room (table `synced_file`), keyed by [relativePath] ('/'-separated, relative to the
 * selected backup root; directories have no trailing slash).
 */
data class SyncedFile(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val driveFileId: String,
    val md5Checksum: String?,
    val syncedAtMillis: Long,
)
