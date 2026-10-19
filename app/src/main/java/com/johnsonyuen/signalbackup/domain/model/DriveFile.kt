package com.johnsonyuen.signalbackup.domain.model

/** A backup item on Drive as shown in the Clean Up dialog (a legacy file or a snapshot folder). */
data class DriveFile(
    val id: String,
    val name: String,
    /** File size, or total size of a folder's contents when known (0 when unknown). */
    val sizeBytes: Long,
    val isFolder: Boolean = false,
    val createdTimeMillis: Long? = null,
)
