package com.johnsonyuen.signalbackup.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A local file or directory already mirrored to Drive (table `synced_file`). */
@Entity(tableName = "synced_file")
data class SyncedFileEntity(
    @PrimaryKey val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val driveFileId: String,
    val md5Checksum: String?,
    val syncedAtMillis: Long,
)
